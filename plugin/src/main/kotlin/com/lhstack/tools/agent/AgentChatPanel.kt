package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.jetbrains.rd.generator.nova.array
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.plugins.pluginState
import org.jdesktop.swingx.VerticalLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

class AgentChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val messageContainer = JPanel(VerticalLayout(8))
    private val chatScroll = JBScrollPane(messageContainer)
    private val inputArea = JBTextArea(3, 0)
    private val actionToolbars = mutableListOf<ActionToolbar>()
    private val actionEnabledState = mutableMapOf<AnAction, Boolean>()
    private val quickClearAction = createAction("清空当前会话", Icons.closeAllIcon()) {
        currentSession?.let { clearSession(it) }
    }
    private val sessionManageAction = createAction("会话管理", Icons.libraryIcon()) { openSessionManager() }
    private val modelManageAction = createAction("模型管理", Icons.toolIcon()) { openModelManager() }
    private val mcpManageAction = createAction("MCP 配置", Icons.settingIcon()) { openMcpManager() }
    private val sendAction = createAction(
        "发送",
        Icons.runIcon(),
        enabledProvider = { !sending.get() && inputArea.isEnabled }
    ) { sendMessage() }
    private val stopAction = createAction(
        "停止",
        Icons.stopIcon(),
        enabledProvider = { sending.get() }
    ) { cancelCurrentRequest() }
    private val statusLabel = JLabel()
    private val inputHintLabel = JLabel("Shift+Enter 发送, Enter 换行")
    private val sessionModel = DefaultComboBoxModel<ChatSession>()
    private val sessionSelector = ComboBox<ChatSession>()
    private val modelSelector = ComboBox<String>()
    private val client = AgentClient()
    private val sending = AtomicBoolean(false)
    private val requestCounter = AtomicInteger(0)
    @Volatile private var activeRequestId = 0
    private var cancelToken: AgentClient.CancelToken? = null
    private var currentSession: ChatSession? = null
    private var updatingSessionSelection = false
    private var updatingModelSelection = false

    private var assistantBlock: MessageBlock? = null
    private var reasoningBlock: MessageBlock? = null
    private var suppressToolMarkup = false

    init {
        setupChatContainer()
        setupInputArea()
        setupSessionSelector()
        setupModelSelector()
        setActionEnabled(stopAction, false)
        val root = JPanel(BorderLayout())
        root.add(buildTopBar(), BorderLayout.NORTH)
        root.add(buildChatContainer(), BorderLayout.CENTER)
        root.add(buildInputBar(), BorderLayout.SOUTH)
        setContent(root)
        initSessions()
        updateStatus()
        updateToolbars()
    }

    private fun setupChatContainer() {
        messageContainer.background = UIUtil.getPanelBackground()
        messageContainer.isOpaque = true
        chatScroll.border = JBUI.Borders.customLine(JBColor.border(), 1)
        chatScroll.viewport.background = UIUtil.getPanelBackground()
        chatScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        chatScroll.verticalScrollBar.unitIncrement = 16
    }

    private fun setupInputArea() {
        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.margin = JBUI.insets(6)
        inputArea.background = UIUtil.getTextFieldBackground()
        inputArea.isOpaque = true
        inputArea.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "sendMessage"
        )
        inputArea.actionMap.put("sendMessage", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                sendMessage()
            }
        })
    }

    private fun setupSessionSelector() {
        sessionSelector.model = sessionModel
        sessionSelector.maximumRowCount = 8
        sessionSelector.isEditable = false
        sessionSelector.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val text = (value as? ChatSession)?.title ?: value?.toString().orEmpty()
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        sessionSelector.addActionListener {
            if (updatingSessionSelection || sending.get()) {
                return@addActionListener
            }
            val selected = sessionSelector.selectedItem as? ChatSession ?: return@addActionListener
            switchSession(selected)
        }
    }

    private fun setupModelSelector() {
        modelSelector.isEditable = true
        refreshModelSelector(project.pluginState().agentModel)
        modelSelector.addItemListener { event ->
            if (updatingModelSelection || event.stateChange != ItemEvent.SELECTED) {
                return@addItemListener
            }
            updateCurrentModel(resolveSelectedModel())
        }
        modelSelector.addActionListener {
            if (updatingModelSelection) {
                return@addActionListener
            }
            updateCurrentModel(resolveSelectedModel())
        }
    }

    private fun createAction(
        description: String,
        icon: javax.swing.Icon,
        enabledProvider: (() -> Boolean)? = null,
        action: () -> Unit
    ): AnAction {
        return object : AnAction({ description }, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                action()
            }

            override fun update(e: AnActionEvent) {
                val enabled = (enabledProvider?.invoke() ?: true) && isActionEnabled(this)
                e.presentation.isEnabled = enabled
            }
        }
    }

    private fun setActionEnabled(action: AnAction, enabled: Boolean) {
        actionEnabledState[action] = enabled
    }

    private fun isActionEnabled(action: AnAction): Boolean {
        return actionEnabledState[action] != false
    }

    private fun updateToolbars() {
        actionToolbars.forEach { it.updateActionsAsync() }
    }

    private fun createToolbar(id: String, group: DefaultActionGroup, horizontal: Boolean, target: JComponent): JComponent {
        val toolbar = ActionManager.getInstance().createActionToolbar(id, group, horizontal)
        toolbar.targetComponent = target
        actionToolbars.add(toolbar)
        return toolbar.component
    }

    private fun resolveSelectedModel(): String {
        val selected = modelSelector.selectedItem?.toString()?.trim().orEmpty()
        if (selected.isNotEmpty()) {
            return selected
        }
        return modelSelector.editor.item?.toString()?.trim().orEmpty()
    }

    private fun initSessions() {
        val stored = project.pluginState().agentSessions
        if (stored.isNotEmpty()) {
            stored.forEach { state ->
                sessionModel.addElement(toSession(state))
            }
            val activeId = project.pluginState().agentActiveSessionId
            val active = (0 until sessionModel.size)
                .map { sessionModel.getElementAt(it) }
                .firstOrNull { it.id == activeId }
                ?: sessionModel.getElementAt(0)
            switchSession(active)
        } else {
            val session = createSession()
            switchSession(session)
        }
    }

    private fun createSession(): ChatSession {
        val modelName = modelSelector.editor.item?.toString()?.trim().orEmpty()
        val resolvedModel = modelName.ifBlank {
            project.pluginState().agentModel.trim().ifBlank { "gpt-4o-mini" }
        }
        val state = AgentSessionState().apply {
            id = UUID.randomUUID().toString()
            title = "新会话"
            autoTitle = true
            model = resolvedModel
        }
        val session = toSession(state)
        resetMessages(session)
        project.pluginState().agentSessions.add(state)
        sessionModel.addElement(session)
        return session
    }

    private fun toSession(state: AgentSessionState): ChatSession {
        if (state.id.isBlank()) {
            state.id = UUID.randomUUID().toString()
        }
        val resolvedModel = state.model.trim().ifBlank {
            project.pluginState().agentModel.trim().ifBlank { "gpt-4o-mini" }
        }
        state.model = resolvedModel
        val messages = mutableListOf<JsonObject>()
        state.messages.forEach { raw ->
            try {
                messages.add(JsonParser.parseString(raw).asJsonObject)
            } catch (_: Throwable) {
                // ignore malformed persisted message
            }
        }
        val renders = state.renders.map { renderState ->
            RenderItem(
                role = renderState.role,
                content = renderState.content,
                collapsible = renderState.collapsible,
                collapsedByDefault = renderState.collapsedByDefault,
                state = renderState
            )
        }.toMutableList()
        return ChatSession(
            id = state.id,
            title = state.title.ifBlank { "新会话" },
            autoTitle = state.autoTitle,
            model = resolvedModel,
            messages = messages,
            renders = renders,
            state = state
        )
    }

    private fun switchSession(session: ChatSession) {
        currentSession = session
        project.pluginState().agentActiveSessionId = session.id
        updatingSessionSelection = true
        sessionSelector.selectedItem = session
        updatingSessionSelection = false
        ensureModelExists(session.model)
        refreshModelSelector(session.model)
        renderSession(session)
        updateStatus()
    }

    private fun renderSession(session: ChatSession) {
        messageContainer.removeAll()
        messageContainer.revalidate()
        messageContainer.repaint()
        assistantBlock = null
        reasoningBlock = null
        session.renders.forEach { item ->
            appendRenderedItem(item)
        }
        scrollToBottom()
    }

    private fun appendRenderedItem(item: RenderItem) {
        val color = if (item.role == "推理") JBColor(0x6A6A6A, 0x9A9A9A) else UIUtil.getLabelForeground()
        val block = createMessageBlock(item.role, color, item.collapsible, item.collapsedByDefault, item)
        block.textArea.text = item.content
        addMessageBlock(block)
    }

    private fun addRenderItem(item: RenderItem, before: RenderItem? = null) {
        val session = currentSession ?: return
        val stateItem = item.state ?: AgentRenderState().apply {
            role = item.role
            content = item.content
            collapsible = item.collapsible
            collapsedByDefault = item.collapsedByDefault
        }.also { item.state = it }
        if (before != null) {
            val index = session.renders.indexOf(before)
            if (index >= 0) {
                session.renders.add(index, item)
                session.state.renders.add(index, stateItem)
                return
            }
        }
        session.renders.add(item)
        session.state.renders.add(stateItem)
    }

    private fun syncSessionMessages(session: ChatSession) {
        session.state.messages.clear()
        session.messages.forEach { message ->
            session.state.messages.add(message.toString())
        }
    }

    private fun updateCurrentModel(model: String) {
        val session = currentSession ?: return
        val resolved = model.trim().ifBlank { "gpt-4o-mini" }
        session.model = resolved
        session.state.model = resolved
        project.pluginState().agentModel = resolved
        ensureModelExists(resolved)
        refreshModelSelector(resolved)
        updateStatus()
    }

    private fun refreshModelSelector(selected: String?) {
        updatingModelSelection = true
        modelSelector.removeAllItems()
        val models = ensureModelList()
        val candidate = selected?.trim().orEmpty()
        if (candidate.isNotEmpty() && !models.contains(candidate)) {
            models.add(candidate)
        }
        models.forEach { modelSelector.addItem(it) }
        val resolved = selected?.trim().orEmpty().ifBlank {
            currentSession?.model?.trim().orEmpty().ifBlank { models.first() }
        }
        modelSelector.selectedItem = resolved
        modelSelector.editor.item = resolved
        updatingModelSelection = false
    }

    private fun ensureModelList(): MutableList<String> {
        val models = project.pluginState().agentModels
        if (models.isEmpty()) {
            models.add("gpt-4o-mini")
        }
        return models
    }

    private fun ensureModelExists(model: String) {
        val trimmed = model.trim().ifBlank { return }
        val models = ensureModelList()
        if (!models.contains(trimmed)) {
            models.add(trimmed)
        }
    }

    private fun updateSessionTitle(text: String) {
        val session = currentSession ?: return
        if (!session.autoTitle) {
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return
        }
        session.title = trimmed.take(20)
        session.autoTitle = false
        session.state.title = session.title
        session.state.autoTitle = session.autoTitle
        sessionSelector.repaint()
    }

    private fun renameSession(session: ChatSession) {
        val input = Messages.showInputDialog(
            this,
            "请输入会话名称",
            "重命名会话",
            null,
            session.title,
            null
        ) ?: return
        val name = input.trim()
        if (name.isEmpty()) {
            return
        }
        session.title = name
        session.autoTitle = false
        session.state.title = name
        session.state.autoTitle = false
        sessionSelector.repaint()
    }

    private fun deleteSession(session: ChatSession) {
        val message = if (sending.get() && session == currentSession) {
            "当前会话正在回复，是否终止并删除？"
        } else {
            "确定要删除会话吗？"
        }
        val confirmed = Messages.showYesNoDialog(
            this,
            message,
            "删除会话",
            null
        )
        if (confirmed != Messages.YES) {
            return
        }
        if (sending.get() && session == currentSession) {
            cancelCurrentRequest()
        }
        val index = sessionModel.getIndexOf(session)
        sessionModel.removeElement(session)
        project.pluginState().agentSessions.remove(session.state)
        if (sessionModel.size <= 0) {
            switchSession(createSession())
            return
        }
        if (session == currentSession) {
            val nextIndex = if (index <= 0) 0 else minOf(index, sessionModel.size - 1)
            switchSession(sessionModel.getElementAt(nextIndex))
        } else {
            sessionSelector.repaint()
        }
    }

    private fun clearSession(session: ChatSession) {
        val message = if (sending.get() && session == currentSession) {
            "当前会话正在回复，是否终止并清空？"
        } else {
            "确定要清空会话吗？"
        }
        val confirmed = Messages.showYesNoDialog(
            this,
            message,
            "清空会话",
            null
        )
        if (confirmed != Messages.YES) {
            return
        }
        if (sending.get() && session == currentSession) {
            cancelCurrentRequest()
        }
        session.renders.clear()
        session.state.renders.clear()
        resetMessages(session)
        if (session == currentSession) {
            renderSession(session)
        }
    }

    private fun openSessionManager() {
        val dialog = JDialog(SwingUtilities.getWindowAncestor(this), "会话管理", Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        val listModel = DefaultListModel<ChatSession>()
        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val text = (value as? ChatSession)?.title ?: value?.toString().orEmpty()
                    return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
                }
            }
        }
        val newButton = JButton("新建")
        val renameButton = JButton("重命名")
        val deleteButton = JButton("删除")
        val closeButton = JButton("关闭")
        val refreshList = {
            listModel.clear()
            for (i in 0 until sessionModel.size) {
                listModel.addElement(sessionModel.getElementAt(i))
            }
            currentSession?.let { list.setSelectedValue(it, true) }
        }
        refreshList()

        fun updateDeleteState() {
            val selected = list.selectedValue
            deleteButton.isEnabled = !(sending.get() && selected == currentSession)
        }

        newButton.addActionListener {
            if (sending.get()) {
                return@addActionListener
            }
            val session = createSession()
            switchSession(session)
            refreshList()
            list.setSelectedValue(session, true)
        }
        renameButton.addActionListener {
            val selected = list.selectedValue ?: return@addActionListener
            renameSession(selected)
            list.repaint()
        }
        deleteButton.addActionListener {
            val selected = list.selectedValue ?: return@addActionListener
            deleteSession(selected)
            refreshList()
            updateDeleteState()
        }
        closeButton.addActionListener { dialog.dispose() }
        list.addListSelectionListener { updateDeleteState() }
        newButton.isEnabled = !sending.get()
        updateDeleteState()

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
            add(newButton)
            add(renameButton)
            add(deleteButton)
            add(closeButton)
        }
        val content = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }
        dialog.contentPane = content
        dialog.setSize(420, 320)
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    private fun openModelManager() {
        val dialog = JDialog(SwingUtilities.getWindowAncestor(this), "模型管理", Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        val listModel = DefaultListModel<String>()
        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }
        val addButton = JButton("新增")
        val renameButton = JButton("改名")
        val deleteButton = JButton("删除")
        val closeButton = JButton("关闭")
        val refreshList = {
            listModel.clear()
            ensureModelList().forEach { listModel.addElement(it) }
            currentSession?.model?.let { list.setSelectedValue(it, true) }
        }
        refreshList()

        addButton.addActionListener {
            val input = Messages.showInputDialog(this, "请输入模型名称", "新增模型", null) ?: return@addActionListener
            val name = input.trim()
            if (name.isEmpty()) {
                return@addActionListener
            }
            ensureModelExists(name)
            updateCurrentModel(name)
            refreshList()
            list.setSelectedValue(name, true)
        }
        renameButton.addActionListener {
            val current = list.selectedValue ?: return@addActionListener
            val input = Messages.showInputDialog(this, "请输入新的模型名称", "修改模型", null, current, null)
                ?: return@addActionListener
            val name = input.trim()
            if (name.isEmpty() || name == current) {
                return@addActionListener
            }
            val models = ensureModelList()
            models.remove(current)
            if (!models.contains(name)) {
                models.add(name)
            }
            updateSessionsModelName(current, name)
            if (currentSession?.model == current) {
                updateCurrentModel(name)
            } else {
                refreshModelSelector(currentSession?.model)
                updateStatus()
            }
            refreshList()
            list.setSelectedValue(name, true)
        }
        deleteButton.addActionListener {
            val current = list.selectedValue ?: return@addActionListener
            val confirmed = Messages.showYesNoDialog(this, "确定要删除模型 \"$current\" 吗？", "删除模型", null)
            if (confirmed != Messages.YES) {
                return@addActionListener
            }
            val models = ensureModelList()
            models.remove(current)
            if (models.isEmpty()) {
                models.add("gpt-4o-mini")
            }
            val fallback = models.first()
            updateSessionsModelName(current, fallback)
            if (currentSession?.model == current) {
                updateCurrentModel(fallback)
            } else {
                refreshModelSelector(currentSession?.model)
                updateStatus()
            }
            refreshList()
            list.setSelectedValue(fallback, true)
        }
        closeButton.addActionListener { dialog.dispose() }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
            add(addButton)
            add(renameButton)
            add(deleteButton)
            add(closeButton)
        }
        val content = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }
        dialog.contentPane = content
        dialog.setSize(360, 300)
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    private fun openMcpManager() {
        val dialog = object: DialogWrapper(project,false){
            val panel = McpConfigPanel(project) { text ->
                if (text.isNotBlank()) {
                    inputArea.append(if (inputArea.text.isBlank()) text else "\n$text")
                    inputArea.requestFocusInWindow()
                }
            }
            init {
                this.title = "MCP 配置"
                this.setSize(720,520)
                Disposer.register(this.disposable){
                    panel.dispose()
                }
                this.init()
            }
            override fun createCenterPanel(): JComponent = panel.component
            override fun createActions(): Array<out Action?> {
                return arrayOf()
            }
        }
        dialog.showAndGet()
    }

    private fun updateSessionsModelName(oldName: String, newName: String) {
        for (i in 0 until sessionModel.size) {
            val session = sessionModel.getElementAt(i)
            if (session.model == oldName) {
                session.model = newName
                session.state.model = newName
            }
        }
    }

    private fun beginRequestUi() {
        setActionEnabled(sendAction, false)
        setActionEnabled(stopAction, true)
        sessionSelector.isEnabled = false
        modelSelector.isEnabled = false
        setActionEnabled(modelManageAction, false)
        setActionEnabled(mcpManageAction, false)
        setInputEnabled(false)
        updateToolbars()
    }

    private fun finishRequestUi() {
        setActionEnabled(sendAction, true)
        setActionEnabled(stopAction, false)
        sessionSelector.isEnabled = true
        modelSelector.isEnabled = true
        setActionEnabled(modelManageAction, true)
        setActionEnabled(mcpManageAction, true)
        setInputEnabled(true)
        cancelToken = null
        sending.set(false)
        updateToolbars()
    }

    private fun cancelCurrentRequest() {
        if (!sending.get()) {
            return
        }
        cancelToken?.cancel()
        activeRequestId = requestCounter.incrementAndGet()
        closeAssistantBlock()
        closeReasoningBlock()
        appendMessage("系统", "已终止当前请求", collapsible = false, collapsedByDefault = false)
        finishRequestUi()
    }

    private fun isActiveRequest(requestId: Int, token: AgentClient.CancelToken?): Boolean {
        return requestId == activeRequestId && token?.isCancelled() != true
    }

    private fun buildTopBar(): JComponent {
        val sessionPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JLabel("会话: "))
            add(sessionSelector)
            val group = DefaultActionGroup().apply {
                add(quickClearAction)
                add(sessionManageAction)
            }
            add(createToolbar("AgentSessionToolbar", group, true, this))
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 0, 8)
            add(statusLabel, BorderLayout.CENTER)
            add(sessionPanel, BorderLayout.EAST)
        }
    }

    private fun buildChatContainer(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 6, 8)
            add(chatScroll, BorderLayout.CENTER)
        }
    }

    private fun buildInputBar(): JComponent {
        val inputScroll = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.customLine(JBColor(0x4B90FF, 0x4B90FF), 1)
            viewport.background = UIUtil.getTextFieldBackground()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        inputHintLabel.foreground = JBColor.GRAY
        inputHintLabel.horizontalAlignment = SwingConstants.LEFT
        val modelPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            add(JLabel("模型: "))
            add(Box.createHorizontalStrut(6))
            add(modelSelector)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(inputHintLabel, BorderLayout.WEST)
        }
        val actionPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(modelPanel, BorderLayout.NORTH)
            val sendGroup = DefaultActionGroup().apply {
                add(modelManageAction)
                add(mcpManageAction)
                addSeparator()
                add(sendAction)
                add(stopAction)
            }
            val sendToolbar = createToolbar("AgentSendToolbar", sendGroup, true, this)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                this.add(sendToolbar)
            }, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            add(header, BorderLayout.NORTH)
            add(inputScroll, BorderLayout.CENTER)
            add(actionPanel, BorderLayout.EAST)
        }
    }

    private fun sendMessage() {
        if (sending.get() || !isActionEnabled(sendAction) || !inputArea.isEnabled) {
            return
        }
        val session = currentSession ?: return
        val text = inputArea.text.trim()
        if (text.isEmpty()) {
            return
        }
        val apiKey = project.pluginState().agentOpenApiKey.trim()
        if (apiKey.isBlank()) {
            project.errorNotify("智能体", "请先在设置中配置 OpenAPI Key")
            return
        }
        if (!sending.compareAndSet(false, true)) {
            return
        }
        val baseUrl = project.pluginState().agentOpenApiBaseUrl.trim()
        val model = session.model.trim().ifBlank {
            project.pluginState().agentModel.trim().ifBlank { "gpt-4o-mini" }
        }
        updateCurrentModel(model)
        val maxToolIterations = project.pluginState().agentMaxToolIterations.takeIf { it > 0 } ?: 5
        val toolTimeoutMs = project.pluginState().agentToolTimeoutMs.takeIf { it > 0 } ?: 120_000
        val requestId = requestCounter.incrementAndGet()
        activeRequestId = requestId
        val token = AgentClient.CancelToken()
        cancelToken = token
        suppressToolMarkup = false
        inputArea.text = ""
        appendMessage("用户", text, collapsible = false, collapsedByDefault = false)
        updateSessionTitle(text)
        beginRequestUi()
        updateStatus()

        val userMessage = JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", text)
        }
        session.messages.add(userMessage)
        syncSessionMessages(session)

        ApplicationManager.getApplication().executeOnPooledThread {
            val toolRegistry = AgentToolRegistry.build(project)
            val assistantStarted = AtomicBoolean(false)
            val reasoningStarted = AtomicBoolean(false)
            val reasoningClosed = AtomicBoolean(false)
            val streamedContent = AtomicBoolean(false)
            var toolStreamingUsed = false
            val result = client.complete(
                session.messages,
                toolRegistry,
                apiKey,
                baseUrl,
                model,
                onDelta = { delta ->
                    streamedContent.set(true)
                    ApplicationManager.getApplication().invokeLater {
                        if (!isActiveRequest(requestId, token)) {
                            return@invokeLater
                        }
                        if (reasoningStarted.get() && reasoningClosed.compareAndSet(false, true)) {
                            closeReasoningBlock()
                        }
                        val filtered = filterAssistantDelta(delta)
                        if (filtered.isBlank()) {
                            return@invokeLater
                        }
                        if (assistantStarted.compareAndSet(false, true)) {
                            val item = RenderItem("助手", "", collapsible = false, collapsedByDefault = false)
                            addRenderItem(item)
                            assistantBlock = createMessageBlock("助手", UIUtil.getLabelForeground(), false, false, item)
                            addMessageBlock(assistantBlock!!)
                        }
                        appendToBlock(assistantBlock, filtered)
                    }
                },
                onReasoningDelta = { delta ->
                    streamedContent.set(true)
                    ApplicationManager.getApplication().invokeLater {
                        if (!isActiveRequest(requestId, token)) {
                            return@invokeLater
                        }
                        if (reasoningStarted.compareAndSet(false, true)) {
                            val item = RenderItem("推理", "", collapsible = true, collapsedByDefault = false)
                            addRenderItem(item)
                            reasoningBlock = createMessageBlock("推理", JBColor(0x6A6A6A, 0x9A9A9A), true, false, item)
                            addMessageBlock(reasoningBlock!!)
                        }
                        appendToBlock(reasoningBlock, delta)
                    }
                },
                onToolCall = { event ->
                    toolStreamingUsed = true
                    ApplicationManager.getApplication().invokeLater {
                        if (!isActiveRequest(requestId, token)) {
                            return@invokeLater
                        }
                        if (reasoningStarted.get() && reasoningClosed.compareAndSet(false, true)) {
                            closeReasoningBlock()
                        }
                        if (event.done) {
                            appendToolMessage(
                                "工具调用",
                                "name=${event.name}\narguments=${truncate(event.arguments)}",
                                collapsible = true,
                                collapsedByDefault = true
                            )
                        }
                    }
                },
                onToolResult = { toolLog ->
                    toolStreamingUsed = true
                    ApplicationManager.getApplication().invokeLater {
                        if (!isActiveRequest(requestId, token)) {
                            return@invokeLater
                        }
                        appendToolMessage(
                            "工具结果",
                            "name=${toolLog.name}\nresult=${truncate(toolLog.result)}",
                            collapsible = true,
                            collapsedByDefault = true
                        )
                    }
                },
                maxToolIterations = maxToolIterations,
                toolTimeoutMs = toolTimeoutMs.toLong(),
                cancelToken = token
            )
            ApplicationManager.getApplication().invokeLater {
                if (!isActiveRequest(requestId, token)) {
                    return@invokeLater
                }
                if (assistantStarted.get()) {
                    closeAssistantBlock()
                }
                if (reasoningStarted.get() && reasoningClosed.compareAndSet(false, true)) {
                    closeReasoningBlock()
                }
                if (!toolStreamingUsed) {
                    result.toolCalls.forEach { toolCall ->
                        appendMessage(
                            "工具",
                            "name=${toolCall.name}\narguments=${truncate(toolCall.arguments)}\nresult=${truncate(toolCall.result)}",
                            collapsible = true,
                            collapsedByDefault = true
                        )
                    }
                }
                if (result.errorMessage != null) {
                    appendMessage("错误", result.errorMessage, collapsible = false, collapsedByDefault = false)
                } else if (!assistantStarted.get() && !streamedContent.get()) {
                    val content = result.assistantContent.orEmpty()
                    if (content.isNotBlank()) {
                        appendMessage("助手", content, collapsible = false, collapsedByDefault = false)
                    }
                }
                syncSessionMessages(session)
                finishRequestUi()
            }
        }
    }

    private fun appendMessage(role: String, content: String, collapsible: Boolean, collapsedByDefault: Boolean) {
        val color = if (role == "推理") JBColor(0x6A6A6A, 0x9A9A9A) else UIUtil.getLabelForeground()
        val item = RenderItem(role, content, collapsible, collapsedByDefault)
        addRenderItem(item)
        val block = createMessageBlock(role, color, collapsible, collapsedByDefault, item)
        block.textArea.text = content
        addMessageBlock(block)
    }

    private fun appendMessageBeforeAssistant(
        role: String,
        content: String,
        collapsible: Boolean,
        collapsedByDefault: Boolean
    ) {
        val color = if (role == "推理") JBColor(0x6A6A6A, 0x9A9A9A) else UIUtil.getLabelForeground()
        val item = RenderItem(role, content, collapsible, collapsedByDefault)
        addRenderItem(item, assistantBlock?.renderItem)
        val block = createMessageBlock(role, color, collapsible, collapsedByDefault, item)
        block.textArea.text = content
        addMessageBlock(block, assistantBlock)
    }

    private fun appendToolMessage(
        role: String,
        content: String,
        collapsible: Boolean,
        collapsedByDefault: Boolean
    ) {
        if (assistantBlock != null) {
            appendMessageBeforeAssistant(role, content, collapsible, collapsedByDefault)
        } else {
            appendMessage(role, content, collapsible, collapsedByDefault)
        }
    }

    private fun appendToBlock(block: MessageBlock?, text: String) {
        block ?: return
        block.textArea.append(text)
        block.renderItem?.let {
            it.content += text
            it.state?.content = it.content
        }
        scrollToBottom()
    }

    private fun closeAssistantBlock() {
        assistantBlock = null
        scrollToBottom()
    }

    private fun closeReasoningBlock() {
        reasoningBlock = null
        scrollToBottom()
    }

    private fun addMessageBlock(block: MessageBlock, before: MessageBlock? = null) {
        if (before != null) {
            val index = messageContainer.getComponentZOrder(before.panel)
            if (index >= 0) {
                messageContainer.add(block.panel, index)
            } else {
                messageContainer.add(block.panel)
            }
        } else {
            messageContainer.add(block.panel)
        }
        messageContainer.revalidate()
        messageContainer.repaint()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = chatScroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun createMessageBlock(
        title: String,
        textColor: java.awt.Color,
        collapsible: Boolean,
        collapsedByDefault: Boolean,
        renderItem: RenderItem? = null
    ): MessageBlock {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        val headerLabel = JLabel(title).apply {
            foreground = if (title == "推理") textColor else UIUtil.getLabelForeground()
            font = font.deriveFont(font.style or Font.BOLD)
        }
        val contentArea = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            foreground = textColor
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 12, 6, 8)
            isOpaque = false
        }
        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(contentArea, BorderLayout.CENTER)
            isVisible = !collapsedByDefault
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }

        if (collapsible) {
            val toggle = JButton("▼").apply {
                isFocusable = false
                isContentAreaFilled = false
                isBorderPainted = false
                margin = JBUI.insets(0)
            }
            toggle.text = if (contentPanel.isVisible) "▼" else "▶"
            toggle.addActionListener {
                contentPanel.isVisible = !contentPanel.isVisible
                toggle.text = if (contentPanel.isVisible) "▼" else "▶"
                panel.revalidate()
                panel.repaint()
            }
            header.add(toggle)
        }
        header.add(headerLabel)
        panel.add(header, BorderLayout.NORTH)
        panel.add(contentPanel, BorderLayout.CENTER)

        return MessageBlock(panel, contentArea, renderItem)
    }

    private fun resetMessages(session: ChatSession) {
        session.messages.clear()
        val systemMessage = JsonObject().apply {
            addProperty("role", "system")
            addProperty(
                "content",
                "你是 JTools 智能体, 可调用工具完成任务。插件工具名称以 plugin_ 开头, 系统工具以 jtools_ 开头。避免连续重复调用同一个工具, 如果无法获得新信息请停止并向用户说明。不要在回答内容中输出任何 tool_call/tool_result 标记或 XML 块。"
            )
        }
        session.messages.add(systemMessage)
        syncSessionMessages(session)
    }

    private fun updateStatus() {
        val apiKey = project.pluginState().agentOpenApiKey.trim()
        val baseUrl = project.pluginState().agentOpenApiBaseUrl.trim().ifBlank { "https://api.openai.com/v1" }
        val model = currentSession?.model?.trim()
            ?.ifBlank { project.pluginState().agentModel.trim() }
            ?.ifBlank { "gpt-4o-mini" }
            ?: "gpt-4o-mini"
        val mcpServers = McpSupport.safeServers(project.pluginState().agentMcpServers)
        val enabledCount = mcpServers.count { it.enabled }
        val mcpStatus = if (project.pluginState().agentMcpEnabled) {
            "MCP: $enabledCount/${mcpServers.size}"
        } else {
            "MCP: 未启用"
        }
        statusLabel.text = if (apiKey.isBlank()) {
            "API Key 未配置 | Base URL: $baseUrl | Model: $model | $mcpStatus"
        } else {
            "OpenAPI: 已配置 | Base URL: $baseUrl | Model: $model | $mcpStatus"
        }
    }

    fun refreshStatus() {
        updateStatus()
    }

    private fun truncate(value: String, max: Int = 2000): String {
        if (value.length <= max) {
            return value
        }
        return value.take(max) + "...(truncated)"
    }

    private fun setInputEnabled(enabled: Boolean) {
        inputArea.isEditable = enabled
        inputArea.isEnabled = enabled
        inputArea.isFocusable = enabled
        inputArea.isRequestFocusEnabled = enabled
        if (!enabled && inputArea.isFocusOwner) {
            inputArea.transferFocus()
        }
        if (enabled) {
            inputArea.requestFocusInWindow()
        }
    }

    private fun filterAssistantDelta(delta: String): String {
        var text = delta
        if (!suppressToolMarkup) {
            val start = text.indexOf("<tool_call")
            if (start >= 0) {
                val before = text.substring(0, start)
                val end = text.indexOf("</tool_call>", start)
                return if (end >= 0) {
                    suppressToolMarkup = false
                    before + text.substring(end + "</tool_call>".length)
                } else {
                    suppressToolMarkup = true
                    before
                }
            }
            return text
        } else {
            val end = text.indexOf("</tool_call>")
            return if (end >= 0) {
                suppressToolMarkup = false
                text.substring(end + "</tool_call>".length)
            } else {
                ""
            }
        }
    }

    private data class RenderItem(
        val role: String,
        var content: String,
        val collapsible: Boolean,
        val collapsedByDefault: Boolean,
        var state: AgentRenderState? = null,
    )

    private class ChatSession(
        val id: String,
        var title: String,
        var autoTitle: Boolean,
        var model: String,
        val messages: MutableList<JsonObject>,
        val renders: MutableList<RenderItem>,
        val state: AgentSessionState,
    ) {
        override fun toString(): String = title
    }

    private data class MessageBlock(val panel: JComponent, val textArea: JBTextArea, val renderItem: RenderItem?)
}
