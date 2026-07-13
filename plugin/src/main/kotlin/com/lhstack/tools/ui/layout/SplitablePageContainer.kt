package com.lhstack.tools.ui.layout

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.MouseDragHelper
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.JBEditorTabsBase
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import com.lhstack.tools.components.FloatingDialog
import com.lhstack.tools.components.PluginTabPanel
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.catch
import com.lhstack.tools.plugins.PluginInfo
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.*
import javax.swing.*
import javax.swing.border.Border
import javax.swing.border.EmptyBorder

class SplitablePageContainer(
    val project: Project,
    private val rootDisposable: Disposable,
    parent: SplitablePageContainer? = null
) : JPanel(BorderLayout()), Disposable, TabsListener {

    private var tabs: JBEditorTabsBase? = null
    private var splitter: Splitter? = null

    // Child containers if split
    private var firstChild: SplitablePageContainer? = null
    private var secondChild: SplitablePageContainer? = null

    // Parent container
    var parentContainer: SplitablePageContainer? = parent

    val isSplit: Boolean
        get() = splitter != null

    val hasContent: Boolean
        get() = isSplit || (tabs?.tabCount ?: 0) > 0

    // Track active leaf for adding new tabs
    private var lastActiveLeaf: SplitablePageContainer? = null

    // Flag to prevent reentrant merge during split
    private var isSplitting: Boolean = false

    // Guard to suppress auto-merge while moving tabs across containers
    private var transferDepth: Int = 0

    private var defaultTabsBorder: Border? = null
    private var dropHighlight: Boolean = false
    private var highlightedDropContainer: SplitablePageContainer? = null

    private data class DragOutState(
        val tab: TabInfo,
        val source: SplitablePageContainer,
        val sourceTabs: JBEditorTabsBase,
        val wasHidden: Boolean,
        var dragImage: DragImageDialog? = null,
        var target: SplitablePageContainer? = null,
        var previewTab: TabInfo? = null,
        var previewTabs: JBEditorTabsBase? = null,
        var dropIndex: Int? = null
    )

    private var dragOutState: DragOutState? = null
    private val dragOutDelegate: TabInfo.DragOutDelegate = ToolTabDragOutDelegate()


    init {
        Disposer.register(rootDisposable, this)
        initTabs()
        // Default self as active
        lastActiveLeaf = this
    }

    private fun getRoot(): SplitablePageContainer {
        var root = this
        while (root.parentContainer != null) {
            root = root.parentContainer!!
        }
        return root
    }

    private fun updateActiveLeaf(leaf: SplitablePageContainer) {
        getRoot().propagateActiveLeaf(leaf)
    }

    private fun propagateActiveLeaf(leaf: SplitablePageContainer) {
        lastActiveLeaf = leaf
        firstChild?.propagateActiveLeaf(leaf)
        secondChild?.propagateActiveLeaf(leaf)
    }

    private fun initTabs() {
        val newTabs = JBTabsFactory.createEditorTabs(project, this)
        configureTabs(newTabs)
        this.tabs = newTabs
        add(newTabs.component, BorderLayout.CENTER)
    }

    private fun ensureDragOutDelegate(info: TabInfo) {
        if (info.dragOutDelegate !== dragOutDelegate) {
            info.setDragOutDelegate(dragOutDelegate)
        }
    }

    private fun clearDropOver(state: DragOutState) {
        clearDropPreview(state)
        state.target = null
        state.dropIndex = null
        updateDropHighlight(null)
    }

    private fun createPreviewTab(original: TabInfo): TabInfo {
        val placeholder = JPanel()
        placeholder.isOpaque = false
        val preview = TabInfo(placeholder)
        preview.setText(original.text)
        preview.setIcon(original.icon)
        preview.setTooltipText(original.tooltipText)
        return preview
    }

    private fun clearDropPreview(state: DragOutState) {
        val preview = state.previewTab ?: return
        val previewTabs = state.previewTabs ?: return
        beginTabTransfer()
        try {
            previewTabs.resetDropOver(preview)
        } finally {
            endTabTransfer()
        }
        state.previewTab = null
        state.previewTabs = null
        state.dropIndex = null
    }

    private fun toTabsRelativePoint(tabs: JBEditorTabsBase, screenPoint: Point): RelativePoint {
        val local = Point(screenPoint)
        SwingUtilities.convertPointFromScreen(local, tabs.component)
        return RelativePoint(tabs.component, local)
    }

    private fun createCloneTabForSplit(original: TabInfo): TabInfo? {
        val originalPanel = original.component as? PluginTabPanel ?: return null
        val plugin = originalPanel.plugin
        if (!plugin.supportMultiOpens()) return null
        val pluginInfo = originalPanel.pluginInfo
        val pluginPanel = plugin.catch("创建插件面板") { plugin.createPanel(project) } ?: return null
        val pluginTabPanel = PluginTabPanel(
            pluginInfo,
            plugin,
            pluginPanel,
            null,
            UUID.randomUUID().toString()
        )
        pluginTabPanel.layout = BorderLayout()
        pluginTabPanel.add(pluginPanel, BorderLayout.CENTER)
        val tabInfo = TabInfo(pluginTabPanel)
        tabInfo.setIcon(original.icon ?: plugin.pluginTabIcon())
        tabInfo.setText(original.text ?: pluginInfo.name)
        tabInfo.setTooltipText(original.tooltipText ?: plugin.pluginDesc())
        tabInfo.setTabLabelActions(DefaultActionGroup(object : AnAction({ "关闭" }, AllIcons.Actions.Close) {
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.icon = AllIcons.Actions.Close
                e.presentation.hoveredIcon = AllIcons.Actions.CloseHovered
            }

            override fun actionPerformed(e: AnActionEvent) {
                plugin.catch("插件面板关闭回调") { plugin.closePanel(project, pluginPanel) }
                getRoot().removeTab(tabInfo)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }), "tabActionGroup")
        plugin.tabPanelActions(project, pluginPanel).takeIf { it.isNotEmpty() }?.let {
            tabInfo.setTabPaneActions(DefaultActionGroup(it))
        }
        return tabInfo
    }

    private fun showClonedTabPanel(tabInfo: TabInfo) {
        val panel = tabInfo.component as? PluginTabPanel ?: return
        panel.plugin.catch("插件面板显示回调") {
            panel.plugin.showPanel(project, panel.pluginPanel)
        }
    }

    private fun computeDropIndexFromPoint(targetTabs: JBEditorTabsBase, screenPoint: Point): Int {
        val local = Point(screenPoint)
        SwingUtilities.convertPointFromScreen(local, targetTabs.component)
        val tabAtPoint = targetTabs.findInfo(local)
        val infos = targetTabs.tabs
        if (infos.isEmpty()) return 0
        val isHorizontal = !targetTabs.presentation.tabsPosition.isSide
        val centers = infos.mapNotNull { info ->
            val label = targetTabs.getTabLabel(info) ?: return@mapNotNull null
            val bounds = label.bounds
            if (bounds.width <= 0 || bounds.height <= 0) return@mapNotNull null
            val center = if (isHorizontal) bounds.x + bounds.width / 2 else bounds.y + bounds.height / 2
            Pair(info, center)
        }.sortedBy { it.second }
        if (centers.isEmpty()) return targetTabs.tabCount
        val position = if (isHorizontal) local.x else local.y
        val commitBefore = tabAtPoint?.let { targetTabs.getIndexOf(it) } ?: -1
        for ((info, center) in centers) {
            val index = targetTabs.getIndexOf(info)
            val threshold = if (index == commitBefore) center + 1 else center
            if (position < threshold) {
                val index = targetTabs.getIndexOf(info)
                return if (index < 0) 0 else index
            }
        }
        val lastIndex = targetTabs.getIndexOf(centers.last().first)
        return if (lastIndex < 0) targetTabs.tabCount else (lastIndex + 1).coerceAtMost(targetTabs.tabCount)
    }

    private fun createDragImage(tab: TabInfo): DragImageDialog? {
        val image = createTabLabelImage(tab) ?: return null
        val dialog = DragImageDialog(this)
        dialog.setImage(image)
        return dialog
    }

    private fun createTabLabelImage(tab: TabInfo): Image? {
        val fallback = JLabel(tab.text ?: "")
        fallback.icon = tab.icon
        fallback.border = EmptyBorder(4, 6, 4, 6)
        val size = fallback.preferredSize
        val width = size.width.coerceAtLeast(1)
        val height = size.height.coerceAtLeast(1)
        val gc = fallback.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val image = ImageUtil.createImage(gc, width, height, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            fallback.setBounds(0, 0, width, height)
            fallback.paint(g2)
        } finally {
            g2.dispose()
        }
        return image
    }

    private fun updateDragImage(dialog: DragImageDialog, screenPoint: Point) {
        val x = screenPoint.x - dialog.width / 2
        val y = screenPoint.y - dialog.height / 2
        dialog.setLocation(x, y)
        if (!dialog.isVisible) {
            dialog.isVisible = true
        }
    }

    private class DragImageDialog(owner: JPanel) : JDialog(SwingUtilities.getWindowAncestor(owner)) {
        private val label = JLabel()

        init {
            try {
                type = Type.POPUP
            } catch (_: Throwable) {
            }
            focusableWindowState = false
            isUndecorated = true
            isAlwaysOnTop = true
            try {
                opacity = 0.85f
            } catch (_: Throwable) {
            }
            contentPane = label
        }

        fun setImage(image: Image) {
            label.icon = ImageIcon(image)
            pack()
        }
    }

    private fun beginTabTransfer() {
        val root = getRoot()
        root.transferDepth += 1
    }

    private fun endTabTransfer() {
        val root = getRoot()
        if (root.transferDepth > 0) {
            root.transferDepth -= 1
        }
    }

    private fun isTabTransferInProgress(): Boolean {
        return getRoot().transferDepth > 0
    }

    private fun dropHighlightBorder(): Border {
        return JBUI.Borders.customLine(JBColor(0x4B90FF, 0x4B90FF), 2)
    }

    private fun setDropHighlight(enabled: Boolean) {
        if (dropHighlight == enabled) return
        dropHighlight = enabled
        val tabsComponent = tabs?.component ?: return
        if (defaultTabsBorder == null) {
            defaultTabsBorder = tabsComponent.border
        }
        tabsComponent.border = if (enabled) dropHighlightBorder() else defaultTabsBorder
        tabsComponent.revalidate()
        tabsComponent.repaint()
    }

    private fun updateDropHighlight(target: SplitablePageContainer?) {
        val root = getRoot()
        if (root.highlightedDropContainer === target) return
        root.highlightedDropContainer?.setDropHighlight(false)
        root.highlightedDropContainer = target
        root.highlightedDropContainer?.setDropHighlight(true)
    }

    fun findContainerAtScreen(screenPoint: java.awt.Point): SplitablePageContainer? {
        if (!isShowing) return null

        val bounds = java.awt.Rectangle(locationOnScreen, size)
        if (!bounds.contains(screenPoint)) return null

        return if (isSplit) {
            firstChild?.findContainerAtScreen(screenPoint)
                ?: secondChild?.findContainerAtScreen(screenPoint)
        } else {
            this
        }
    }

    override fun dispose() {
        val root = getRoot()
        if (root.highlightedDropContainer === this) {
            root.updateDropHighlight(null)
        }
        getRoot().dragOutState?.let { state ->
            if (state.source === this || state.target === this) {
                getRoot().clearDropOver(state)
                getRoot().dragOutState = null
            }
        }
        // Only dispose tabs if we still own it (not transferred)
        tabs?.let { t ->
            // Remove all tabs first to prevent component disposal
            t.tabs.toList().forEach { info ->
                t.removeTab(info)
            }
            (t as? Disposable)?.let { Disposer.dispose(it) }
        }
        tabs = null

        // Children are registered with Disposer, they will be disposed automatically
        // But we should clear references
        firstChild = null
        secondChild = null
        splitter = null
    }

    // --- Splitting Logic ---

    fun split(vertical: Boolean, targetTab: TabInfo? = null, moveTarget: Boolean = true) {
        if (isSplit) return // Already split
        val currentTabs = tabs ?: return
        if (currentTabs.tabCount <= 0) return
        val effectiveMoveTarget = moveTarget

        // Set flag to prevent reentrant merge
        isSplitting = true

        // Use targetTab if provided, otherwise fall back to selectedInfo
        val tabToSplit = targetTab ?: currentTabs.selectedInfo ?: currentTabs.tabs.firstOrNull()
        if (tabToSplit == null) {
            isSplitting = false
            return
        }
        val canDuplicate = (tabToSplit.component as? PluginTabPanel)?.plugin?.supportMultiOpens() ?: true
        if (!effectiveMoveTarget && !canDuplicate) {
            isSplitting = false
            return
        }
        val selectedBeforeSplit = currentTabs.selectedInfo

        // Collect all tabs BEFORE any mutation
        val allTabs = currentTabs.tabs.toList()

        // Remove all tabs from current (without disposing components)
        allTabs.forEach { info ->
            currentTabs.removeTab(info)
        }

        // Now dispose the old JBTabs wrapper (empty now, won't dispose components)
        remove(currentTabs.component)
        (currentTabs as? Disposable)?.let { Disposer.dispose(it) }
        this.tabs = null

        // Init Splitter
        val newSplitter = JBSplitter(vertical, 0.5f)

        // Create child containers
        val child1 = SplitablePageContainer(project, rootDisposable, this)
        val child2 = SplitablePageContainer(project, rootDisposable, this)

        // Move existing TabInfo objects into children
        allTabs.forEach { info ->
            if (effectiveMoveTarget && info == tabToSplit) {
                child2.addExistingTab(info)
            } else {
                child1.addExistingTab(info)
            }
        }
        val clonedTab = if (!effectiveMoveTarget) createCloneTabForSplit(tabToSplit) else null
        if (clonedTab != null) {
            child2.addExistingTab(clonedTab)
        }

        newSplitter.firstComponent = child1
        newSplitter.secondComponent = child2

        this.splitter = newSplitter
        this.firstChild = child1
        this.secondChild = child2

        add(newSplitter, BorderLayout.CENTER)

        // Clear the splitting flag
        isSplitting = false

        // Select target tab in new split
        if (effectiveMoveTarget) {
            tabToSplit?.let { child2.tabs?.select(it, true) }
            if (selectedBeforeSplit != null && selectedBeforeSplit != tabToSplit) {
                child1.tabs?.select(selectedBeforeSplit, true)
            }
            updateActiveLeaf(child2)
        } else {
            if (clonedTab != null) {
                selectedBeforeSplit?.let { child1.tabs?.select(it, true) }
                child2.tabs?.select(clonedTab, true)
                updateActiveLeaf(child2)
                showClonedTabPanel(clonedTab)
            } else {
                selectedBeforeSplit?.let { child1.tabs?.select(it, true) }
                updateActiveLeaf(child1)
            }
        }

        revalidate()
        repaint()
    }

    fun removeTab(tabInfo: TabInfo) {
        val container = findContainerOf(tabInfo)
        container?.closeTab(tabInfo)
    }

    // --- Public API ---

    fun addTab(tabInfo: TabInfo) {
        val target = findTargetContainer()
        target.addExistingTab(tabInfo)
        target.tabs?.select(tabInfo, true)
    }

    private fun findTargetContainer(): SplitablePageContainer {
        // Use tracked active leaf if available, valid (not split), and still attached
        val active = lastActiveLeaf
        // Check if active is valid: not split, has tabs, parent link intact, and is a descendant of root
        if (active != null && !active.isSplit && active.tabs != null && active.isShowing && isDescendant(active)) {
            return active
        }
        // Fallback: Find first leaf with tabs, or just first leaf
        return findFirstLeafWithTabs() ?: findFirstLeaf()
    }

    private fun isDescendant(container: SplitablePageContainer): Boolean {
        if (container === this) return true
        if (!isSplit) return false
        return firstChild?.isDescendant(container) == true || secondChild?.isDescendant(container) == true
    }

    private fun findFirstLeafWithTabs(): SplitablePageContainer? {
        if (!isSplit) {
            return if ((tabs?.tabCount ?: 0) > 0) this else null
        }
        return firstChild?.findFirstLeafWithTabs() ?: secondChild?.findFirstLeafWithTabs()
    }

    private fun findFirstLeaf(): SplitablePageContainer {
        if (!isSplit) return this
        return firstChild?.findFirstLeaf() ?: secondChild?.findFirstLeaf() ?: this
    }

    fun addExistingTab(info: TabInfo) {
        val t = tabs ?: return
        ensureDragOutDelegate(info)
        t.addTab(info)
        // Fix up PluginTabPanel reference if needed
        if (info.component is PluginTabPanel) {
            (info.component as PluginTabPanel).tabsPanel = t
        }
    }

    /**
     * 在指定位置插入 Tab
     * @param info 要插入的 Tab
     * @param index 目标位置，-1 或超出范围则追加到末尾
     */
    fun insertTab(info: TabInfo, index: Int) {
        val t = tabs ?: return
        val insertIndex = if (index < 0 || index > t.tabCount) t.tabCount else index
        ensureDragOutDelegate(info)
        t.addTab(info, insertIndex)
        // Fix up PluginTabPanel reference if needed
        if (info.component is PluginTabPanel) {
            (info.component as PluginTabPanel).tabsPanel = t
        }
    }

    // --- Merging Logic ---

    override fun tabRemoved(tabToRemove: TabInfo) {
        checkEmpty()
    }

    fun checkEmpty() {
        // Don't trigger merge during split operation
        if (isSplitting || isTabTransferInProgress()) return

        val t = tabs
        if (t != null && t.tabCount == 0) {
            // This container is empty.
            // Notify parent to merge.
            parentContainer?.mergeChild(this)
        }
    }

    fun mergeChild(emptyChild: SplitablePageContainer) {
        if (!isSplit) return

        val survivor = if (firstChild == emptyChild) secondChild else firstChild
        if (survivor == null) return // Should not happen

        // Structure: THIS (Splitter) -> [EmptyChild, Survivor]
        // We want THIS to become Survivor.

        // 1. Remove Splitter from UI
        remove(splitter)
        splitter = null

        // 2. Absorb Survivor
        // If Survivor is Split, we become Split (adopt its children)
        // If Survivor is Leaf, we become Leaf (adopt its tabs)

        if (survivor.isSplit) {
            // Adopt survivor's splitter and children
            this.splitter = survivor.splitter
            this.firstChild = survivor.firstChild?.also { it.parentContainer = this }
            this.secondChild = survivor.secondChild?.also { it.parentContainer = this }

            // Clear survivor's refs so it doesn't dispose them
            survivor.splitter = null
            survivor.firstChild = null
            survivor.secondChild = null

            add(this.splitter, BorderLayout.CENTER)
        } else {
            // Survivor is Leaf with Tabs
            val survivorTabs = survivor.tabs
            val tabInfos = survivorTabs?.tabs?.toList() ?: emptyList()

            // Remove tabs from survivor without disposing components
            if (survivorTabs != null) {
                val wasSplitting = survivor.isSplitting
                survivor.isSplitting = true
                tabInfos.forEach { info -> survivorTabs.removeTab(info) }
                survivor.isSplitting = wasSplitting
                survivor.remove(survivorTabs.component)
            }

            survivor.tabs = null

            // Init our tabs
            val newTabs = JBTabsFactory.createEditorTabs(project, this)
            configureTabs(newTabs)
            this.tabs = newTabs
            add(newTabs.component, BorderLayout.CENTER)

            // Move existing TabInfo objects into this container
            tabInfos.forEach { info ->
                addExistingTab(info)
            }
        }

        // Clear child references BEFORE disposing
        this.firstChild = null
        this.secondChild = null

        // Update lastActiveLeaf to point to 'this' (the merged container)
        updateActiveLeaf(this)

        // Now safe to dispose the container wrappers (they no longer own the components)
        Disposer.dispose(emptyChild)
        Disposer.dispose(survivor)

        revalidate()
        repaint()

        // Recurse check
        if (this.tabs?.tabCount == 0 && !this.isSplit) {
            parentContainer?.mergeChild(this)
        }
    }

    /**
     * 取消分屏 - 将所有Tab合并到一个容器
     */
    private fun unsplit() {
        val parent = parentContainer ?: return

        // 收集当前容器及兄弟容器的所有 Tab
        val sibling = if (parent.firstChild == this) parent.secondChild else parent.firstChild
        val allTabInfos = mutableListOf<TabInfo>()

        collectAllTabInfos(this, allTabInfos)
        sibling?.let { collectAllTabInfos(it, allTabInfos) }

        // 在父容器中重建所有 Tab

        // 1. 如果父容器是分屏状态，移除分屏器
        if (parent.isSplit) {
            parent.remove(parent.splitter)
            parent.splitter = null
        }

        // 2. 清理子容器引用
        parent.firstChild = null
        parent.secondChild = null

        // 3. 重建父容器的 tabs
        val newTabs = JBTabsFactory.createEditorTabs(project, parent)
        parent.configureTabs(newTabs)

        // 4. 添加所有收集到的 Tab
        parent.tabs = newTabs
        allTabInfos.forEach { info ->
            parent.addExistingTab(info)
        }

        parent.add(newTabs.component, BorderLayout.CENTER)

        // 更新最后活动叶子节点
        parent.updateActiveLeaf(parent)

        // 重新验证父容器
        parent.revalidate()
        parent.repaint()

        // 销毁当前容器和兄弟容器
        Disposer.dispose(this)
        sibling?.let { Disposer.dispose(it) }
    }

    private fun collectAllTabInfos(container: SplitablePageContainer, result: MutableList<TabInfo>) {
        if (container.isSplit) {
            container.firstChild?.let { collectAllTabInfos(it, result) }
            container.secondChild?.let { collectAllTabInfos(it, result) }

            // 递归清理分裂的子容器
            container.splitter = null
            // 注意：不要在这里 dispose，因为 unsplit 会统一处理顶级子容器，
            // 但如果有多级嵌套，需要确保资源释放。
            // 这里的逻辑是收集数据，原来的组件会被重新包装到新的 TabInfo 中，
            // 所以原来的容器结构只要不再引用就会被垃圾回收（或显式 dispose）。
            // 实际上，unsplit 会销毁当前层级的子容器，其内部的孙子容器也会随之 dispose。
            // 但我们需要先把 TabInfo 从原来的 Tabs 中移除，避免 dispose 时连带 dispose 了组件内容。

        } else {
            // 是叶子节点，转移 Tab
            val cTabs = container.tabs ?: return
            val wasSplitting = container.isSplitting
            container.isSplitting = true
            cTabs.tabs.toList().forEach { info ->
                result.add(info)
                cTabs.removeTab(info)
            }
            container.isSplitting = wasSplitting
            container.remove(cTabs.component)
            container.tabs = null
        }
    }

    private fun configureTabs(newTabs: JBEditorTabsBase) {
        newTabs.addListener(this)
        defaultTabsBorder = newTabs.component.border
        if (dropHighlight) {
            newTabs.component.border = dropHighlightBorder()
        }

        // Listen for selection to update active leaf (same as in initTabs)
        newTabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                if (newSelection != null && !isSplit) {
                    updateActiveLeaf(this@SplitablePageContainer)
                }
            }
        })

        // Enable built-in drag
        newTabs.presentation.setTabDraggingEnabled(true)
        MouseDragHelper.setComponentDraggable(newTabs.component, true)
        newTabs.tabs.toList().forEach { ensureDragOutDelegate(it) }

        // Track active leaf on click
        newTabs.addTabMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val point = SwingUtilities.convertPoint(e.component, e.point, newTabs.component)
                newTabs.findInfo(point)?.let { ensureDragOutDelegate(it) }
                newTabs.presentation.setTabDraggingEnabled(true)
                updateActiveLeaf(this@SplitablePageContainer)
            }
        })

        // Setup Popup (Copy from init)
        val tabsPopupGroup = DefaultActionGroup()

        // Helper function to get the right-clicked tab from ActionEvent
        fun getTargetTab(e: AnActionEvent): TabInfo? {
            newTabs.targetInfo?.let { return it }
            val inputEvent = e.inputEvent
            if (inputEvent is java.awt.event.MouseEvent) {
                // Convert to tabs component coordinates
                val point = SwingUtilities.convertPoint(
                    inputEvent.component,
                    inputEvent.point,
                    newTabs.component
                )
                return newTabs.findInfo(point)
            }
            return newTabs.selectedInfo
        }

        fun canDuplicateTab(info: TabInfo?): Boolean {
            val panel = info?.component as? PluginTabPanel ?: return true
            return panel.plugin.supportMultiOpens()
        }

        tabsPopupGroup.add(object : AnAction({ "关闭" }, Icons.closeAllIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                getTargetTab(e)?.let { closeTab(it) }
            }
        })
        tabsPopupGroup.add(object : AnAction({ "关闭其他" }, Icons.closeOtherIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                val current = getTargetTab(e) ?: return
                newTabs.tabs.toList().forEach { if (it != current) closeTab(it) }
            }
        })
        tabsPopupGroup.add(object : AnAction({ "关闭所有" }, Icons.closeAllIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                newTabs.tabs.toList().forEach { closeTab(it) }
            }
        })
        tabsPopupGroup.addSeparator()
        tabsPopupGroup.add(object : AnAction({ "新窗口打开" }, Icons.closeAllIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                getTargetTab(e)?.let {
                    it.isHidden = true
                    FloatingDialog(project, it.text, it.component) {
                        it.isHidden = false
                    }.isVisible = true
                }
            }
        })
        tabsPopupGroup.add(object : AnAction({ "向右分屏" }, Icons.moveright()) {
            override fun actionPerformed(e: AnActionEvent) {
                split(false, getTargetTab(e), moveTarget = false)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isVisible = newTabs.tabCount > 1
                val target = getTargetTab(e)
                e.presentation.isEnabled = newTabs.tabCount > 0 && target != null && canDuplicateTab(target)
            }
        })
        tabsPopupGroup.add(object : AnAction({ "向右分屏并移动" }, Icons.moveright()) {
            override fun actionPerformed(e: AnActionEvent) {
                split(false, getTargetTab(e), moveTarget = true)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isVisible = newTabs.tabCount > 1
                e.presentation.isEnabled = (newTabs.tabCount ?: 0) > 0
            }
        })
        tabsPopupGroup.add(object : AnAction({ "向下分屏" }, Icons.movedown()) {
            override fun actionPerformed(e: AnActionEvent) {
                split(true, getTargetTab(e), moveTarget = false)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isVisible = newTabs.tabCount > 1
                val target = getTargetTab(e)
                e.presentation.isEnabled = newTabs.tabCount > 0 && target != null && canDuplicateTab(target)
            }
        })
        tabsPopupGroup.add(object : AnAction({ "向下分屏并移动" }, Icons.movedown()) {
            override fun actionPerformed(e: AnActionEvent) {
                split(true, getTargetTab(e), moveTarget = true)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isVisible = newTabs.tabCount > 1
                e.presentation.isEnabled = (newTabs.tabCount ?: 0) > 0
            }
        })

        // 只有存在分屏时才显示取消分屏选项
        if (parentContainer != null) {
            tabsPopupGroup.addSeparator()
            tabsPopupGroup.add(object : AnAction({ "取消分屏" }, AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) {
                    unsplit()
                }
            })
        }
        newTabs.setPopupGroup(tabsPopupGroup, "SplitablePageContainer", true)
    }

    private inner class ToolTabDragOutDelegate : TabInfo.DragOutDelegate {
        override fun dragOutStarted(mouseEvent: MouseEvent, info: TabInfo) {
            val root = getRoot()
            root.dragOutState?.let { existing ->
                val sourceTabs = existing.sourceTabs
                val stale = !existing.source.isDisplayable ||
                        !sourceTabs.component.isDisplayable ||
                        !sourceTabs.component.isShowing
                if (stale) {
                    root.clearDropOver(existing)
                    root.dragOutState = null
                } else {
                    return
                }
            }
            val source = root.findContainerOf(info) ?: return
            val sourceTabs = source.tabs ?: return
            val wasHidden = info.isHidden
            root.dragOutState = DragOutState(
                tab = info,
                source = source,
                sourceTabs = sourceTabs,
                wasHidden = wasHidden
            )
            root.dragOutState?.dragImage = createDragImage(info)
            root.dragOutState?.dragImage?.let { updateDragImage(it, mouseEvent.locationOnScreen) }
            info.isHidden = true
        }

        override fun processDragOut(event: MouseEvent, source: TabInfo) {
            val root = getRoot()
            val state = root.dragOutState ?: return
            state.dragImage?.let { updateDragImage(it, event.locationOnScreen) }
            val target = root.findContainerAtScreen(event.locationOnScreen)
            if (target == null) {
                root.clearDropOver(state)
                return
            }
            root.updateDropHighlight(target)
            val targetTabs = target.tabs ?: return
            val point = toTabsRelativePoint(targetTabs, event.locationOnScreen)
            if (state.previewTabs !== targetTabs || state.previewTab == null) {
                root.clearDropPreview(state)
                val preview = createPreviewTab(state.tab)
                state.previewTab = preview
                state.previewTabs = targetTabs
                beginTabTransfer()
                try {
                    targetTabs.startDropOver(preview, point)
                } finally {
                    endTabTransfer()
                }
            } else {
                val preview = state.previewTab ?: return
                targetTabs.processDropOver(preview, point)
            }
            state.dropIndex = computeDropIndexFromPoint(targetTabs, event.locationOnScreen)
            state.target = target
        }

        override fun dragOutFinished(event: MouseEvent, source: TabInfo) {
            val root = getRoot()
            val state = root.dragOutState ?: return
            root.dragOutState = null
            state.dragImage?.dispose()
            state.dragImage = null
            val targetFromEvent = root.findContainerAtScreen(event.locationOnScreen)
            val target = targetFromEvent ?: state.target
            val targetTabs = target?.tabs

            if (target == null || targetTabs == null) {
                root.clearDropOver(state)
                state.tab.isHidden = state.wasHidden
                return
            }

            val sameContainer = target === state.source
            if (sameContainer) {
                val currentIndex = state.sourceTabs.tabs.indexOf(state.tab)
                val index = state.dropIndex ?: computeDropIndexFromPoint(state.sourceTabs, event.locationOnScreen)
                root.clearDropOver(state)
                if (index >= 0 && (currentIndex < 0 || currentIndex != index)) {
                    val adjustedIndex = if (currentIndex >= 0 && index > currentIndex) index - 1 else index
                    beginTabTransfer()
                    try {
                        state.sourceTabs.removeTab(state.tab)
                        state.source.insertTab(state.tab, adjustedIndex)
                        state.source.tabs?.select(state.tab, true)
                    } finally {
                        endTabTransfer()
                    }
                }
                state.tab.isHidden = state.wasHidden
                return
            }

            val dropIndex = state.dropIndex ?: computeDropIndexFromPoint(targetTabs, event.locationOnScreen)
            root.clearDropOver(state)
            beginTabTransfer()
            try {
                state.sourceTabs.removeTab(state.tab)
                target.insertTab(state.tab, dropIndex)
                target.tabs?.select(state.tab, true)
                target.updateActiveLeaf(target)
            } finally {
                endTabTransfer()
            }
            state.tab.isHidden = state.wasHidden
            state.source.checkEmpty()
        }

        override fun dragOutCancelled(source: TabInfo) {
            val root = getRoot()
            val state = root.dragOutState ?: return
            root.dragOutState = null
            root.clearDropOver(state)
            state.dragImage?.dispose()
            state.dragImage = null
            state.tab.isHidden = state.wasHidden
        }
    }

    private fun closeTab(info: TabInfo) {
        tabs?.removeTab(info)
        if (info.component is PluginTabPanel) {
            val panel = info.component as PluginTabPanel
            panel.plugin.catch("关闭面板") {
                // Call close on plugin?
                // Needs access to method closePanel
            }
            Disposer.dispose(panel)
        }
    }

    override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {}
    override fun beforeSelectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {}
    override fun tabsMoved() {}

    // --- Public API for ContentPageAction ---

    fun findContainerOf(tabInfo: TabInfo): SplitablePageContainer? {
        if (isSplit) {
            return firstChild?.findContainerOf(tabInfo) ?: secondChild?.findContainerOf(tabInfo)
        } else {
            if (tabs?.tabs?.contains(tabInfo) == true) return this
            return null
        }
    }

    fun findActiveContainer(): SplitablePageContainer? {
        // Return leaf that has focus or last valid?
        // Simplification: if leaf, return this. If split, recurse?
        // We might need a global tracker or just traverse.
        if (!isSplit) return this

        // Try to find one with selection?
        return firstChild?.findActiveContainer() ?: secondChild?.findActiveContainer()
    }

    fun closePluginTabs(pluginInfo: PluginInfo) {
        if (isSplit) {
            firstChild?.closePluginTabs(pluginInfo)
            secondChild?.closePluginTabs(pluginInfo)
        } else {
            val toRemove = tabs?.tabs?.filter {
                (it.component as? PluginTabPanel)?.pluginInfo?.id == pluginInfo.id
            } ?: emptyList()
            toRemove.forEach { closeTab(it) }
        }
    }

    fun selectPluginTab(pluginInfo: PluginInfo): Boolean {
        if (isSplit) {
            if (firstChild?.selectPluginTab(pluginInfo) == true) return true
            return secondChild?.selectPluginTab(pluginInfo) == true
        } else {
            val found = tabs?.tabs?.find {
                (it.component as? PluginTabPanel)?.pluginInfo?.id == pluginInfo.id
            }
            if (found != null) {
                tabs?.select(found, true)
                return true
            }
            return false
        }
    }
}
