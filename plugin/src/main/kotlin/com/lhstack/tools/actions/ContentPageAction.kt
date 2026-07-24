package com.lhstack.tools.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tabs.JBEditorTabsBase
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Point
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import com.lhstack.tools.components.EmptyPanel
import com.lhstack.tools.components.FloatingDialog
import com.lhstack.tools.components.PluginTabPanel
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.catch
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.min


class ContentPageAction(
    windowPanel: SimpleToolWindowPanel,
    private val project: Project,
    private val goToPage: (String) -> Unit,
) :
    AbstractPageAction({ "插件面板" }, Icons.toolIcon(), windowPanel), Disposable, ProjectPluginListener {

    private val leftTabsPane: JBEditorTabsBase
    private val rightTabsPane: JBEditorTabsBase

    private val contentPanel: JPanel

    private val cardLayout = CardLayout()

    private val messageBusConnection: MessageBusConnection

    private val cardView = "view"

    private val cardEmpty = "empty"

    private val splitter = JBSplitter(false)

    private val goToPluginButton: JButton = object : JButton() {
        override fun contains(x: Int, y: Int): Boolean {
            val width = width
            val height = height
            // 计算圆心坐标和半径
            val radius = (min(width.toDouble(), height.toDouble()) / 2).toInt()
            val centerX = width / 2
            val centerY = height / 2
            // 计算点 (x, y) 到圆心的距离
            val dx = x - centerX
            val dy = y - centerY
            // 如果点在圆内，返回 true；否则返回 false
            return (dx * dx + dy * dy) <= radius * radius
        }
    }.apply {
        this.icon = Icons.addIcon()
        this.setContentAreaFilled(false);   // 禁用按钮的背景填充
        this.setBorderPainted(false);       // 去掉边框
        this.setFocusPainted(false);        // 去掉焦点框
        this.setOpaque(false);
        this.preferredSize = Dimension(icon.iconWidth, icon.iconHeight)
        this.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                setCursor(Cursor(Cursor.HAND_CURSOR))
                icon = Icons.addHoverIcon()
            }

            override fun mouseExited(e: MouseEvent?) {
                icon = Icons.addIcon()
                setCursor(Cursor(Cursor.DEFAULT_CURSOR))
            }
        })
        this.addActionListener {
            icon = Icons.addIcon()
            goToPage.invoke("plugin")
        }
    }

    init {
        contentPanel = JPanel(cardLayout)
        leftTabsPane = JBTabsFactory.createEditorTabs(project, this)
        rightTabsPane = JBTabsFactory.createEditorTabs(project, this)
        leftTabsPane.presentation.setTabDraggingEnabled(true)
        rightTabsPane.presentation.setTabDraggingEnabled(true)
        val splitterRefreshListener = object : TabsListener {
            override fun tabRemoved(tabToRemove: TabInfo) {
                refreshSplitterLayout()
            }
        }
        leftTabsPane.addListener(splitterRefreshListener)
        rightTabsPane.addListener(splitterRefreshListener)
        attachTabsPopup(leftTabsPane,true)
        attachTabsPopup(rightTabsPane,false)
        contentPanel.add(splitter, cardView)
        contentPanel.add(EmptyPanel(goToPluginButton, "没有内容,请在插件列表中打开一个插件吧"), cardEmpty)
        refreshSplitterLayout()
        messageBusConnection = project.messageBus.connect()
        messageBusConnection.subscribe(ProjectPluginListener.TOPIC, this)
        Disposer.register(project, this)
    }

    private fun attachTabsPopup(tabsPanel: JBEditorTabsBase,left:Boolean) {
        val tabsPopupGroup = DefaultActionGroup()
        tabsPopupGroup.add(object : AnAction({ "关闭所有标签" }, Icons.closeAllIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if (component is TabLabel) {
                    val tabInfo = component.info
                    if(tabInfo.component is PluginTabPanel){
                        val pluginTabPanel = (tabInfo.component as PluginTabPanel)
                        val pluginTabsPanel = pluginTabPanel.tabsPanel
                        pluginTabsPanel.tabs.forEach { tab ->
                            pluginTabPanel.plugin.catch("插件面板关闭回调") { closePanel(project,(tab.component as PluginTabPanel).pluginPanel) }
                            pluginTabsPanel.removeTab(tab)
                        }
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        tabsPopupGroup.add(object : AnAction({ "关闭其他标签" }, Icons.closeOtherIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if (component is TabLabel) {
                    val tabInfo = component.info
                    if(tabInfo.component is PluginTabPanel){
                        val pluginTabPanel = (tabInfo.component as PluginTabPanel)
                        val pluginTabsPanel = pluginTabPanel.tabsPanel
                        pluginTabsPanel.tabs.forEach { tab ->
                            if(tab.component is PluginTabPanel){
                                val tabPluginTabPanel = (tab.component as PluginTabPanel)
                                if(pluginTabPanel.identity != tabPluginTabPanel.identity){
                                    tabPluginTabPanel.plugin.catch("插件面板关闭回调") { closePanel(project,(tab.component as PluginTabPanel).pluginPanel) }
                                    pluginTabsPanel.removeTab(tab)
                                }
                            }
                        }
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        tabsPopupGroup.add(object : AnAction({ "在新窗口中打开" }, Icons.newTabIcon()) {
            override fun actionPerformed(e: AnActionEvent) {
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if (component is TabLabel) {
                    val tabInfo = component.info
                    tabInfo.isHidden = true
                    val dialog: FloatingDialog = if (tabInfo.component is PluginTabPanel) {
                        FloatingDialog(
                            project,
                            (tabInfo.component as PluginTabPanel).pluginInfo.name,
                            tabInfo.component
                        )
                    } else {
                        FloatingDialog(project, "新窗口", tabInfo.component)
                    }
                    //关闭回调
                    Disposer.register(dialog.disposable) {
                        tabInfo.isHidden = false
                    }
                    dialog.show()
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        tabsPopupGroup.add(object : AnAction({ "复制" }, Icons.closeOtherIcon()) {

            override fun update(e: AnActionEvent) {
                super.update(e)
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if (component is TabLabel) {
                    val tabInfo = component.info
                    e.presentation.description =
                        "需要插件支持多开功能,新版本插件新增supportMultiOpens函数,用于支持多开功能"
                    if (tabInfo.component is PluginTabPanel) {
                        val pluginTabPanel = tabInfo.component as PluginTabPanel
                        if (!pluginTabPanel.plugin.supportMultiOpens()) {
                            e.presentation.isEnabled = false
                        }
                    }

                }
            }

            override fun actionPerformed(e: AnActionEvent) {
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if (component is TabLabel) {
                    val tabInfo = component.info
                    if (tabInfo.component is PluginTabPanel) {
                        val pluginTabPanel = tabInfo.component as PluginTabPanel
                        openPanel(pluginTabPanel.pluginInfo, pluginTabPanel.plugin,pluginTabPanel.tabsPanel)
                    }
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        tabsPopupGroup.add(object : AnAction({ if(left){"移动到右侧"} else {"移动到左侧"} }, if(left){Icons.moveright()} else {Icons.moveleft()}) {
            override fun update(e: AnActionEvent) {
                super.update(e)
                if(leftTabsPane.tabCount == 1 && rightTabsPane.tabCount == 0){
                    e.presentation.isEnabledAndVisible = false
                }
                if(leftTabsPane.tabCount == 0 && rightTabsPane.tabCount == 1){
                    e.presentation.isEnabledAndVisible = false
                }
            }
            override fun actionPerformed(e: AnActionEvent) {
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if (component is TabLabel) {
                    val tabInfo = component.info
                    val pluginTabPanel = tabInfo.component as PluginTabPanel
                    pluginTabPanel.tabsPanel.removeTab(tabInfo)
                    pluginTabPanel.tabsPanel = if (left) rightTabsPane else leftTabsPane
                    pluginTabPanel.tabsPanel.addTab(tabInfo)
                    pluginTabPanel.tabsPanel.select(tabInfo, true)
                    refreshSplitterLayout()
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        tabsPopupGroup.add(object : AnAction({ if(left){"复制到右侧"} else {"复制到左侧"} }, Icons.movecopy()) {
            override fun update(e: AnActionEvent) {
                super.update(e)
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if (component is TabLabel) {
                    val tabInfo = component.info
                    e.presentation.description =
                        "需要插件支持多开功能,新版本插件新增supportMultiOpens函数,用于支持多开功能"
                    if (tabInfo.component is PluginTabPanel) {
                        val pluginTabPanel = tabInfo.component as PluginTabPanel
                        if (!pluginTabPanel.plugin.supportMultiOpens()) {
                            e.presentation.isEnabled = false
                        }
                    }

                }
            }
            override fun actionPerformed(e: AnActionEvent) {
                val component = e.dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT)
                if (component is TabLabel) {
                    val tabInfo = component.info
                    val pluginTabPanel = tabInfo.component as PluginTabPanel
                    val target = if (left) rightTabsPane else leftTabsPane
                    openPanel(pluginTabPanel.pluginInfo, pluginTabPanel.plugin, target)
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })


        tabsPanel.setPopupGroup(tabsPopupGroup, "ContentPage@Tabs", true)
    }

    /**
     * 统一收敛分屏布局状态：根据左右两侧标签数量决定挂载哪一侧组件、
     * 是否显示分隔条，以及展示内容页还是空页。所有增删/移动/复制操作
     * 在完成标签变更后调用此方法，避免出现"状态判断早于标签变更"的顺序缺陷。
     */
    private fun refreshSplitterLayout() {
        val leftComponent = leftTabsPane.component.takeIf { leftTabsPane.tabCount > 0 }
        val rightComponent = rightTabsPane.component.takeIf { rightTabsPane.tabCount > 0 }
        if (splitter.firstComponent !== leftComponent) {
            splitter.firstComponent = leftComponent
        }
        if (splitter.secondComponent !== rightComponent) {
            splitter.secondComponent = rightComponent
        }
        splitter.divider.isVisible = leftComponent != null && rightComponent != null
        cardLayout.show(contentPanel, if (leftComponent == null && rightComponent == null) cardEmpty else cardView)
    }

    /**
     * 跨分屏拖拽委托：借助平台 DragHelper 在标签被拖出源分屏边界时触发的回调，
     * 实现"拖出即从源分屏移除、随鼠标显示幽灵标签、落到目标分屏按鼠标位置插入到正确顺序"的连贯视觉。
     * 幽灵标签绘制在窗口的 GlassPane 上，落点索引依据目标分屏各标签 TabLabel 的边界与鼠标位置计算。
     */
    private inner class CrossPaneDragOutDelegate : TabInfo.DragOutDelegate {
        private var sourcePane: JBEditorTabsBase? = null
        private var ghost: DragGhost? = null

        /** 当前落点占位标签及其所在分屏；随鼠标移动实时更新，松手时占位位置即真实插入位置。 */
        private var placeholder: TabInfo? = null
        private var placeholderPane: JBEditorTabsBase? = null

        override fun dragOutStarted(mouseEvent: MouseEvent, info: TabInfo) {
            val panel = info.component as? PluginTabPanel ?: return
            sourcePane = panel.tabsPanel
            ghost = DragGhost(panel.tabsPanel, info)
            panel.tabsPanel.removeTab(info)
            refreshSplitterLayout()
            ghost?.moveTo(mouseEvent)
            updatePlaceholder(mouseEvent, info)
        }

        override fun processDragOut(mouseEvent: MouseEvent, info: TabInfo) {
            ghost?.moveTo(mouseEvent)
            updatePlaceholder(mouseEvent, info)
        }

        override fun dragOutFinished(mouseEvent: MouseEvent, info: TabInfo) {
            val source = sourcePane ?: return
            sourcePane = null
            ghost?.dispose()
            ghost = null
            val target = placeholderPane ?: paneAtScreenPoint(mouseEvent) ?: source
            val index = placeholderIndex(target) ?: dropIndexFor(target, mouseEvent)
            removePlaceholder()
            addBackTab(target, info, index)
        }

        override fun dragOutCancelled(info: TabInfo) {
            val source = sourcePane ?: return
            sourcePane = null
            ghost?.dispose()
            ghost = null
            removePlaceholder()
            addBackTab(source, info, -1)
        }

        /**
         * 根据鼠标位置刷新落点占位标签：定位目标分屏与落点索引，仅当分屏或索引变化时才
         * 重建占位标签，避免频繁增删导致闪烁。鼠标不在任何分屏上时移除占位。
         */
        private fun updatePlaceholder(mouseEvent: MouseEvent, info: TabInfo) {
            val target = paneAtScreenPoint(mouseEvent)
            if (target == null) {
                removePlaceholder()
                return
            }
            // 鼠标此刻正悬停在已有占位标签上：落点即占位当前位置，保持不动直接返回。
            // 否则占位插入推开真实标签后，会与 dropIndexFor（按真实标签计算）反复错位，形成"插入-移除"闪烁环。
            if (placeholderPane === target && isPointerOverPlaceholder(mouseEvent)) return
            val index = dropIndexFor(target, mouseEvent).coerceIn(0, target.tabCount)
            val currentIndex = placeholderIndex(placeholderPane)
            if (placeholderPane === target && currentIndex == index) return
            removePlaceholder()
            val holder = TabInfo(JPanel()).apply {
                setText("▸ " + (info.text ?: ""))
                setIcon(info.icon)
                // 高亮占位标签，与真实标签形成明显区分，让用户清楚当前落点是哪个位置。
                setTabColor(UIUtil.getFocusedFillColor())
            }
            target.addTab(holder, index)
            placeholder = holder
            placeholderPane = target
            refreshSplitterLayout()
        }

        /** 占位标签在其所在分屏中的当前索引；无占位或已失效时返回 null。 */
        private fun placeholderIndex(pane: JBEditorTabsBase?): Int? {
            val holder = placeholder ?: return null
            val owner = pane ?: return null
            val index = owner.tabs.indexOf(holder)
            return index.takeIf { it >= 0 }
        }

        /** 鼠标是否落在当前占位标签的 label 边界内；用于打破"占位推开真实标签导致落点反复横跳"的闪烁环。 */
        private fun isPointerOverPlaceholder(mouseEvent: MouseEvent): Boolean {
            val holder = placeholder ?: return false
            val label = placeholderPane?.getTabLabel(holder) ?: return false
            if (!label.isShowing) return false
            val local = Point(mouseEvent.locationOnScreen)
            SwingUtilities.convertPointFromScreen(local, label)
            return local.x in 0 until label.width && local.y in 0 until label.height
        }

        private fun removePlaceholder() {
            val holder = placeholder ?: return
            placeholderPane?.removeTab(holder)
            placeholder = null
            placeholderPane = null
            refreshSplitterLayout()
        }

        /** 根据鼠标屏幕坐标判断落在哪个分屏；不在任何分屏上时返回 null。 */
        private fun paneAtScreenPoint(mouseEvent: MouseEvent): JBEditorTabsBase? {
            val screenPoint = mouseEvent.locationOnScreen
            return when {
                containsOnScreen(leftTabsPane, screenPoint) -> leftTabsPane
                containsOnScreen(rightTabsPane, screenPoint) -> rightTabsPane
                else -> null
            }
        }

        private fun containsOnScreen(pane: JBEditorTabsBase, screenPoint: Point): Boolean {
            val component = pane.component
            if (!component.isShowing) return false
            val local = Point(screenPoint)
            SwingUtilities.convertPointFromScreen(local, component)
            return component.contains(local)
        }

        /**
         * 计算标签在目标分屏中的插入索引：将鼠标屏幕坐标换算到各标签 TabLabel 的坐标系，
         * 命中某标签时按鼠标是否越过其水平中点决定插到该标签之前或之后；
         * 未命中任何标签时插到末尾。返回 -1 表示交由平台默认追加。
         */
        private fun dropIndexFor(target: JBEditorTabsBase, mouseEvent: MouseEvent): Int {
            val screenPoint = mouseEvent.locationOnScreen
            // 排除当前占位标签，只按真实标签的边界与计数计算落点，避免占位标签自身干扰索引。
            val tabs = target.tabs.filter { it !== placeholder }
            tabs.forEachIndexed { index, tab ->
                val label = target.getTabLabel(tab) ?: return@forEachIndexed
                if (!label.isShowing) return@forEachIndexed
                val local = Point(screenPoint)
                SwingUtilities.convertPointFromScreen(local, label)
                if (local.x in 0 until label.width && local.y in 0 until label.height) {
                    return if (local.x <= label.width / 2) index else index + 1
                }
            }
            return tabs.size
        }

        private fun addBackTab(target: JBEditorTabsBase, info: TabInfo, index: Int) {
            (info.component as? PluginTabPanel)?.tabsPanel = target
            if (index in 0..target.tabCount) {
                target.addTab(info, index)
            } else {
                target.addTab(info)
            }
            target.select(info, true)
            refreshSplitterLayout()
        }
    }

    /**
     * 拖拽幽灵标签：把被拖标签渲染成一张半透明图，绘制在活动窗口 GlassPane 上并跟随鼠标移动。
     * 仅用于视觉反馈，不参与任何数据状态；拖拽结束时统一销毁。
     */
    private inner class DragGhost(sourcePane: JBEditorTabsBase, info: TabInfo) {
        private val text: String = info.text ?: ""
        private val icon: javax.swing.Icon? = info.icon
        private val rootPane: javax.swing.JRootPane? = SwingUtilities.getRootPane(sourcePane.component)
        private val glassPane: JComponent? = rootPane?.glassPane as? JComponent
        private val label = JLabel(text, icon, SwingConstants.LEFT).apply {
            isOpaque = true
            background = UIUtil.getListSelectionBackground(true)
            foreground = UIUtil.getListSelectionForeground(true)
            border = JBUI.Borders.empty(2, 8)
            size = preferredSize
        }
        private var installed = false

        fun moveTo(mouseEvent: MouseEvent) {
            val glass = glassPane ?: return
            if (!installed) {
                glass.add(label)
                glass.isVisible = true
                installed = true
            }
            val point = Point(mouseEvent.locationOnScreen)
            SwingUtilities.convertPointFromScreen(point, glass)
            label.location = Point(point.x + JBUI.scale(8), point.y + JBUI.scale(8))
            glass.repaint()
        }

        fun dispose() {
            val glass = glassPane ?: return
            if (installed) {
                glass.remove(label)
                glass.repaint()
                installed = false
            }
        }
    }

    override fun getPanel(): JComponent {
        return contentPanel
    }

    override fun dispose() {
        messageBusConnection.disconnect()
    }

    override fun closePanel(plugin: IPlugin, pluginInfo: PluginInfo) {
        this.leftTabsPane.tabs.forEach {
            if (it.component is PluginTabPanel) {
                val pluginTabPanel = it.component as PluginTabPanel
                if (pluginTabPanel.pluginInfo.id == pluginInfo.id) {
                    pluginTabPanel.tabsPanel.removeTab(it)
                    plugin.catch("插件面板关闭回调") {
                        closePanel(project,pluginTabPanel.pluginPanel)
                    }
                }
            }
        }

        this.rightTabsPane.tabs.forEach {
            if (it.component is PluginTabPanel) {
                val pluginTabPanel = it.component as PluginTabPanel
                if (pluginTabPanel.pluginInfo.id == pluginInfo.id) {
                    pluginTabPanel.tabsPanel.removeTab(it)
                    plugin.catch("插件面板关闭回调") {
                        closePanel(project,pluginTabPanel.pluginPanel)
                    }
                }
            }
        }
    }

    fun openPanel(pluginInfo: PluginInfo, plugin: IPlugin,tabsPanel: JBEditorTabsBase) {
        if (!plugin.supportMultiOpens()) {
            tabsPanel.tabs.forEach {
                if (it.component is PluginTabPanel) {
                    val pluginTabPanel = it.component as PluginTabPanel
                    if (pluginTabPanel.pluginInfo.id == pluginInfo.id) {
                        tabsPanel.select(it, true)
                        goToPage()
                        return
                    }
                }
            }
        }
        plugin.catch("创建插件面板") {
            val pluginPanel = createPanel(project)
            val pluginTabPanel = PluginTabPanel(pluginInfo, plugin, pluginPanel,tabsPanel,UUID.randomUUID().toString())
            pluginTabPanel.layout = BorderLayout()
            pluginTabPanel.add(pluginPanel, BorderLayout.CENTER)
            val tabInfo = TabInfo(pluginTabPanel)
            tabInfo.setIcon(plugin.pluginTabIcon())
            tabInfo.setText(pluginInfo.name)
            tabInfo.setTooltipText(plugin.pluginDesc())
            tabInfo.setTabLabelActions(DefaultActionGroup(object : AnAction({ "关闭" }, AllIcons.Actions.Close) {

                override fun update(e: AnActionEvent) {
                    super.update(e)
                    e.presentation.icon = AllIcons.Actions.Close
                    e.presentation.hoveredIcon = AllIcons.Actions.CloseHovered
                }

                override fun actionPerformed(e: AnActionEvent) {
                    pluginTabPanel.tabsPanel.removeTab(tabInfo)
                    plugin.catch("插件面板关闭回调") { closePanel(project,pluginPanel) }
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }

            }), "tabActionGroup")

            Optional.ofNullable(plugin.tabPanelActions(project,pluginPanel)).filter { it.isNotEmpty() }.ifPresent {
                tabInfo.setTabPaneActions(DefaultActionGroup(it))
            }

            tabInfo.setDragOutDelegate(CrossPaneDragOutDelegate())
            tabsPanel.addTab(tabInfo)
            tabsPanel.select(tabInfo, true)
            refreshSplitterLayout()
            plugin.catch("插件面板显示回调") { showPanel(project,pluginPanel) }
            goToPage()
        }
    }

    override fun openPanel(pluginInfo: PluginInfo, plugin: IPlugin) {
        if (!plugin.supportMultiOpens()) {
            this.leftTabsPane.tabs.forEach {
                if (it.component is PluginTabPanel) {
                    val pluginTabPanel = it.component as PluginTabPanel
                    if (pluginTabPanel.pluginInfo.id == pluginInfo.id) {
                        leftTabsPane.select(it, true)
                        goToPage()
                        return
                    }
                }
            }
        }
        plugin.catch("创建插件面板") {
            val pluginPanel = createPanel(project)
            val pluginTabPanel = PluginTabPanel(pluginInfo, plugin, pluginPanel,leftTabsPane,UUID.randomUUID().toString())
            pluginTabPanel.layout = BorderLayout()
            pluginTabPanel.add(pluginPanel, BorderLayout.CENTER)
            val tabInfo = TabInfo(pluginTabPanel)
            tabInfo.setIcon(plugin.pluginTabIcon())
            tabInfo.setText(pluginInfo.name)
            tabInfo.setTooltipText(plugin.pluginDesc())
            tabInfo.setTabLabelActions(DefaultActionGroup(object : AnAction({ "关闭" }, AllIcons.Actions.Close) {

                override fun update(e: AnActionEvent) {
                    super.update(e)
                    e.presentation.icon = AllIcons.Actions.Close
                    e.presentation.hoveredIcon = AllIcons.Actions.CloseHovered
                }

                override fun actionPerformed(e: AnActionEvent) {
                    pluginTabPanel.tabsPanel.removeTab(tabInfo)
                    plugin.catch("插件面板关闭回调") { closePanel(project,pluginPanel) }
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }

            }), "tabActionGroup")

            Optional.ofNullable(plugin.tabPanelActions(project,pluginPanel)).filter { it.isNotEmpty() }.ifPresent {
                tabInfo.setTabPaneActions(DefaultActionGroup(it))
            }

            tabInfo.setDragOutDelegate(CrossPaneDragOutDelegate())
            leftTabsPane.addTab(tabInfo)
            leftTabsPane.select(tabInfo, true)
            refreshSplitterLayout()
            plugin.catch("插件面板显示回调") { showPanel(project,pluginPanel) }
            goToPage()
        }
    }


}