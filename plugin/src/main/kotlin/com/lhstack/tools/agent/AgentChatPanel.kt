package com.lhstack.tools.agent

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.ifNotBlank
import com.lhstack.tools.ext.infoNotify
import com.lhstack.tools.plugins.pluginState
import io.agentscope.core.tool.Toolkit as AgentScopeToolkit
import org.jdesktop.swingx.VerticalLayout
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.HyperlinkEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.DefaultEditorKit
import javax.swing.text.JTextComponent

internal data class AgentRequestUiControls(
    val sessionSelector: JComponent,
    val providerSelector: JComponent,
    val systemPromptSelector: JComponent,
    val modelSelector: JComponent,
    val conversationModeSelector: JComponent,
    val permissionScopeSelector: JComponent,
    val approvalPolicySelector: JComponent,
    val sendAction: AnAction,
    val stopAction: AnAction,
    val providerManageAction: AnAction,
    val systemPromptManageAction: AnAction,
    val modelManageAction: AnAction,
    val modelSettingsAction: AnAction,
    val skillSelectAction: AnAction,
    val skillManageAction: AnAction,
    val mcpManageAction: AnAction,
) {
    fun applyRequestInProgress(
        requestInProgress: Boolean,
        setActionEnabled: (AnAction, Boolean) -> Unit,
        setInputEnabled: (Boolean) -> Unit,
    ) {
        val enabled = !requestInProgress
        setActionEnabled(sendAction, enabled)
        setActionEnabled(stopAction, requestInProgress)
        sessionSelector.isEnabled = enabled
        providerSelector.isEnabled = enabled
        systemPromptSelector.isEnabled = enabled
        modelSelector.isEnabled = enabled
        conversationModeSelector.isEnabled = enabled
        permissionScopeSelector.isEnabled = enabled
        approvalPolicySelector.isEnabled = enabled
        setActionEnabled(providerManageAction, enabled)
        setActionEnabled(systemPromptManageAction, enabled)
        setActionEnabled(modelManageAction, enabled)
        setActionEnabled(modelSettingsAction, enabled)
        setActionEnabled(skillSelectAction, enabled)
        setActionEnabled(skillManageAction, enabled)
        setActionEnabled(mcpManageAction, enabled)
        setInputEnabled(enabled)
    }
}

class AgentChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private companion object {
        private const val TOOL_BLOCK_VISIBLE_LINES = 10
        private val INPUT_COMPOSER_BACKGROUND = JBColor(Color(0xFFFFFF), Color(0x2B2F34))
        private val INPUT_COMPOSER_BORDER = JBColor(Color(0xD3D9E2), Color(0x4E545A))
        private val INPUT_COMPOSER_DIVIDER = JBColor(Color(0xE4E8EF), Color(0x43484D))
        private val INPUT_COMPOSER_FOCUS_BORDER = JBColor(0x4B90FF, 0x4B90FF)
        private val TOP_SYSTEM_PROMPT_WIDTH = JBUI.scale(150)
        private val TOP_PERMISSION_WIDTH = JBUI.scale(92)
        private val TOP_APPROVAL_WIDTH = JBUI.scale(92)
        private const val MARKDOWN_STREAM_RENDER_DELAY_MS = 80
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val messageContainer = JPanel(VerticalLayout(8))
    private val chatScroll = JBScrollPane(messageContainer)
    private val inputArea = JBTextArea(3, 0)
    private val attachmentDraftPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }
    private val attachmentDraftScroll = AgentAttachmentChipUi.createHorizontalStrip(attachmentDraftPanel)
    private val inputCenterPanel = JPanel(BorderLayout(0, 6)).apply {
        isOpaque = false
    }
    private val actionToolbars = mutableListOf<ActionToolbar>()
    private val actionEnabledState = mutableMapOf<AnAction, Boolean>()
    private val quickClearAction = createAction("清空当前会话", Icons.closeAllIcon()) {
        currentSession?.let { clearSession(it) }
    }
    private val sessionManageAction = createAction("会话管理", Icons.libraryIcon()) { openSessionManager() }
    private val modelManageAction = createAction("模型管理", Icons.toolIcon()) { openModelManager() }
    private val modelSettingsAction =
        createAction("模型扩展设置", Icons.modelTuningIcon()) { openModelSettingsDialog() }
    private val providerManageAction = createAction("供应方管理", Icons.providerConfigIcon()) { openProviderManager() }
    private val systemPromptManageAction =
        createAction("系统提示词管理", Icons.promptManageIcon()) { openSystemPromptManager() }
    private val skillSelectAction = createAction("会话 Skills", Icons.sessionSkillsIcon()) { openSkillSelector() }
    private val skillManageAction = createAction("Skills 管理", Icons.skillsManageIcon()) { openSkillManager() }
    private val mcpManageAction = createAction("MCP 配置", Icons.mcpConfigIcon()) { openMcpManager() }
    private val attachmentAction = object : AnAction({ "附件" }, Icons.attachmentIcon()) {
        override fun actionPerformed(e: AnActionEvent) {
            chooseAttachments()
        }

        override fun update(e: AnActionEvent) {
            val visible = AgentInputCapabilitySupport.attachmentButtonVisible(resolveCurrentModelSettings())
            e.presentation.isVisible = visible
            e.presentation.isEnabled = visible && !sending.get() && inputArea.isEnabled
        }
    }
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
    private val inputHintLabel = JLabel(AgentInputShortcutSupport.inputHint())
    private val sessionModel = DefaultComboBoxModel<ChatSession>()
    private val providerModel = DefaultComboBoxModel<AgentProviderState>()
    private val sessionSelector = ComboBox<ChatSession>()
    private val providerSelector = ComboBox<AgentProviderState>()
    private val systemPromptSelector = ComboBox<SystemPromptOption>()
    private val modelSelector = ComboBox<String>()
    private val conversationModeSelector = ComboBox<AgentConversationMode>()
    private val permissionScopeSelector = ComboBox<AgentToolPermissionScope>()
    private val approvalPolicySelector = ComboBox<AgentToolApprovalPolicy>()
    private val permissionHelpButton = JButton(AllIcons.General.ContextHelp).apply {
        toolTipText = "查看权限说明"
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        margin = JBUI.insets(0)
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        minimumSize = preferredSize
        maximumSize = preferredSize
        addActionListener {
            showPermissionHelpDialog()
        }
    }
    private val comboFixedWidth = JBUI.scale(180)
    private val projectKey = resolveProjectKey()
    private val client = AgentClient()
    private val sending = AtomicBoolean(false)
    private val requestCounter = AtomicInteger(0)
    @Volatile
    private var activeRequestId = 0
    private var cancelToken: AgentClient.CancelToken? = null
    private var currentSession: ChatSession? = null
    private var updatingSessionSelection = false
    private var updatingProviderSelection = false
    private var updatingSystemPromptSelection = false
    private var updatingModelSelection = false
    private var updatingConversationModeSelection = false
    private var updatingPermissionScopeSelection = false
    private var updatingApprovalPolicySelection = false

    private var assistantBlock: MessageBlock? = null
    private val streamingTextBlocks = mutableMapOf<String, MessageBlock>()
    private var toolBlock: ToolListBlock? = null
    private val modelCache = mutableMapOf<String, ModelCacheEntry>()
    private val modelLoadInFlight = mutableSetOf<String>()
    private val modelLoadListeners = mutableMapOf<String, MutableList<(List<String>) -> Unit>>()
    private data class ModelCacheEntry(val models: List<String>, val loadedAt: Long)

    private val requestUiControls = AgentRequestUiControls(
        sessionSelector = sessionSelector,
        providerSelector = providerSelector,
        systemPromptSelector = systemPromptSelector,
        modelSelector = modelSelector,
        conversationModeSelector = conversationModeSelector,
        permissionScopeSelector = permissionScopeSelector,
        approvalPolicySelector = approvalPolicySelector,
        sendAction = sendAction,
        stopAction = stopAction,
        providerManageAction = providerManageAction,
        systemPromptManageAction = systemPromptManageAction,
        modelManageAction = modelManageAction,
        modelSettingsAction = modelSettingsAction,
        skillSelectAction = skillSelectAction,
        skillManageAction = skillManageAction,
        mcpManageAction = mcpManageAction,
    )

    init {
        setupChatContainer()
        setupInputArea()
        setupSessionSelector()
        setupProviderSelector()
        setupSystemPromptSelector()
        setupModelSelector()
        setupConversationModeSelector()
        setupPermissionScopeSelector()
        setupApprovalPolicySelector()
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
        val defaultTransferHandler = inputArea.transferHandler
        val defaultPasteAction = inputArea.actionMap.get(DefaultEditorKit.pasteAction)
        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.margin = JBUI.insets(6)
        inputArea.background = UIUtil.getTextFieldBackground()
        inputArea.isOpaque = true
        inputArea.inputMap.put(
            AgentInputShortcutSupport.sendKeyStroke(),
            "sendMessage"
        )
        inputArea.actionMap.put("sendMessage", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                sendMessage()
            }
        })
        bindPasteAttachment(inputArea) {
            if(defaultPasteAction != null){
                defaultPasteAction.actionPerformed(
                    ActionEvent(
                        inputArea,
                        ActionEvent.ACTION_PERFORMED,
                        DefaultEditorKit.pasteAction
                    )
                )
            }else {
                inputArea.text += it
            }
        }
        inputArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                if (AgentInputCapabilitySupport.attachmentButtonVisible(resolveCurrentModelSettings()) &&
                    (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || support.isDataFlavorSupported(
                        DataFlavor.imageFlavor
                    ))
                ) {
                    return true
                }
                return defaultTransferHandler?.canImport(support) ?: false
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) {
                    return false
                }
                if (AgentInputCapabilitySupport.attachmentButtonVisible(resolveCurrentModelSettings()) &&
                    (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || support.isDataFlavorSupported(
                        DataFlavor.imageFlavor
                    ))
                ) {
                    return addAttachmentsFromTransferable(support.transferable)
                }
                return defaultTransferHandler?.importData(support) ?: false
            }
        }
    }

    private fun bindPasteAttachment(component: JBTextArea, onTextFallback: (String) -> Unit) {
        val action = PasteAttachmentAction(
            onFiles = { files ->
                addAttachmentFiles(files)
            },
            onImage = { image ->
                saveClipboardImage(image)?.let { addAttachmentFiles(listOf(it)) }
            },
            onTextFallback = onTextFallback
        )
        val pasteAction = ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE) ?: return
        action.registerCustomShortcutSet(pasteAction.shortcutSet, component)
    }

    private fun setupSessionSelector() {
        sessionSelector.model = sessionModel
        sessionSelector.maximumRowCount = 8
        sessionSelector.isEditable = false
        configureComboBox(sessionSelector, comboFixedWidth, { (it as? ChatSession)?.title ?: it?.toString().orEmpty() })
        sessionSelector.addActionListener {
            if (updatingSessionSelection || sending.get()) {
                return@addActionListener
            }
            val selected = sessionSelector.selectedItem as? ChatSession ?: return@addActionListener
            switchSession(selected)
        }
    }

    private fun setupProviderSelector() {
        providerSelector.model = providerModel
        providerSelector.maximumRowCount = 8
        providerSelector.isEditable = false
        configureComboBox(providerSelector, comboFixedWidth, {
            val provider = it as? AgentProviderState
            val type = AgentProviderType.fromId(provider?.type).displayName
            if (provider == null) "" else "${provider.name} ($type)"
        })
        providerSelector.addActionListener {
            if (updatingProviderSelection || sending.get()) {
                return@addActionListener
            }
            val selected = providerSelector.selectedItem as? AgentProviderState ?: return@addActionListener
            updateCurrentProvider(selected)
        }
        refreshProviderSelector(null)
    }

    private fun setupSystemPromptSelector() {
        systemPromptSelector.maximumRowCount = 8
        systemPromptSelector.isEditable = false
        configureComboBox(systemPromptSelector, TOP_SYSTEM_PROMPT_WIDTH, {
            (it as? SystemPromptOption)?.label ?: it?.toString().orEmpty()
        })
        systemPromptSelector.addActionListener {
            if (updatingSystemPromptSelection || sending.get()) {
                return@addActionListener
            }
            val selected = systemPromptSelector.selectedItem as? SystemPromptOption ?: return@addActionListener
            updateCurrentSystemPrompt(selected.id)
        }
        refreshSystemPromptSelector(null)
    }

    private fun setupModelSelector() {
        modelSelector.isEditable = true
        configureComboBox(modelSelector, comboFixedWidth, { it?.toString().orEmpty() }, ellipsizeEditor = true)
        modelSelector.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                refreshModelSelector(resolveSelectedModel())
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}

            override fun popupMenuCanceled(e: PopupMenuEvent?) {}
        })
        refreshModelSelector(resolveDefaultModel(resolveSelectedProvider()))
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

    private fun setupConversationModeSelector() {
        conversationModeSelector.maximumRowCount = 4
        conversationModeSelector.isEditable = false
        conversationModeSelector.isVisible = AgentConversationModeSupport.selectorVisible()
        configureComboBox(conversationModeSelector, comboFixedWidth, {
            (it as? AgentConversationMode)?.displayName ?: it?.toString().orEmpty()
        })
        refreshConversationModeSelector(AgentConversationMode.CHAT.id)
        conversationModeSelector.addActionListener {
            if (updatingConversationModeSelection || sending.get()) {
                return@addActionListener
            }
            val selected = conversationModeSelector.selectedItem as? AgentConversationMode ?: return@addActionListener
            updateConversationMode(selected)
        }
    }

    private fun setupPermissionScopeSelector() {
        permissionScopeSelector.maximumRowCount = AgentToolPermissionScope.entries.size
        permissionScopeSelector.isEditable = false
        configureComboBox(
            permissionScopeSelector,
            TOP_PERMISSION_WIDTH,
            { (it as? AgentToolPermissionScope)?.displayName ?: it?.toString().orEmpty() },
            tooltipProvider = { (it as? AgentToolPermissionScope)?.tooltip ?: it?.toString().orEmpty() }
        )
        refreshPermissionScopeSelector(currentSession?.state?.runtime?.permissionScope)
        permissionScopeSelector.addActionListener {
            if (updatingPermissionScopeSelection || sending.get()) {
                return@addActionListener
            }
            val selected = permissionScopeSelector.selectedItem as? AgentToolPermissionScope ?: return@addActionListener
            updateCurrentPermissionScope(selected)
        }
    }

    private fun setupApprovalPolicySelector() {
        approvalPolicySelector.maximumRowCount = AgentToolApprovalPolicy.entries.size
        approvalPolicySelector.isEditable = false
        configureComboBox(
            approvalPolicySelector,
            TOP_APPROVAL_WIDTH,
            { (it as? AgentToolApprovalPolicy)?.displayName ?: it?.toString().orEmpty() },
            tooltipProvider = { (it as? AgentToolApprovalPolicy)?.tooltip ?: it?.toString().orEmpty() }
        )
        refreshApprovalPolicySelector(currentSession?.state?.runtime?.approvalPolicy)
        approvalPolicySelector.addActionListener {
            if (updatingApprovalPolicySelection || sending.get()) {
                return@addActionListener
            }
            val selected = approvalPolicySelector.selectedItem as? AgentToolApprovalPolicy ?: return@addActionListener
            updateCurrentApprovalPolicy(selected)
        }
    }


    private fun configureComboBox(
        comboBox: ComboBox<*>,
        fixedWidth: Int,
        textProvider: (Any?) -> String,
        tooltipProvider: (Any?) -> String = textProvider,
        ellipsizeEditor: Boolean = false
    ) {
        applyFixedWidth(comboBox, fixedWidth)
        comboBox.renderer = createEllipsisRenderer(comboBox, textProvider, tooltipProvider)
        if (ellipsizeEditor) {
            comboBox.editor = EllipsisComboBoxEditor(comboBox, textProvider)
        }
    }

    private fun applyFixedWidth(comboBox: ComboBox<*>, fixedWidth: Int) {
        val height = comboBox.preferredSize.height
        val size = Dimension(fixedWidth, height)
        comboBox.preferredSize = size
        comboBox.minimumSize = size
        comboBox.maximumSize = size
    }

    private fun createEllipsisRenderer(
        comboBox: ComboBox<*>,
        textProvider: (Any?) -> String,
        tooltipProvider: (Any?) -> String = textProvider,
    ): DefaultListCellRenderer {
        return object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val fullText = textProvider(value)
                val displayText = if (index < 0) {
                    val metrics = getFontMetrics(font ?: comboBox.font)
                    ellipsizeText(fullText, metrics, comboDisplayWidth(comboBox))
                } else {
                    fullText
                }
                val label = super.getListCellRendererComponent(
                    list,
                    displayText,
                    index,
                    isSelected,
                    cellHasFocus
                ) as JLabel
                label.toolTipText = tooltipProvider(value).takeIf { it.isNotBlank() }
                return label
            }
        }
    }

    private fun createOptionRenderer(labelProvider: (Any?) -> String): DefaultListCellRenderer {
        return object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val label = labelProvider(value)
                return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
            }
        }
    }

    private fun comboDisplayWidth(comboBox: ComboBox<*>): Int {
        val rawWidth = if (comboBox.width > 0) comboBox.width else comboBox.preferredSize.width
        return maxOf(0, rawWidth - JBUI.scale(32))
    }

    private fun ellipsizeText(text: String, metrics: FontMetrics, maxWidth: Int): String {
        if (text.isBlank()) {
            return text
        }
        if (metrics.stringWidth(text) <= maxWidth) {
            return text
        }
        val ellipsis = "..."
        val ellipsisWidth = metrics.stringWidth(ellipsis)
        if (maxWidth <= ellipsisWidth) {
            return ellipsis
        }
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high) / 2
            val width = metrics.stringWidth(text.substring(0, mid)) + ellipsisWidth
            if (width <= maxWidth) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        val end = maxOf(0, low - 1)
        return text.substring(0, end) + ellipsis
    }

    private inner class EllipsisComboBoxEditor(
        private val comboBox: ComboBox<*>,
        private val textProvider: (Any?) -> String
    ) : ComboBoxEditor {
        private val textField = JBTextField()
        private var currentItem: Any? = null

        init {
            textField.border = JBUI.Borders.empty(0, 4)
            textField.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    textField.text = textProvider(currentItem)
                    textField.selectAll()
                }

                override fun focusLost(e: FocusEvent) {
                    currentItem = textField.text
                    updateDisplay()
                }
            })
        }

        override fun getEditorComponent(): Component = textField

        override fun setItem(anObject: Any?) {
            currentItem = anObject
            updateDisplay()
        }

        override fun getItem(): Any? {
            if (textField.hasFocus()) {
                currentItem = textField.text
            }
            return currentItem?.toString().orEmpty()
        }

        override fun selectAll() {
            textField.selectAll()
        }

        override fun addActionListener(l: ActionListener) {
            textField.addActionListener(l)
        }

        override fun removeActionListener(l: ActionListener) {
            textField.removeActionListener(l)
        }

        private fun updateDisplay() {
            val fullText = textProvider(currentItem)
            textField.toolTipText = fullText.takeIf { it.isNotBlank() }
            textField.text = if (textField.hasFocus()) {
                fullText
            } else {
                ellipsizeText(fullText, textField.getFontMetrics(textField.font), comboDisplayWidth(comboBox))
            }
        }
    }

    private fun createAction(
        description: String,
        icon: javax.swing.Icon,
        enabledProvider: (() -> Boolean)? = null,
        action: () -> Unit
    ): AnAction {
        return object : AnAction({ description }, AgentToolbarIconSupport.normalize(icon)) {
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

    private fun createToolbar(
        id: String,
        group: DefaultActionGroup,
        horizontal: Boolean,
        target: JComponent
    ): JComponent {
        val toolbar = ActionManager.getInstance().createActionToolbar(id, group, horizontal)
        toolbar.targetComponent = target
        toolbar.setMinimumButtonSize(AgentToolbarIconSupport.minimumButtonSize)
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

    private fun resolveModelForSettings(provider: AgentProviderState?): String {
        val selected = resolveSelectedModel().trim()
        if (selected.isNotBlank()) {
            return selected
        }
        val active = provider?.activeModel?.trim().orEmpty()
        if (active.isNotBlank()) {
            return active
        }
        return currentSession?.model?.trim().orEmpty()
    }

    private fun initSessions() {
        val stored = resolveStoredSessions()
        if (stored.isNotEmpty()) {
            stored.forEach { state ->
                sessionModel.addElement(toSession(state))
            }
            val activeId = resolveActiveSessionId(stored)
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
        val resolvedProvider = resolveSelectedProvider()
        val resolvedModel = modelName.ifBlank { resolveDefaultModel(resolvedProvider) }
        if (resolvedProvider != null && resolvedModel.isNotBlank()) {
            resolvedProvider.activeModel = resolvedModel
            ensureModelExists(resolvedProvider, resolvedModel)
        }
        val state = AgentSessionState().apply {
            id = UUID.randomUUID().toString()
            projectKey = this@AgentChatPanel.projectKey
            providerId = resolvedProvider?.id.orEmpty()
            systemPromptId = ""
            title = "新会话"
            autoTitle = true
            model = resolvedModel
            conversationMode = AgentConversationMode.CHAT.id
            enabledSkillIds = project.pluginState().agentSkills
                .filter { it.enabledByDefault }
                .map { it.id }
                .toMutableList()
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
        if (state.projectKey.isBlank()) {
            state.projectKey = projectKey
        }
        val prompts = ensureSystemPromptList()
        state.systemPromptId = AgentSystemPromptSupport.normalizeSelectedPromptId(state.systemPromptId, prompts)
        AgentSystemPromptSupport.syncSessionSystemPrompt(state, prompts)
        val availableSkillIds = project.pluginState().agentSkills.map { it.id }.toSet()
        state.enabledSkillIds = state.enabledSkillIds
            .filter { it in availableSkillIds }
            .distinct()
            .toMutableList()
        val resolvedProvider = resolveProviderForSession(state)
        val resolvedModel = state.model.trim().ifBlank { resolveDefaultModel(resolvedProvider) }
        state.model = resolvedModel
        if (resolvedProvider != null && resolvedModel.isNotBlank()) {
            resolvedProvider.activeModel = resolvedModel
            ensureModelExists(resolvedProvider, resolvedModel)
        }
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
                attachments = renderState.attachments.toMutableList(),
                state = renderState
            )
        }.toMutableList()
        return ChatSession(
            id = state.id,
            title = state.title.ifBlank { "新会话" },
            autoTitle = state.autoTitle,
            model = resolvedModel,
            providerId = resolvedProvider?.id.orEmpty(),
            messages = messages,
            renders = renders,
            state = state
        )
    }

    private fun switchSession(session: ChatSession) {
        currentSession = session
        setActiveSessionId(session.id)
        syncSessionSystemPrompt(session)
        updatingSessionSelection = true
        sessionSelector.selectedItem = session
        updatingSessionSelection = false
        val resolvedProvider = resolveProviderForSession(session.state)
        session.providerId = resolvedProvider?.id.orEmpty()
        refreshProviderSelector(session.providerId)
        refreshSystemPromptSelector(session.state.systemPromptId)
        val resolvedModel = session.model.trim().ifBlank { resolveDefaultModel(resolvedProvider) }
        session.model = resolvedModel
        session.state.model = resolvedModel
        if (resolvedProvider != null && resolvedModel.isNotBlank()) {
            resolvedProvider.activeModel = resolvedModel
            ensureModelExists(resolvedProvider, resolvedModel)
        }
        refreshModelSelector(resolvedModel)
        refreshConversationModeSelector(session.state.conversationMode)
        refreshPermissionScopeSelector(session.state.runtime.permissionScope)
        refreshApprovalPolicySelector(session.state.runtime.approvalPolicy)
        renderSession(session)
        refreshAttachmentDrafts()
        updateStatus()
    }

    private fun renderSession(session: ChatSession) {
        messageContainer.removeAll()
        messageContainer.revalidate()
        messageContainer.repaint()
        clearStreamingRequestState()
        session.renders.forEach { item ->
            appendRenderedItem(item)
        }
        scrollToBottom()
    }

    private fun appendRenderedItem(item: RenderItem) {
        if (item.role == "工具") {
            appendRenderedToolItem(item)
            return
        }
        val color = if (item.role == "推理") JBColor(0x6A6A6A, 0x9A9A9A) else UIUtil.getLabelForeground()
        val block = createMessageBlock(item.role, color, item.collapsible, item.collapsedByDefault, item)
        setBlockContent(block, item.content)
        addMessageBlock(block)
    }

    private fun appendRenderedToolItem(item: RenderItem) {
        val block = createToolListBlock(item)
        val toolEntries = item.state?.toolEntries
            ?.takeIf { it.isNotEmpty() }
            ?: item.content.takeIf { it.isNotBlank() }?.let { legacyContent ->
                mutableListOf(
                    AgentToolRenderEntryState().apply {
                        id = "legacy-tool-log"
                        index = 0
                        name = "工具日志"
                        startedAt = 0L
                        status = "已完成"
                        result = legacyContent
                    }
                )
            }
            ?: emptyList()
        toolEntries.sortedBy { it.index }.forEach { entryState ->
            val entryCard = createToolEntryCard(entryState)
            block.entriesById[entryState.id] = entryCard
            block.listPanel.add(entryCard.panel)
        }
        updateToolBlockSummary(block)
        block.listPanel.revalidate()
        block.listPanel.repaint()
        addBlockComponent(block.panel)
    }

    private fun addRenderItem(item: RenderItem, before: RenderItem? = null) {
        val session = currentSession ?: return
        val stateItem = item.state ?: AgentRenderState().apply {
            role = item.role
            content = item.content
            collapsible = item.collapsible
            collapsedByDefault = item.collapsedByDefault
            attachments = item.attachments.toMutableList()
        }.also { item.state = it }
        stateItem.attachments = item.attachments.toMutableList()
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

    private fun syncSessionSystemPrompt(session: ChatSession) {
        val prompts = ensureSystemPromptList()
        session.state.systemPromptId = AgentSystemPromptSupport.normalizeSelectedPromptId(session.state.systemPromptId, prompts)
        AgentSystemPromptSupport.syncSessionSystemPrompt(session.state, prompts)
        AgentSystemPromptSupport.syncSystemPromptJson(
            session.messages,
            AgentSystemPromptSupport.resolvePromptContent(prompts, session.state.systemPromptId)
        )
        syncSessionMessages(session)
    }

    private fun updateCurrentModel(model: String) {
        val session = currentSession ?: return
        val provider = resolveSelectedProvider()
        val resolved = model.trim()
        if (resolved.isBlank()) {
            refreshModelSelector(session.model)
            return
        }
        session.model = resolved
        session.state.model = resolved
        if (provider != null) {
            provider.activeModel = resolved
            ensureModelExists(provider, resolved)
        }
        refreshModelSelector(resolved)
        refreshConversationModeSelector(session.state.conversationMode)
        refreshAttachmentDrafts()
        updateStatus()
        updateModelSettingsAction()
    }

    private fun updateCurrentProvider(provider: AgentProviderState) {
        val session = currentSession ?: return
        session.providerId = provider.id
        session.state.providerId = provider.id
        project.pluginState().agentActiveProviderId = provider.id
        val resolvedModel = provider.activeModel.trim().ifBlank { resolveDefaultModel(provider) }
        if (resolvedModel.isNotBlank()) {
            provider.activeModel = resolvedModel
            ensureModelExists(provider, resolvedModel)
        }
        session.model = resolvedModel
        session.state.model = resolvedModel
        refreshModelSelector(resolvedModel)
        refreshConversationModeSelector(session.state.conversationMode)
        refreshAttachmentDrafts()
        updateStatus()
        updateModelSettingsAction()
    }

    private fun updateConversationMode(mode: AgentConversationMode) {
        val session = currentSession ?: return
        session.state.conversationMode = mode.id
        refreshConversationModeSelector(mode.id)
        updateStatus()
    }

    private fun updateCurrentPermissionScope(scope: AgentToolPermissionScope) {
        val session = currentSession ?: return
        session.state.runtime.permissionScope = scope.id
        refreshPermissionScopeSelector(scope.id)
        client.clearSession(session.id)
        updateStatus()
    }

    private fun updateCurrentApprovalPolicy(policy: AgentToolApprovalPolicy) {
        val session = currentSession ?: return
        session.state.runtime.approvalPolicy = policy.id
        refreshApprovalPolicySelector(policy.id)
        client.clearSession(session.id)
        updateStatus()
    }

    private fun updateModelSettingsAction() {
        val provider = resolveSelectedProvider()
        val model = resolveModelForSettings(provider)
        setActionEnabled(modelSettingsAction, !sending.get() && provider != null && model.isNotBlank())
        updateToolbars()
    }

    private fun openModelSettingsDialog() {
        val provider = resolveSelectedProvider()
        if (provider == null) {
            project.errorNotify("模型扩展设置", "请先选择供应方")
            return
        }
        val model = resolveModelForSettings(provider)
        if (model.isBlank()) {
            project.errorNotify("模型扩展设置", "请先选择模型")
            return
        }
        when (AgentProviderType.fromId(provider.type)) {
            AgentProviderType.OPENAI -> openOpenAiModelSettingsDialog(provider, model)
            AgentProviderType.ANTHROPIC -> openAnthropicModelSettingsDialog(provider, model)
        }
    }

    private fun openOpenAiModelSettingsDialog(provider: AgentProviderState, model: String) {
        val settings = AgentProviderSupport.findModelSettings(provider, model)
        val streamingCheck = JCheckBox("启用流式输出").apply { isSelected = settings?.streamingEnabled != false }
        val capabilitySelection = createCapabilitySelection(
            settings?.modelCapabilities.orEmpty(),
            AgentModelCapabilityCatalog.multimodalOptions(),
        )
        val toolCallingCheck = JCheckBox("支持工具调用").apply {
            isOpaque = false
            val capabilities = settings?.modelCapabilities.orEmpty()
            isSelected = capabilities.isEmpty() || capabilities.contains("tool_calling")
        }
        val effortOptions = listOf(
            ModelSettingOption("默认 (不设置)", ""),
            ModelSettingOption("NONE", "none"),
            ModelSettingOption("MINIMAL", "minimal"),
            ModelSettingOption("LOW", "low"),
            ModelSettingOption("MEDIUM", "medium"),
            ModelSettingOption("HIGH", "high"),
            ModelSettingOption("XHIGH", "xhigh"),
        )
        val responseFormatOptions = listOf(
            ModelSettingOption("默认 (不设置)", ""),
            ModelSettingOption("TEXT", "text"),
            ModelSettingOption("JSON_OBJECT", "json_object"),
            ModelSettingOption("JSON_SCHEMA", "json_schema"),
        )
        val logprobsOptions = listOf(
            ModelSettingOption("默认 (不设置)", ""),
            ModelSettingOption("启用", "true"),
            ModelSettingOption("关闭", "false"),
        )
        val toolChoiceOptions = listOf(
            ModelSettingOption("默认 (不设置)", ""),
            ModelSettingOption("AUTO", "auto"),
            ModelSettingOption("NONE", "none"),
            ModelSettingOption("REQUIRED", "required"),
        )
        val boolOptions = listOf(
            ModelSettingOption("默认 (不设置)", ""),
            ModelSettingOption("启用", "true"),
            ModelSettingOption("关闭", "false"),
        )
        val effortCombo = ComboBox(effortOptions.toTypedArray()).apply {
            renderer = createOptionRenderer { (it as? ModelSettingOption)?.label.orEmpty() }
            selectedItem = effortOptions.firstOrNull {
                it.value.equals(settings?.openAiReasoningEffort, ignoreCase = true)
            } ?: effortOptions.first()
        }
        val temperatureField = JBTextField(settings?.openAiTemperature.orEmpty())
        val topPField = JBTextField(settings?.openAiTopP.orEmpty())
        val maxTokensField = JBTextField(settings?.openAiMaxTokens.orEmpty())
        val maxCompletionTokensField = JBTextField(settings?.openAiMaxCompletionTokens.orEmpty())
        val presencePenaltyField = JBTextField(settings?.openAiPresencePenalty.orEmpty())
        val frequencyPenaltyField = JBTextField(settings?.openAiFrequencyPenalty.orEmpty())
        val seedField = JBTextField(settings?.openAiSeed.orEmpty())
        val stopArea = JBTextArea(3, 28).apply {
            text = settings?.openAiStopSequences.orEmpty()
            lineWrap = true
            wrapStyleWord = true
        }
        val responseFormatCombo = ComboBox(responseFormatOptions.toTypedArray()).apply {
            renderer = createOptionRenderer { (it as? ModelSettingOption)?.label.orEmpty() }
            selectedItem = responseFormatOptions.firstOrNull {
                it.value.equals(settings?.openAiResponseFormat, ignoreCase = true)
            } ?: responseFormatOptions.first()
        }
        val schemaNameField = JBTextField(settings?.openAiResponseFormatSchemaName.orEmpty())
        val schemaDescField = JBTextField(settings?.openAiResponseFormatSchemaDescription.orEmpty())
        val schemaStrictCheck =
            JCheckBox("Strict").apply { isSelected = settings?.openAiResponseFormatSchemaStrict == true }
        val schemaArea = JBTextArea(6, 28).apply {
            text = settings?.openAiResponseFormatSchemaJson.orEmpty()
            lineWrap = true
            wrapStyleWord = true
        }
        val logprobsCombo = ComboBox(logprobsOptions.toTypedArray()).apply {
            renderer = createOptionRenderer { (it as? ModelSettingOption)?.label.orEmpty() }
            selectedItem = logprobsOptions.firstOrNull {
                it.value.equals(settings?.openAiLogprobs, ignoreCase = true)
            } ?: logprobsOptions.first()
        }
        val topLogprobsField = JBTextField(settings?.openAiTopLogprobs.orEmpty())
        val toolChoiceCombo = ComboBox(toolChoiceOptions.toTypedArray()).apply {
            renderer = createOptionRenderer { (it as? ModelSettingOption)?.label.orEmpty() }
            selectedItem = toolChoiceOptions.firstOrNull {
                it.value.equals(settings?.openAiToolChoice, ignoreCase = true)
            } ?: toolChoiceOptions.first()
        }
        val parallelToolCallsCombo = ComboBox(boolOptions.toTypedArray()).apply {
            renderer = createOptionRenderer { (it as? ModelSettingOption)?.label.orEmpty() }
            selectedItem = boolOptions.firstOrNull {
                it.value.equals(settings?.openAiParallelToolCalls, ignoreCase = true)
            } ?: boolOptions.first()
        }

        fun updateSchemaState() {
            val mode = (responseFormatCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
            val enabled = mode == "json_schema"
            schemaNameField.isEnabled = enabled
            schemaDescField.isEnabled = enabled
            schemaStrictCheck.isEnabled = enabled
            schemaArea.isEnabled = enabled
        }

        fun updateLogprobsState() {
            val enabled = (logprobsCombo.selectedItem as? ModelSettingOption)?.value == "true"
            topLogprobsField.isEnabled = enabled
        }
        responseFormatCombo.addItemListener { updateSchemaState() }
        logprobsCombo.addItemListener { updateLogprobsState() }
        updateSchemaState()
        updateLogprobsState()

        val capabilityCard = AgentFormUi.sectionCard(
            "模型能力",
            "这里配置附件能力、工具能力和流式输出。",
            AgentFormUi.verticalStack(
                AgentFormUi.twoColumnGrid(
                    AgentFormUi.fieldTile("模型", JLabel(model), "当前正在配置的模型名称。"),
                    AgentFormUi.fieldTile("流式输出", streamingCheck, "决定该模型是否支持流式返回内容。"),
                ),
                AgentFormUi.fieldTile(
                    "多模态能力",
                    capabilitySelection.panel,
                    "选择这个模型支持的输入模态。聊天框附件按钮会根据这里动态显示。"
                ),
                AgentFormUi.fieldTile("工具调用", toolCallingCheck, "勾选后表示该模型支持工具调用。"),
            )
        )
        val samplingCard = AgentFormUi.sectionCard(
            "采样与输出",
            "控制回答风格、长度和随机性。",
            AgentFormUi.verticalStack(
                AgentFormUi.twoColumnGrid(
                    AgentFormUi.fieldTile("思考强度", effortCombo, "控制模型在推理阶段投入的计算强度。"),
                    AgentFormUi.fieldTile("温度", temperatureField, "控制输出随机性，值越高越发散。"),
                    AgentFormUi.fieldTile("Top P", topPField, "控制核采样范围。"),
                    AgentFormUi.fieldTile("最大输出 Tokens", maxTokensField, "限制模型本次回答的最大输出长度。"),
                    AgentFormUi.fieldTile(
                        "最大完成 Tokens",
                        maxCompletionTokensField,
                        "限制 completion 阶段的 token 上限。"
                    ),
                    AgentFormUi.fieldTile("随机种子", seedField, "在支持的模型上固定随机种子，便于复现。"),
                    AgentFormUi.fieldTile("存在惩罚", presencePenaltyField, "降低重复主题出现的概率。"),
                    AgentFormUi.fieldTile("频率惩罚", frequencyPenaltyField, "降低重复措辞出现的概率。"),
                ),
                AgentFormUi.fieldTile("停止序列", JBScrollPane(stopArea), "配置一个或多个停止输出的序列。"),
            )
        )
        val structureCard = AgentFormUi.sectionCard(
            "结构化输出",
            "控制文本输出、JSON 对象和 Schema 输出。",
            AgentFormUi.verticalStack(
                AgentFormUi.twoColumnGrid(
                    AgentFormUi.fieldTile("响应格式", responseFormatCombo, "控制模型输出文本或结构化 JSON。"),
                    AgentFormUi.fieldTile("严格模式", schemaStrictCheck, "决定是否严格遵守 Schema。"),
                    AgentFormUi.fieldTile("Schema 名称", schemaNameField, "JSON Schema 的名称。"),
                    AgentFormUi.fieldTile("Schema 描述", schemaDescField, "JSON Schema 的中文说明。"),
                ),
                AgentFormUi.fieldTile("Schema JSON", JBScrollPane(schemaArea), "完整的 JSON Schema 对象。"),
            )
        )
        val toolCard = AgentFormUi.sectionCard(
            "工具与调试",
            "控制工具调用策略和调试信息输出。",
            AgentFormUi.twoColumnGrid(
                AgentFormUi.fieldTile("工具调用策略", toolChoiceCombo, "控制模型自动、禁止或强制调用工具。"),
                AgentFormUi.fieldTile("并行工具调用", parallelToolCallsCombo, "是否允许一次并行触发多个工具调用。"),
                AgentFormUi.fieldTile("Logprobs", logprobsCombo, "是否返回输出 token 的概率信息。"),
                AgentFormUi.fieldTile("Top Logprobs", topLogprobsField, "每个 token 返回的最高概率候选数量。"),
            )
        )
        val panel = AgentFormUi.verticalStack(capabilityCard, samplingCard, structureCard, toolCard)

        val dialog = object : DialogWrapper(project, false) {
            init {
                title = "模型扩展设置 - OpenAI"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val scroll = JBScrollPane(panel)
                scroll.preferredSize = Dimension(JBUI.scale(760), JBUI.scale(680))
                return scroll
            }

            override fun doOKAction() {
                fun requireNumber(field: JBTextField, label: String, integerOnly: Boolean = false): Boolean {
                    val text = field.text.trim()
                    if (text.isBlank()) {
                        return true
                    }
                    val valid = if (integerOnly) text.toLongOrNull() != null else text.toDoubleOrNull() != null
                    if (!valid) {
                        Messages.showErrorDialog(project, "$label 必须是数字", "参数无效")
                    }
                    return valid
                }
                if (!requireNumber(temperatureField, "Temperature")) return
                if (!requireNumber(topPField, "Top P")) return
                if (!requireNumber(maxTokensField, "Max Tokens", integerOnly = true)) return
                if (!requireNumber(maxCompletionTokensField, "Max Completion Tokens", integerOnly = true)) return
                if (!requireNumber(presencePenaltyField, "Presence Penalty")) return
                if (!requireNumber(frequencyPenaltyField, "Frequency Penalty")) return
                if (!requireNumber(seedField, "Seed", integerOnly = true)) return
                val responseMode = (responseFormatCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
                val logprobsValue = (logprobsCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
                if (logprobsValue == "true" && !requireNumber(
                        topLogprobsField,
                        "Top Logprobs",
                        integerOnly = true
                    )
                ) return
                if (responseMode == "json_schema") {
                    if (schemaArea.text.trim().isEmpty()) {
                        Messages.showErrorDialog(project, "Schema JSON 不能为空", "参数无效")
                        return
                    }
                    try {
                        JsonParser.parseString(schemaArea.text.trim()).asJsonObject
                    } catch (_: Throwable) {
                        Messages.showErrorDialog(project, "Schema JSON 必须是有效的 JSON 对象", "参数无效")
                        return
                    }
                }

                val target = AgentProviderSupport.getOrCreateModelSettings(provider, model)
                target.streamingEnabled = streamingCheck.isSelected
                target.chatModeEnabled = true
                target.responsesModeEnabled = false
                target.modelCapabilities = selectedCapabilityIds(capabilitySelection).apply {
                    if (toolCallingCheck.isSelected) add("tool_calling")
                }
                target.openAiReasoningEffort = (effortCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
                target.openAiTemperature = temperatureField.text.trim()
                target.openAiTopP = topPField.text.trim()
                target.openAiMaxTokens = maxTokensField.text.trim()
                target.openAiMaxCompletionTokens = maxCompletionTokensField.text.trim()
                target.openAiPresencePenalty = presencePenaltyField.text.trim()
                target.openAiFrequencyPenalty = frequencyPenaltyField.text.trim()
                target.openAiSeed = seedField.text.trim()
                target.openAiStopSequences = stopArea.text.trim()
                target.openAiResponseFormat = responseMode
                target.openAiResponseFormatSchemaName = schemaNameField.text.trim()
                target.openAiResponseFormatSchemaDescription = schemaDescField.text.trim()
                target.openAiResponseFormatSchemaStrict = schemaStrictCheck.isSelected
                target.openAiResponseFormatSchemaJson = schemaArea.text.trim()
                target.openAiLogprobs = logprobsValue
                target.openAiTopLogprobs = topLogprobsField.text.trim()
                target.openAiToolChoice = (toolChoiceCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
                target.openAiParallelToolCalls =
                    (parallelToolCallsCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
                super.doOKAction()
            }
        }
        if (dialog.showAndGet()) {
            refreshConversationModeSelector(currentSession?.state?.conversationMode)
            refreshAttachmentDrafts()
            updateStatus()
            updateToolbars()
        }
    }

    private fun openAnthropicModelSettingsDialog(provider: AgentProviderState, model: String) {
        val settings = AgentProviderSupport.findModelSettings(provider, model)
        val streamingCheck = JCheckBox("启用流式输出").apply { isSelected = settings?.streamingEnabled != false }
        val capabilitySelection = createCapabilitySelection(
            settings?.modelCapabilities.orEmpty(),
            AgentModelCapabilityCatalog.multimodalOptions(),
        )
        val toolCallingCheck = JCheckBox("支持工具调用").apply {
            isOpaque = false
            val capabilities = settings?.modelCapabilities.orEmpty()
            isSelected = capabilities.isEmpty() || capabilities.contains("tool_calling")
        }
        val thinkingOptions = listOf(
            ModelSettingOption("默认 (不设置)", ""),
            ModelSettingOption("启用", "enabled"),
            ModelSettingOption("自适应", "adaptive"),
            ModelSettingOption("禁用", "disabled"),
        )
        val serviceTierOptions = listOf(
            ModelSettingOption("默认 (不设置)", ""),
            ModelSettingOption("AUTO", "auto"),
            ModelSettingOption("STANDARD_ONLY", "standard_only"),
        )
        val outputEffortOptions = listOf(
            ModelSettingOption("默认 (不设置)", ""),
            ModelSettingOption("LOW", "low"),
            ModelSettingOption("MEDIUM", "medium"),
            ModelSettingOption("HIGH", "high"),
            ModelSettingOption("MAX", "max"),
        )
        val thinkingCombo = ComboBox(thinkingOptions.toTypedArray()).apply {
            renderer = createOptionRenderer { (it as? ModelSettingOption)?.label.orEmpty() }
            selectedItem = thinkingOptions.firstOrNull {
                it.value.equals(settings?.anthropicThinkingMode, ignoreCase = true)
            } ?: thinkingOptions.first()
        }
        val budgetField = JBTextField(settings?.anthropicThinkingBudgetTokens?.takeIf { it > 0 }?.toString().orEmpty())
        val maxTokensField = JBTextField(settings?.anthropicMaxTokens.orEmpty())
        val temperatureField = JBTextField(settings?.anthropicTemperature.orEmpty())
        val topPField = JBTextField(settings?.anthropicTopP.orEmpty())
        val topKField = JBTextField(settings?.anthropicTopK.orEmpty())
        val stopArea = JBTextArea(3, 28).apply {
            text = settings?.anthropicStopSequences.orEmpty()
            lineWrap = true
            wrapStyleWord = true
        }
        val serviceTierCombo = ComboBox(serviceTierOptions.toTypedArray()).apply {
            renderer = createOptionRenderer { (it as? ModelSettingOption)?.label.orEmpty() }
            selectedItem = serviceTierOptions.firstOrNull {
                it.value.equals(settings?.anthropicServiceTier, ignoreCase = true)
            } ?: serviceTierOptions.first()
        }
        val inferenceGeoField = JBTextField(settings?.anthropicInferenceGeo.orEmpty())
        val metadataUserIdField = JBTextField(settings?.anthropicMetadataUserId.orEmpty())
        val outputEffortCombo = ComboBox(outputEffortOptions.toTypedArray()).apply {
            renderer = createOptionRenderer { (it as? ModelSettingOption)?.label.orEmpty() }
            selectedItem = outputEffortOptions.firstOrNull {
                it.value.equals(settings?.anthropicOutputEffort, ignoreCase = true)
            } ?: outputEffortOptions.first()
        }
        val outputSchemaArea = JBTextArea(6, 28).apply {
            text = settings?.anthropicOutputSchemaJson.orEmpty()
            lineWrap = true
            wrapStyleWord = true
        }

        fun updateBudgetState() {
            val enabled = (thinkingCombo.selectedItem as? ModelSettingOption)?.value == "enabled"
            budgetField.isEnabled = enabled
        }
        thinkingCombo.addItemListener { updateBudgetState() }
        updateBudgetState()

        val capabilityCard = AgentFormUi.sectionCard(
            "模型能力",
            "这里配置附件能力、工具能力和流式输出。",
            AgentFormUi.verticalStack(
                AgentFormUi.twoColumnGrid(
                    AgentFormUi.fieldTile("模型", JLabel(model), "当前正在配置的模型名称。"),
                    AgentFormUi.fieldTile("流式输出", streamingCheck, "决定该模型是否支持流式返回内容。"),
                ),
                AgentFormUi.fieldTile(
                    "多模态能力",
                    capabilitySelection.panel,
                    "选择这个模型支持的输入模态。聊天框附件按钮会根据这里动态显示。"
                ),
                AgentFormUi.fieldTile("工具调用", toolCallingCheck, "勾选后表示该模型支持工具调用。"),
            )
        )
        val samplingCard = AgentFormUi.sectionCard(
            "Thinking 与采样",
            "控制 Anthropic 的 thinking 模式和采样参数。",
            AgentFormUi.verticalStack(
                AgentFormUi.twoColumnGrid(
                    AgentFormUi.fieldTile("思考模式", thinkingCombo, "控制 Anthropic 模型的 thinking 行为。"),
                    AgentFormUi.fieldTile("预算 Tokens", budgetField, "Thinking 模式启用时可消耗的 token 预算。"),
                    AgentFormUi.fieldTile("最大输出 Tokens", maxTokensField, "限制模型本次回答的最大输出长度。"),
                    AgentFormUi.fieldTile("温度", temperatureField, "控制输出随机性，值越高越发散。"),
                    AgentFormUi.fieldTile("Top P", topPField, "控制核采样范围。"),
                    AgentFormUi.fieldTile("Top K", topKField, "限制每步采样候选 token 数量。"),
                    AgentFormUi.fieldTile("服务层级", serviceTierCombo, "控制 Anthropic 服务层级策略。"),
                    AgentFormUi.fieldTile("输出强度", outputEffortCombo, "控制结构化输出阶段的努力级别。"),
                ),
                AgentFormUi.fieldTile("停止序列", JBScrollPane(stopArea), "配置一个或多个停止输出的序列。"),
            )
        )
        val metadataCard = AgentFormUi.sectionCard(
            "地域与结构化输出",
            "配置地域、用户标识以及结构化输出。",
            AgentFormUi.verticalStack(
                AgentFormUi.twoColumnGrid(
                    AgentFormUi.fieldTile("推理地域", inferenceGeoField, "指定推理地域或可用区域。"),
                    AgentFormUi.fieldTile("用户标识", metadataUserIdField, "请求中附带的用户标识。"),
                ),
                AgentFormUi.fieldTile(
                    "输出 Schema JSON",
                    JBScrollPane(outputSchemaArea),
                    "结构化输出使用的 JSON Schema 对象。"
                ),
            )
        )
        val panel = AgentFormUi.verticalStack(capabilityCard, samplingCard, metadataCard)

        val dialog = object : DialogWrapper(project, false) {
            init {
                title = "模型扩展设置 - Anthropic"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val scroll = JBScrollPane(panel)
                scroll.preferredSize = Dimension(JBUI.scale(760), JBUI.scale(680))
                return scroll
            }

            override fun doOKAction() {
                fun requireNumber(field: JBTextField, label: String, integerOnly: Boolean = false): Boolean {
                    val text = field.text.trim()
                    if (text.isBlank()) {
                        return true
                    }
                    val valid = if (integerOnly) text.toLongOrNull() != null else text.toDoubleOrNull() != null
                    if (!valid) {
                        Messages.showErrorDialog(project, "$label 必须是数字", "参数无效")
                    }
                    return valid
                }
                if (!requireNumber(maxTokensField, "Max Tokens", integerOnly = true)) return
                if (!requireNumber(temperatureField, "Temperature")) return
                if (!requireNumber(topPField, "Top P")) return
                if (!requireNumber(topKField, "Top K", integerOnly = true)) return
                val schemaText = outputSchemaArea.text.trim()
                if (schemaText.isNotEmpty()) {
                    try {
                        JsonParser.parseString(schemaText).asJsonObject
                    } catch (_: Throwable) {
                        Messages.showErrorDialog(project, "Output Schema JSON 必须是有效的 JSON 对象", "参数无效")
                        return
                    }
                }
                val thinkingMode = (thinkingCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
                val budgetText = budgetField.text.trim()
                val budgetParsed = budgetText.toIntOrNull()
                if (budgetText.isNotBlank() && budgetParsed == null) {
                    Messages.showErrorDialog(project, "预算 Tokens 必须是数字", "参数无效")
                    return
                }
                val budgetValue = budgetParsed ?: 0
                if (thinkingMode == "enabled") {
                    if (budgetValue <= 0) {
                        Messages.showErrorDialog(project, "预算 Tokens 必须大于 0", "参数无效")
                        return
                    }
                }
                val target = AgentProviderSupport.getOrCreateModelSettings(provider, model)
                target.streamingEnabled = streamingCheck.isSelected
                target.chatModeEnabled = true
                target.responsesModeEnabled = false
                target.modelCapabilities = selectedCapabilityIds(capabilitySelection).apply {
                    if (toolCallingCheck.isSelected) add("tool_calling")
                }
                target.anthropicThinkingMode = thinkingMode
                target.anthropicThinkingBudgetTokens = if (thinkingMode == "enabled") budgetValue else 0
                target.anthropicMaxTokens = maxTokensField.text.trim()
                target.anthropicTemperature = temperatureField.text.trim()
                target.anthropicTopP = topPField.text.trim()
                target.anthropicTopK = topKField.text.trim()
                target.anthropicStopSequences = stopArea.text.trim()
                target.anthropicServiceTier = (serviceTierCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
                target.anthropicInferenceGeo = inferenceGeoField.text.trim()
                target.anthropicMetadataUserId = metadataUserIdField.text.trim()
                target.anthropicOutputEffort = (outputEffortCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
                target.anthropicOutputSchemaJson = outputSchemaArea.text.trim()
                super.doOKAction()
            }
        }
        if (dialog.showAndGet()) {
            refreshConversationModeSelector(currentSession?.state?.conversationMode)
            refreshAttachmentDrafts()
            updateStatus()
            updateToolbars()
        }
    }

    private fun refreshModelSelector(selected: String?) {
        updatingModelSelection = true
        modelSelector.removeAllItems()
        val provider = resolveSelectedProvider()
        val candidate = selected?.trim().orEmpty()
        if (provider == null) {
            if (candidate.isNotBlank()) {
                modelSelector.addItem(candidate)
                modelSelector.selectedItem = candidate
                modelSelector.editor.item = candidate
            } else {
                modelSelector.selectedItem = ""
                modelSelector.editor.item = ""
            }
            updatingModelSelection = false
            updateModelSettingsAction()
            return
        }
        val models = ensureModelList(provider)
        val active = provider.activeModel.trim()
        if (active.isNotBlank() && !models.contains(active)) {
            models.add(0, active)
        }
        if (candidate.isNotBlank() && !models.contains(candidate)) {
            models.add(0, candidate)
        }
        val resolved = when {
            candidate.isNotBlank() -> candidate
            active.isNotBlank() -> active
            currentSession?.model?.trim()?.isNotBlank() == true -> currentSession?.model?.trim().orEmpty()
            models.isNotEmpty() -> models.first()
            else -> ""
        }
        models.forEach { modelSelector.addItem(it) }
        modelSelector.selectedItem = resolved
        modelSelector.editor.item = resolved
        updatingModelSelection = false
        updateModelSettingsAction()

    }

    private fun refreshConversationModeSelector(selected: String?) {
        updatingConversationModeSelection = true
        conversationModeSelector.removeAllItems()
        val provider = resolveSelectedProvider()
        val model = currentSession?.model?.trim().orEmpty().ifBlank { resolveDefaultModel(provider) }
        val settings = if (provider != null && model.isNotBlank()) AgentProviderSupport.findModelSettings(
            provider,
            model
        ) else null
        val availableModes = AgentConversationModeSupport.availableModes(settings?.responsesModeEnabled == true)
        availableModes.forEach { conversationModeSelector.addItem(it) }
        val requested = AgentConversationMode.fromId(selected)
        val resolved = availableModes.firstOrNull { it == requested } ?: availableModes.first()
        conversationModeSelector.selectedItem = resolved
        currentSession?.state?.conversationMode = resolved.id
        updatingConversationModeSelection = false
    }

    private fun refreshPermissionScopeSelector(selected: String?) {
        updatingPermissionScopeSelection = true
        permissionScopeSelector.removeAllItems()
        AgentToolPermissionScope.entries.forEach { permissionScopeSelector.addItem(it) }
        val resolved = AgentToolPermissionScope.fromId(selected)
        permissionScopeSelector.selectedItem = resolved
        currentSession?.state?.runtime?.permissionScope = resolved.id
        updatingPermissionScopeSelection = false
    }

    private fun refreshApprovalPolicySelector(selected: String?) {
        updatingApprovalPolicySelection = true
        approvalPolicySelector.removeAllItems()
        AgentToolApprovalPolicy.entries.forEach { approvalPolicySelector.addItem(it) }
        val resolved = AgentToolApprovalPolicy.fromId(selected)
        approvalPolicySelector.selectedItem = resolved
        currentSession?.state?.runtime?.approvalPolicy = resolved.id
        updatingApprovalPolicySelection = false
    }

    private fun requestModelList(
        provider: AgentProviderState,
        showError: Boolean,
        onLoaded: ((List<String>) -> Unit)? = null
    ) {
        val providerId = provider.id
        if (onLoaded != null) {
            val listeners = modelLoadListeners.getOrPut(providerId) { mutableListOf() }
            listeners.add(onLoaded)
        }
        if (!modelLoadInFlight.add(providerId)) {
            return
        }
        val apiKey = provider.apiKey.trim()
        if (apiKey.isBlank()) {
            modelLoadInFlight.remove(providerId)
            if (showError) {
                project.errorNotify("模型列表", "请先在供应方配置中填写 API Key")
            }
            drainModelLoadListeners(providerId, modelCache[providerId]?.models.orEmpty())
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.listModels(provider)
            ApplicationManager.getApplication().invokeLater({
                modelLoadInFlight.remove(providerId)
                if (showError && result.errorMessage != null) {
                    project.errorNotify("模型列表", result.errorMessage)
                }
                val existing = modelCache[providerId]
                if (result.models.isNotEmpty() || existing == null) {
                    modelCache[providerId] = ModelCacheEntry(result.models, System.currentTimeMillis())
                }
                drainModelLoadListeners(providerId, modelCache[providerId]?.models.orEmpty())
                if (resolveSelectedProvider()?.id == providerId) {
                    refreshModelSelector(currentSession?.model)
                    updateStatus()
                }
            }, ModalityState.any())
        }
    }

    private fun drainModelLoadListeners(providerId: String, models: List<String>) {
        val listeners = modelLoadListeners.remove(providerId) ?: return
        listeners.forEach { it(models) }
    }

    private fun refreshProviderSelector(selectedProviderId: String?) {
        updatingProviderSelection = true
        providerSelector.removeAllItems()
        val providers = ensureProviderList()
        providers.forEach { providerSelector.addItem(it) }
        val resolvedId = selectedProviderId
            ?: currentSession?.providerId
            ?: project.pluginState().agentActiveProviderId
        val resolved = providers.firstOrNull { it.id == resolvedId } ?: providers.firstOrNull()
        providerSelector.selectedItem = resolved
        updatingProviderSelection = false
    }

    private fun refreshSystemPromptSelector(selectedPromptId: String?) {
        updatingSystemPromptSelection = true
        systemPromptSelector.removeAllItems()
        val prompts = ensureSystemPromptList()
        val defaultPrompt = prompts.first { it.id == AgentSystemPromptSupport.DEFAULT_PROMPT_ID }
        systemPromptSelector.addItem(SystemPromptOption("", defaultPrompt.name))
        AgentSystemPromptSupport.customPrompts(prompts).forEach { prompt ->
            systemPromptSelector.addItem(SystemPromptOption(prompt.id, prompt.name))
        }
        val resolvedId = AgentSystemPromptSupport.normalizeSelectedPromptId(
            selectedPromptId ?: currentSession?.state?.systemPromptId,
            prompts
        )
        val selected = (0 until systemPromptSelector.itemCount)
            .mapNotNull { systemPromptSelector.getItemAt(it) }
            .firstOrNull { it.id == resolvedId }
            ?: SystemPromptOption("", defaultPrompt.name)
        systemPromptSelector.selectedItem = selected
        currentSession?.state?.systemPromptId = resolvedId
        updatingSystemPromptSelection = false
    }

    private fun ensureSystemPromptList(): MutableList<AgentSystemPromptState> {
        val prompts = AgentSystemPromptSupport.normalizePrompts(project.pluginState().agentSystemPrompts)
        project.pluginState().agentSystemPrompts.clear()
        project.pluginState().agentSystemPrompts.addAll(prompts)
        return project.pluginState().agentSystemPrompts
    }

    private fun updateCurrentSystemPrompt(promptId: String) {
        val session = currentSession ?: return
        val prompts = ensureSystemPromptList()
        val resolvedId = AgentSystemPromptSupport.normalizeSelectedPromptId(promptId, prompts)
        if (session.state.systemPromptId == resolvedId) {
            refreshSystemPromptSelector(resolvedId)
            return
        }
        session.state.systemPromptId = resolvedId
        syncSessionSystemPrompt(session)
        client.clearSession(session.id)
        refreshSystemPromptSelector(resolvedId)
        updateStatus()
    }

    private fun ensureModelList(provider: AgentProviderState?): MutableList<String> {
        if (provider == null) {
            return mutableListOf()
        }
        AgentProviderSupport.normalizeProvider(provider)
        provider.models = provider.models.filter { it.isNotBlank() }.toMutableList()
        return provider.models
    }

    private fun ensureModelExists(provider: AgentProviderState, model: String) {
        val trimmed = model.trim().ifBlank { return }
        val models = ensureModelList(provider)
        if (!models.contains(trimmed)) {
            models.add(trimmed)
        }
    }

    private fun resolveDefaultModel(provider: AgentProviderState?): String {
        provider?.let { AgentProviderSupport.normalizeProvider(it) }
        val active = provider?.activeModel?.trim().orEmpty()
        if (active.isNotBlank()) {
            return active
        }
        val models = provider?.models?.filter { it.isNotBlank() }.orEmpty()
        return models.firstOrNull().orEmpty()
    }

    private fun ensureProviderList(): MutableList<AgentProviderState> {
        val providers = project.pluginState().agentProviders
        if (providers.isEmpty()) {
            val provider = AgentProviderState().apply {
                id = UUID.randomUUID().toString()
                name = "OpenAI"
                type = AgentProviderType.OPENAI.id
                baseUrl = AgentProviderSupport.defaultBaseUrl(AgentProviderType.OPENAI)
                apiKey = project.pluginState().agentOpenApiKey.trim()
            }
            AgentProviderSupport.normalizeProvider(provider)
            providers.add(provider)
            if (project.pluginState().agentActiveProviderId.isBlank()) {
                project.pluginState().agentActiveProviderId = provider.id
            }
        } else {
            providers.forEach { AgentProviderSupport.normalizeProvider(it) }
        }
        return providers
    }

    private fun resolveProviderForSession(state: AgentSessionState): AgentProviderState? {
        val providers = ensureProviderList()
        val existing = providers.firstOrNull { it.id == state.providerId && state.providerId.isNotBlank() }
        if (existing != null) {
            return existing
        }
        val fallback = providers.firstOrNull { it.id == project.pluginState().agentActiveProviderId }
            ?: providers.firstOrNull()
        state.providerId = fallback?.id.orEmpty()
        return fallback
    }

    private fun resolveSelectedProvider(): AgentProviderState? {
        return (providerSelector.selectedItem as? AgentProviderState)
            ?: ensureProviderList().firstOrNull()
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
        client.clearSession(session.id)
        session.renders.clear()
        session.state.renders.clear()
        session.state.draftAttachments.clear()
        resetMessages(session)
        if (session == currentSession) {
            inputArea.text = ""
            refreshAttachmentDrafts()
            renderSession(session)
        }
    }

    private fun openSessionManager() {
        object : DialogWrapper(project, false) {
            init {
                this.title = "会话管理"
                this.setSize(760, 520)
                this.init()
            }

            override fun createActions(): Array<out Action?> {
                return arrayOf()
            }

            override fun createCenterPanel(): JComponent? {
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
                list.addListSelectionListener { updateDeleteState() }
                newButton.isEnabled = !sending.get()
                updateDeleteState()

                val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    isOpaque = false
                    add(newButton)
                    add(renameButton)
                    add(deleteButton)
                }
                val content = AgentFormUi.sectionCard(
                    "会话列表",
                    "集中管理当前项目中的历史会话。",
                    JPanel(BorderLayout(0, 12)).apply {
                        isOpaque = false
                        add(buttonPanel, BorderLayout.NORTH)
                        add(JBScrollPane(list), BorderLayout.CENTER)
                    }
                )
                return JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(12)
                    add(content, BorderLayout.CENTER)
                }
            }
        }.showAndGet()
    }

    private fun openModelManager() {
        val provider = resolveSelectedProvider()
        if (provider == null) {
            project.errorNotify("模型管理", "请先选择供应方")
            return
        }
        val providerId = provider.id
        object : DialogWrapper(project, false) {
            init {
                this.title = "模型管理"
                this.setSize(760, 360)
                this.init()
            }

            override fun createActions(): Array<out Action?> {
                return arrayOf()
            }

            override fun createCenterPanel(): JComponent {
                val currentListModel = DefaultListModel<String>()
                val sdkListModel = DefaultListModel<String>()
                val sdkAllModels = mutableListOf<String>()
                val currentList = JBList(currentListModel).apply {
                    selectionMode = ListSelectionModel.SINGLE_SELECTION
                }
                val sdkList = JBList(sdkListModel).apply {
                    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                }
                val sdkSearchField = JBTextField().apply {
                    columns = 18
                    emptyText.text = "搜索模型 ID"
                    toolTipText = "通过模型 ID 进行模糊匹配"
                }
                val emptyHint = JLabel("当前模型列表为空，请从右侧选择并添加").apply {
                    foreground = JBColor.GRAY
                }

                fun toolbarAction(text: String, icon: javax.swing.Icon, action: () -> Unit): AnAction {
                    return object : AnAction({ text }, icon) {
                        override fun actionPerformed(e: AnActionEvent) {
                            action()
                        }
                    }
                }

                fun currentModelSet(): Set<String> {
                    val result = LinkedHashSet<String>()
                    for (i in 0 until currentListModel.size()) {
                        result.add(currentListModel.getElementAt(i))
                    }
                    return result
                }

                fun applySdkFilter() {
                    val query = sdkSearchField.text.trim()
                    val hidden = currentModelSet()
                    val filtered = if (query.isBlank()) {
                        sdkAllModels.filter { !hidden.contains(it) }
                    } else {
                        sdkAllModels.filter { it.contains(query, ignoreCase = true) && !hidden.contains(it) }
                    }
                    sdkListModel.clear()
                    filtered.forEach { sdkListModel.addElement(it) }
                }

                fun createToolbar(id: String, group: DefaultActionGroup, target: JComponent): JComponent {
                    val toolbar = ActionManager.getInstance().createActionToolbar(id, group, true)
                    toolbar.targetComponent = target
                    return toolbar.component
                }

                fun refreshCurrentList() {
                    currentListModel.clear()
                    ensureModelList(provider).forEach { currentListModel.addElement(it) }
                    emptyHint.isVisible = currentListModel.isEmpty
                    applySdkFilter()
                }


                fun refreshSdkList(showError: Boolean) {
                    val cached = modelCache[providerId]?.models.orEmpty()
                    sdkAllModels.clear()
                    sdkAllModels.addAll(cached)
                    applySdkFilter()
                    requestModelList(provider, showError) { models ->
                        sdkAllModels.clear()
                        sdkAllModels.addAll(models)
                        applySdkFilter()
                    }
                }

                refreshCurrentList()
                refreshSdkList(false)

                sdkSearchField.document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        applySdkFilter()
                    }
                })

                val addAction = toolbarAction("新增", AllIcons.General.Add) {
                    val input =
                        Messages.showInputDialog(project, "请输入模型名称", "新增模型", null) ?: return@toolbarAction
                    val name = input.trim()
                    if (name.isEmpty()) {
                        return@toolbarAction
                    }
                    ensureModelExists(provider, name)
                    refreshCurrentList()
                    if (provider.activeModel.isBlank()) {
                        provider.activeModel = name
                    }
                    if (currentSession?.providerId == providerId && currentSession?.model?.isBlank() == true) {
                        updateCurrentModel(name)
                    } else {
                        refreshModelSelector(currentSession?.model)
                        updateStatus()
                    }
                    currentList.setSelectedValue(name, true)
                }

                val renameAction = toolbarAction("改名", Icons.mcpSaveIcon()) {
                    val current = currentList.selectedValue ?: return@toolbarAction
                    val input = Messages.showInputDialog(project, "请输入新的模型名称", "修改模型", null, current, null)
                        ?: return@toolbarAction
                    val name = input.trim()
                    if (name.isEmpty() || name == current) {
                        return@toolbarAction
                    }
                    val models = ensureModelList(provider)
                    val index = models.indexOf(current)
                    if (index >= 0) {
                        models.removeAt(index)
                        models.add(index, name)
                    }
                    if (provider.activeModel == current) {
                        provider.activeModel = name
                    }
                    AgentProviderSupport.renameModelSettings(provider, current, name)
                    updateSessionsModelName(providerId, current, name)
                    if (currentSession?.model == current) {
                        updateCurrentModel(name)
                    } else {
                        refreshModelSelector(currentSession?.model)
                        updateStatus()
                    }
                    refreshCurrentList()
                    currentList.setSelectedValue(name, true)
                }

                val deleteAction = toolbarAction("删除", Icons.mcpDeleteIcon()) {
                    val current = currentList.selectedValue ?: return@toolbarAction
                    val confirmed =
                        Messages.showYesNoDialog(project, "确定要删除模型 \"$current\" 吗？", "删除模型", null)
                    if (confirmed != Messages.YES) {
                        return@toolbarAction
                    }
                    val models = ensureModelList(provider)
                    models.remove(current)
                    val fallback = models.firstOrNull().orEmpty()
                    if (provider.activeModel == current) {
                        provider.activeModel = fallback
                    }
                    AgentProviderSupport.removeModelSettings(provider, current)
                    if (fallback.isBlank()) {
                        updateSessionsModelName(providerId, current, "")
                        if (currentSession?.model == current) {
                            currentSession?.model = ""
                            currentSession?.state?.model = ""
                        }
                        refreshModelSelector("")
                        updateStatus()
                    } else {
                        updateSessionsModelName(providerId, current, fallback)
                        if (currentSession?.model == current) {
                            updateCurrentModel(fallback)
                        } else {
                            refreshModelSelector(currentSession?.model)
                            updateStatus()
                        }
                    }
                    refreshCurrentList()
                }

                val refreshSdkAction = toolbarAction("刷新", Icons.mcpRefreshIcon()) {
                    refreshSdkList(true)
                }

                val addFromSdkAction = toolbarAction("添加到当前列表", Icons.moveright()) {
                    val selected = sdkList.selectedValuesList.map { it.trim() }.filter { it.isNotBlank() }
                    if (selected.isEmpty()) {
                        project.infoNotify("模型管理", "请先选择右侧模型")
                        return@toolbarAction
                    }
                    val models = ensureModelList(provider)
                    var changed = false
                    selected.forEach { model ->
                        if (!models.contains(model)) {
                            models.add(model)
                            changed = true
                        }
                    }
                    if (!changed) {
                        return@toolbarAction
                    }
                    refreshCurrentList()
                    val firstAdded = selected.first()
                    if (provider.activeModel.isBlank()) {
                        provider.activeModel = firstAdded
                    }
                    if (currentSession?.providerId == providerId && currentSession?.model?.isBlank() == true) {
                        updateCurrentModel(provider.activeModel)
                    } else {
                        refreshModelSelector(currentSession?.model)
                        updateStatus()
                    }
                }

                val leftHeader = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(JLabel("当前模型列表"))
                    add(emptyHint)
                }
                val leftToolbarGroup = DefaultActionGroup().apply {
                    add(addAction)
                    add(renameAction)
                    add(deleteAction)
                }
                val leftToolbar = createToolbar("AgentModelManagerLeftToolbar", leftToolbarGroup, currentList)
                val leftPanel = AgentFormUi.sectionCard(
                    "当前模型列表",
                    "这里显示当前供应方已启用的模型。",
                    JPanel(BorderLayout(0, 10)).apply {
                        isOpaque = false
                        add(leftHeader, BorderLayout.NORTH)
                        add(JBScrollPane(currentList), BorderLayout.CENTER)
                        add(leftToolbar, BorderLayout.SOUTH)
                    }
                )
                val rightHeader = JLabel("SDK 模型列表")
                val rightHeaderPanel = JPanel(BorderLayout(6, 0)).apply {
                    isOpaque = false
                    add(rightHeader, BorderLayout.WEST)
                    add(sdkSearchField, BorderLayout.CENTER)
                }
                val rightToolbarGroup = DefaultActionGroup().apply {
                    add(refreshSdkAction)
                    add(addFromSdkAction)
                }
                val rightToolbar = createToolbar("AgentModelManagerRightToolbar", rightToolbarGroup, sdkList)
                val rightPanel = AgentFormUi.sectionCard(
                    "SDK 模型列表",
                    "从 SDK 拉取并筛选可添加到当前供应方的模型。",
                    JPanel().apply {
                        isOpaque = false
                        add(rightHeaderPanel, BorderLayout.NORTH)
                        add(JBScrollPane(sdkList), BorderLayout.CENTER)
                        add(rightToolbar, BorderLayout.SOUTH)
                    }
                )
                val content = JPanel(GridLayout(1, 2, 12, 0)).apply {
                    isOpaque = false
                    add(leftPanel)
                    add(rightPanel)
                }
                return JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(12)
                    add(content, BorderLayout.CENTER)
                }
            }
        }.showAndGet()
    }

    private fun openProviderManager() {
        val dialog = object : DialogWrapper(project, false) {
            private val panel = AgentProviderConfigPanel(project){
                client.refreshRuntimeCache(it)
            }

            init {
                title = "供应方配置"
                setSize(JBUI.scale(1000), JBUI.scale(500))
                init()
            }

            override fun createCenterPanel(): JComponent = panel.component

            override fun createActions(): Array<out Action?> = arrayOf()

            override fun dispose() {
                panel.dispose()
                super.dispose()
            }
        }
        dialog.showAndGet()
        refreshProvidersAfterChange()
    }

    private fun openSystemPromptManager() {
        val dialog = object : DialogWrapper(project, false) {
            private val panel = AgentSystemPromptConfigPanel(project,(systemPromptSelector.selectedItem as? SystemPromptOption)?.id) { refreshSystemPromptsAfterChange() }

            init {
                title = "系统提示词管理"
                setSize(JBUI.scale(1100), JBUI.scale(720))
                init()
            }

            override fun createCenterPanel(): JComponent = panel.component

            override fun createActions(): Array<out Action?> = arrayOf()

            override fun dispose() {
                panel.dispose()
                super.dispose()
            }
        }
        dialog.showAndGet()
        refreshSystemPromptsAfterChange()
    }

    private fun openSkillManager() {
        val dialog = object : DialogWrapper(project, false) {
            private val panel = AgentSkillConfigPanel(project) { refreshSkillsAfterChange() }

            init {
                title = "Skills 管理"
                setSize(JBUI.scale(1100), JBUI.scale(720))
                init()
            }

            override fun createCenterPanel(): JComponent = panel.component

            override fun createActions(): Array<out Action?> = arrayOf()

            override fun dispose() {
                panel.dispose()
                super.dispose()
            }
        }
        dialog.showAndGet()
        refreshSkillsAfterChange()
    }

    private fun openSkillSelector() {
        val allSkills = AgentSkillSupport.normalizeSkills(project.pluginState().agentSkills)
        if (allSkills.isEmpty()) {
            project.infoNotify("Skills", "当前还没有可用技能，请先导入目录或手动新增。")
            return
        }
        val session = currentSession ?: return
        val dialog = object : DialogWrapper(project, false) {
            private val checkBoxes = linkedMapOf<String, JCheckBox>()

            init {
                title = "当前会话 Skills"
                setSize(JBUI.scale(620), JBUI.scale(480))
                init()
            }

            override fun createCenterPanel(): JComponent {
                val listPanel = JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    allSkills.forEachIndexed { index, skill ->
                        if (index > 0) {
                            add(Box.createVerticalStrut(JBUI.scale(6)))
                        }
                        val checkBox = JCheckBox(skill.name, session.state.enabledSkillIds.contains(skill.id)).apply {
                            isOpaque = false
                            toolTipText = skill.description
                        }
                        checkBoxes[skill.id] = checkBox
                        add(createSessionSkillRow(skill, checkBox))
                    }
                }
                val scrollPane = JBScrollPane(listPanel).apply {
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    preferredSize = Dimension(JBUI.scale(560), JBUI.scale(300))
                    minimumSize = Dimension(JBUI.scale(420), JBUI.scale(220))
                }
                return JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(12)
                    add(scrollPane, BorderLayout.CENTER)
                }
            }

            override fun doOKAction() {
                session.state.enabledSkillIds = checkBoxes
                    .filterValues { it.isSelected }
                    .keys
                    .toMutableList()
                updateStatus()
                super.doOKAction()
            }
        }
        dialog.showAndGet()
    }

    private fun createSessionSkillRow(skill: AgentSkillState, checkBox: JCheckBox): JComponent {
        val sourceLabel = JLabel(AgentSkillSourceType.fromId(skill.sourceType).displayName).apply {
            foreground = JBColor.GRAY
            toolTipText = skill.description
        }
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground().brighter()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 1, 1, 1),
                JBUI.Borders.empty(0, 8)
            )
            toolTipText = skill.description
            minimumSize = Dimension(0, JBUI.scale(28))
            preferredSize = Dimension(0, JBUI.scale(28))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            add(checkBox, BorderLayout.WEST)
            add(sourceLabel, BorderLayout.EAST)
        }
    }

    private fun openMcpManager() {
        val dialog = object : DialogWrapper(project, false) {
            val panel = McpConfigPanel(project) { text ->
                if (text.isNotBlank()) {
                    SwingUtilities.invokeLater {
                        inputArea.append(if (inputArea.text.isBlank()) text else "\n$text")
                        inputArea.requestFocusInWindow()
                    }
                }
            }

            init {
                this.title = "MCP 配置"
                this.setSize(JBUI.scale(980), JBUI.scale(660))
                this.setResizable(true)
                Disposer.register(this.disposable) {
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

    private fun refreshProvidersAfterChange() {
        val previousId = currentSession?.providerId
        refreshProviderSelector(previousId)
        val selected = resolveSelectedProvider()
        if (currentSession != null && selected != null && currentSession?.providerId != selected.id) {
            updateCurrentProvider(selected)
        } else {
            updateStatus()
        }
    }

    private fun refreshSkillsAfterChange() {
        val normalized = AgentSkillSupport.normalizeSkills(project.pluginState().agentSkills)
        project.pluginState().agentSkills.clear()
        project.pluginState().agentSkills.addAll(normalized)
        val validIds = normalized.map { it.id }.toSet()
        project.pluginState().agentSessions.forEach { state ->
            state.enabledSkillIds = state.enabledSkillIds.filter { it in validIds }.distinct().toMutableList()
        }
        updateStatus()
        updateToolbars()
    }

    private fun refreshSystemPromptsAfterChange() {
        val normalized = AgentSystemPromptSupport.normalizePrompts(project.pluginState().agentSystemPrompts)
        project.pluginState().agentSystemPrompts.clear()
        project.pluginState().agentSystemPrompts.addAll(normalized)
        project.pluginState().agentSessions.forEach { session ->
            AgentSystemPromptSupport.syncSessionSystemPrompt(session, normalized)
            client.clearSession(session.id)
        }
        currentSession?.let { syncSessionSystemPrompt(it) }
        refreshSystemPromptSelector(currentSession?.state?.systemPromptId)
        updateStatus()
        updateToolbars()
    }

    private fun updateSessionsModelName(providerId: String, oldName: String, newName: String) {
        for (i in 0 until sessionModel.size) {
            val session = sessionModel.getElementAt(i)
            if (session.providerId == providerId && session.model == oldName) {
                session.model = newName
                session.state.model = newName
            }
        }
    }

    private fun beginRequestUi() {
        requestUiControls.applyRequestInProgress(
            requestInProgress = true,
            setActionEnabled = ::setActionEnabled,
            setInputEnabled = ::setInputEnabled
        )
        updateToolbars()
    }

    private fun finishRequestUi() {
        requestUiControls.applyRequestInProgress(
            requestInProgress = false,
            setActionEnabled = ::setActionEnabled,
            setInputEnabled = ::setInputEnabled
        )
        cancelToken = null
        sending.set(false)
        updateModelSettingsAction()
        updateToolbars()
    }

    private fun cancelCurrentRequest() {
        if (!sending.get()) {
            return
        }
        cancelToken?.cancel()
        activeRequestId = requestCounter.incrementAndGet()
        clearStreamingRequestState()
        appendMessage("系统", "已终止当前请求", collapsible = false, collapsedByDefault = false)
        finishRequestUi()
    }

    private fun isActiveRequest(requestId: Int, token: AgentClient.CancelToken?): Boolean {
        return requestId == activeRequestId && token?.isCancelled() != true
    }

    private fun resolveProjectKey(): String {
        val basePath = project.basePath?.replace("\\", "/")?.trim().orEmpty()
        return if (basePath.isNotEmpty()) basePath else project.name
    }

    private fun resolveStoredSessions(): List<AgentSessionState> {
        val all = project.pluginState().agentSessions
        val matched = all.filter { it.projectKey == projectKey }
        if (matched.isNotEmpty()) {
            return matched
        }
        val legacy = all.filter { it.projectKey.isBlank() }
        if (legacy.isNotEmpty()) {
            legacy.forEach { it.projectKey = projectKey }
            return legacy
        }
        return emptyList()
    }

    private fun resolveActiveSessionId(stored: List<AgentSessionState>): String? {
        if (stored.isEmpty()) {
            return null
        }
        val state = project.pluginState()
        val byProject = state.agentActiveSessionIdByProject[projectKey]
        if (!byProject.isNullOrBlank() && stored.any { it.id == byProject }) {
            return byProject
        }
        val legacy = state.agentActiveSessionId.takeIf { it.isNotBlank() && stored.any { session -> session.id == it } }
        if (legacy != null) {
            state.agentActiveSessionIdByProject[projectKey] = legacy
            return legacy
        }
        return null
    }

    private fun setActiveSessionId(sessionId: String) {
        val state = project.pluginState()
        state.agentActiveSessionIdByProject[projectKey] = sessionId
        state.agentActiveSessionId = sessionId
    }

    private fun buildTopBar(): JComponent {
        val sessionPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JLabel("会话: "))
            add(sessionSelector)
            val group = DefaultActionGroup().apply {
                add(quickClearAction)
                add(sessionManageAction)
                add(skillSelectAction)
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
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            viewport.background = INPUT_COMPOSER_BACKGROUND
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        inputHintLabel.foreground = UIUtil.getContextHelpForeground()
        inputHintLabel.horizontalAlignment = SwingConstants.LEFT
        val providerPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            add(JLabel("供应方: "))
            add(Box.createHorizontalStrut(6))
            add(providerSelector)
        }
        val modelPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            add(JLabel("模型: "))
            add(Box.createHorizontalStrut(6))
            add(modelSelector)
        }
        val permissionScopePanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("访问权限: "))
            add(Box.createHorizontalStrut(6))
            add(permissionScopeSelector)
        }
        val approvalPolicyPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("危险操作: "))
            add(Box.createHorizontalStrut(6))
            add(approvalPolicySelector)
        }
        val systemPromptPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("提示词: "))
            add(Box.createHorizontalStrut(6))
            add(systemPromptSelector)
        }
        val topControlBar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(systemPromptPanel)
            add(permissionScopePanel)
            add(approvalPolicyPanel)
            add(permissionHelpButton)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(inputHintLabel, BorderLayout.WEST)
        }
        val actionPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            val selectorPanel = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(providerPanel)
                add(Box.createVerticalStrut(4))
                add(modelPanel)
                if (AgentConversationModeSupport.selectorVisible()) {
                    val modePanel = JPanel().apply {
                        isOpaque = false
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        add(Box.createHorizontalGlue())
                        add(JLabel("对话模式: "))
                        add(Box.createHorizontalStrut(6))
                        add(conversationModeSelector)
                    }
                    add(Box.createVerticalStrut(4))
                    add(modePanel)
                }
            }
            add(selectorPanel, BorderLayout.NORTH)
            val sendGroup = DefaultActionGroup().apply {
                add(providerManageAction)
                add(systemPromptManageAction)
                add(modelManageAction)
                add(modelSettingsAction)
                add(skillManageAction)
                add(mcpManageAction)
                addSeparator()
                add(attachmentAction)
                add(sendAction)
                add(stopAction)
            }
            val sendToolbar = createToolbar("AgentSendToolbar", sendGroup, true, this)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                isOpaque = false
                this.add(sendToolbar)
            }, BorderLayout.SOUTH)
        }
        inputCenterPanel.removeAll()
        inputCenterPanel.add(attachmentDraftScroll, BorderLayout.NORTH)
        inputCenterPanel.add(inputScroll, BorderLayout.CENTER)
        val inputBody = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(inputCenterPanel, BorderLayout.CENTER)
            add(actionPanel, BorderLayout.EAST)
        }
        val inputCard = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = true
            background = INPUT_COMPOSER_BACKGROUND
            add(header, BorderLayout.NORTH)
            add(inputBody, BorderLayout.CENTER)
        }
        updateInputComposerChrome(inputCard, actionPanel, focused = inputArea.hasFocus())
        inputArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                updateInputComposerChrome(inputCard, actionPanel, focused = true)
            }

            override fun focusLost(e: FocusEvent) {
                updateInputComposerChrome(inputCard, actionPanel, focused = false)
            }
        })
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            isOpaque = false
            add(topControlBar, BorderLayout.NORTH)
            add(inputCard, BorderLayout.CENTER)
        }
    }

    private fun updateInputComposerChrome(card: JPanel, actionPanel: JComponent, focused: Boolean) {
        card.background = INPUT_COMPOSER_BACKGROUND
        inputArea.background = INPUT_COMPOSER_BACKGROUND
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                if (focused) INPUT_COMPOSER_FOCUS_BORDER else INPUT_COMPOSER_BORDER,
                JBUI.scale(1),
                true
            ),
            JBUI.Borders.empty(10, 12, 10, 12)
        )
        actionPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 0, 0, INPUT_COMPOSER_DIVIDER),
            JBUI.Borders.emptyLeft(12)
        )
        card.revalidate()
        card.repaint()
    }

    private fun showPermissionHelpDialog() {
        val textArea = JBTextArea(AgentToolPermissionHelp.fullText()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = UIUtil.getPanelBackground()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(8, 10)
            caretPosition = 0
        }
        val dialog = object : DialogWrapper(project, false) {
            init {
                title = "权限说明"
                init()
            }

            override fun createCenterPanel(): JComponent {
                return JBScrollPane(textArea).apply {
                    preferredSize = Dimension(JBUI.scale(640), JBUI.scale(520))
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBar.unitIncrement = UIUtil.getLineHeight(textArea)
                }
            }
        }
        dialog.show()
    }

    private fun sendMessage() {
        if (sending.get() || !isActionEnabled(sendAction) || !inputArea.isEnabled) {
            return
        }
        val session = currentSession ?: return
        val text = inputArea.text.trim()
        val attachments = session.state.draftAttachments.toList()
        if (text.isEmpty() && attachments.isEmpty()) {
            return
        }
        val provider = resolveSelectedProvider()
        if (provider == null) {
            project.errorNotify("智能体", "请先在设置中配置供应方")
            return
        }
        val apiKey = provider.apiKey.trim()
        if (apiKey.isBlank()) {
            project.errorNotify("智能体", "请先在供应方配置中填写 API Key")
            return
        }
        val providerType = AgentProviderType.fromId(provider.type)
        if (providerType == AgentProviderType.ANTHROPIC) {
            val anthropicModel = session.model.trim().ifBlank { resolveDefaultModel(provider) }
            val anthropicSettings = anthropicModel.takeIf { it.isNotBlank() }?.let {
                AgentProviderSupport.findModelSettings(provider, it)
            }
            val effectiveMaxTokens = anthropicSettings?.anthropicMaxTokens?.trim()?.toIntOrNull()
                ?: provider.maxTokens.takeIf { it > 0 }
            if (effectiveMaxTokens == null || effectiveMaxTokens <= 0) {
                project.errorNotify("智能体", "请在模型设置或供应方默认值中设置 Max Tokens")
                return
            }
        }
        val model = session.model.trim().ifBlank { resolveDefaultModel(provider) }
        if (model.isBlank()) {
            openModelManager()
            refreshModelSelector("")
            return
        }
        if (!sending.compareAndSet(false, true)) {
            return
        }
        syncSessionSystemPrompt(session)
        updateCurrentModel(model)
        updateCurrentProvider(provider)
        val maxToolIterations = project.pluginState().agentMaxToolIterations.takeIf { it > 0 } ?: 5
        val requestId = requestCounter.incrementAndGet()
        activeRequestId = requestId
        val token = AgentClient.CancelToken()
        cancelToken = token
        clearStreamingRequestState()
        SwingUtilities.invokeLater { inputArea.text = "" }
        appendMessage(
            "用户",
            AgentAttachmentPresentationSupport.userMessagePreview(text, attachments),
            collapsible = false,
            collapsedByDefault = false,
            attachments = attachments
        )
        updateSessionTitle(text.ifBlank { attachments.firstOrNull()?.name.orEmpty() })
        beginRequestUi()
        updateStatus()

        val userMessage = JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", text)
            if (attachments.isNotEmpty()) {
                add("attachments", attachmentJsonArray(attachments))
            }
        }
        session.messages.add(userMessage)
        session.state.draftAttachments.clear()
        refreshAttachmentDrafts()
        syncSessionMessages(session)

        ApplicationManager.getApplication().executeOnPooledThread {
            val toolkit = AgentScopeToolkit()
            val resolvedSkills = AgentSkillSupport.resolve(
                project.pluginState().agentSkills,
                session.state.enabledSkillIds,
                toolkit
            )
            val toolRegistry = AgentToolRegistry.build(project, resolvedSkills.selectedSkills, session.state.runtime)
            if (resolvedSkills.warnings.isNotEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    project.infoNotify("Skills", resolvedSkills.warnings.joinToString("\n"))
                    refreshSkillsAfterChange()
                }
            }
            val result = client.complete(
                session.state,
                session.messages,
                toolRegistry,
                provider,
                model,
                resolvedSkills = resolvedSkills,
                toolkit = toolkit,
                onAssistantDelta = { event ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!isActiveRequest(requestId, token)) {
                            return@invokeLater
                        }
                        renderStreamingTextEvent(
                            event = event,
                            role = "助手",
                            collapsible = false,
                            collapsedByDefault = false,
                        )
                    }
                },
                onReasoningDelta = { event ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!isActiveRequest(requestId, token)) {
                            return@invokeLater
                        }
                        renderStreamingTextEvent(
                            event = event,
                            role = "推理",
                            collapsible = true,
                            collapsedByDefault = false,
                        )
                    }
                },
                onToolCall = { event ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!isActiveRequest(requestId, token)) {
                            return@invokeLater
                        }
                        renderToolCallEvent(event)
                    }
                },
                onToolResult = { event ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!isActiveRequest(requestId, token)) {
                            return@invokeLater
                        }
                        renderToolResultEvent(event)
                    }
                },
                maxToolIterations = maxToolIterations,
                cancelToken = token
            )
            ApplicationManager.getApplication().invokeLater {
                if (!isActiveRequest(requestId, token)) {
                    return@invokeLater
                }
                clearStreamingRequestState()
                if (result.errorMessage != null) {
                    appendMessage("错误", result.errorMessage, collapsible = false, collapsedByDefault = false)
                }
                syncSessionMessages(session)
                finishRequestUi()
            }
        }
    }

    private fun appendMessage(
        role: String,
        content: String,
        collapsible: Boolean,
        collapsedByDefault: Boolean,
        attachments: List<AgentAttachmentState> = emptyList(),
    ) {
        val color = if (role == "推理") JBColor(0x6A6A6A, 0x9A9A9A) else UIUtil.getLabelForeground()
        val item = RenderItem(role, content, collapsible, collapsedByDefault, attachments = attachments.toMutableList())
        addRenderItem(item)
        val block = createMessageBlock(role, color, collapsible, collapsedByDefault, item)
        setBlockContent(block, content)
        addMessageBlock(block)
    }

    private fun renderStreamingTextEvent(
        event: AgentTextStreamEvent,
        role: String,
        collapsible: Boolean,
        collapsedByDefault: Boolean,
    ) {
        if (event.text.isEmpty()) {
            return
        }
        val block = streamingTextBlocks.getOrPut(streamingBlockKey(role)) {
            createTrackedMessageBlock(
                role = role,
                collapsible = collapsible,
                collapsedByDefault = collapsedByDefault,
            )
        }
        appendToBlock(block, event.text)
        if (role == "助手") {
            assistantBlock = block
        }
    }

    private fun streamingBlockKey(role: String): String {
        return role
    }

    private fun renderToolCallEvent(event: ToolCallStreamEvent) {
        val block = ensureToolBlock()
        val entry = ensureToolEntryCard(block, event.id, event.index, event.name, "")
        entry.state.name = event.name.ifBlank { entry.state.name }
        entry.state.arguments = event.arguments
        entry.state.status = if (entry.state.result.isNotBlank()) "已完成" else "调用中"
        updateToolBlockSummary(block)
        updateToolEntryCard(entry)
    }

    private fun renderToolResultEvent(event: ToolResultStreamEvent) {
        val block = ensureToolBlock()
        val entry = ensureToolEntryCard(block, event.id, -1, event.name, event.arguments)
        if (entry.state.arguments.isBlank() && event.arguments.isNotBlank()) {
            entry.state.arguments = event.arguments
        }
        entry.state.result += event.result
        entry.state.status = "已完成"
        updateToolBlockSummary(block)
        updateToolEntryCard(entry)
    }

    private fun ensureToolBlock(): ToolListBlock {
        return toolBlock ?: createTrackedToolBlock(
            collapsedByDefault = true,
            insertBeforeAssistant = true,
        ).also { toolBlock = it }
    }

    private fun createTrackedMessageBlock(
        role: String,
        collapsible: Boolean,
        collapsedByDefault: Boolean,
        insertBeforeAssistant: Boolean = false,
    ): MessageBlock {
        val item = RenderItem(role, "", collapsible, collapsedByDefault)
        val block = createMessageBlock(
            role,
            if (role == "推理") JBColor(0x6A6A6A, 0x9A9A9A) else UIUtil.getLabelForeground(),
            collapsible,
            collapsedByDefault,
            item
        )
        if (insertBeforeAssistant && assistantBlock != null) {
            addRenderItem(item, assistantBlock?.renderItem)
            addMessageBlock(block, assistantBlock)
        } else {
            addRenderItem(item)
            addMessageBlock(block)
        }
        if (role == "助手") {
            assistantBlock = block
        }
        return block
    }

    private fun appendToBlock(block: MessageBlock?, text: String) {
        block ?: return
        block.renderItem?.let {
            it.content += text
            it.state?.content = it.content
            setBlockContent(block, it.content, immediate = false)
        } ?: run {
            setBlockContent(block, block.rawContent + text, immediate = false)
        }
        if (block.contentPanel.isVisible && block.scrollPane != null) {
            scrollBlockContentToBottom(block.scrollPane)
        }
        scrollToBottom()
    }

    private fun clearStreamingRequestState() {
        streamingTextBlocks.values.forEach { flushPendingMarkdownRender(it) }
        assistantBlock = null
        toolBlock = null
        streamingTextBlocks.clear()
    }

    private fun addMessageBlock(block: MessageBlock, before: MessageBlock? = null) {
        addBlockComponent(block.panel, before?.panel)
    }

    private fun addBlockComponent(component: JComponent, before: JComponent? = null) {
        if (before != null) {
            val index = messageContainer.getComponentZOrder(before)
            if (index >= 0) {
                messageContainer.add(component, index)
            } else {
                messageContainer.add(component)
            }
        } else {
            messageContainer.add(component)
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

    private fun scrollBlockContentToTop(scrollPane: JScrollPane) {
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = 0
        }
    }

    private fun scrollBlockContentToBottom(scrollPane: JScrollPane) {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = (bar.maximum - bar.visibleAmount).coerceAtLeast(0)
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
//            font = font.deriveFont(font.style or Font.BOLD)
        }
        val rendersMarkdown = title == "助手"
        val contentArea = if (rendersMarkdown) {
            createMarkdownPane(textColor)
        } else {
            createPlainTextArea(textColor)
        }
        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(contentArea, BorderLayout.CENTER)
            isVisible = !collapsedByDefault
        }
        renderItem?.attachments?.takeIf { it.isNotEmpty() }?.let { attachments ->
            contentPanel.add(createMessageAttachmentPanel(attachments), BorderLayout.SOUTH)
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

        return MessageBlock(panel, contentArea, contentPanel, null, renderItem, rendersMarkdown, textColor)
    }

    private fun createPlainTextArea(textColor: java.awt.Color): JTextComponent {
        return JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            foreground = textColor
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 12, 6, 8)
            isOpaque = false
        }
    }

    private fun createMarkdownPane(textColor: java.awt.Color): JTextComponent {
        return object : JEditorPane() {
            override fun getPreferredSize(): Dimension {
                val parentWidth = parent?.width ?: 0
                if (parentWidth > 0) {
                    setSize(parentWidth, Short.MAX_VALUE.toInt())
                }
                return super.getPreferredSize()
            }
        }.apply {
            contentType = "text/html"
            isEditable = false
            isOpaque = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            foreground = textColor
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty()
            addHyperlinkListener { event ->
                if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    event.url?.let { BrowserUtil.browse(it) }
                }
            }
        }
    }

    private fun setBlockContent(block: MessageBlock, content: String, immediate: Boolean = true) {
        if (!block.rendersMarkdown) {
            block.rawContent = content
            block.textComponent.text = content
            return
        }
        block.rawContent = content
        block.pendingMarkdownContent = content
        if (immediate) {
            flushPendingMarkdownRender(block)
            return
        }
        scheduleMarkdownRender(block)
    }

    private fun scheduleMarkdownRender(block: MessageBlock) {
        val runningTimer = block.markdownRenderTimer?.takeIf { it.isRunning }
        if (runningTimer != null) {
            return
        }
        block.markdownRenderTimer = javax.swing.Timer(MARKDOWN_STREAM_RENDER_DELAY_MS) {
            flushPendingMarkdownRender(block)
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun flushPendingMarkdownRender(block: MessageBlock) {
        val content = block.pendingMarkdownContent ?: return
        block.markdownRenderTimer?.stop()
        block.markdownRenderTimer = null
        block.pendingMarkdownContent = null
        block.textComponent.text = AgentMarkdownRenderer.renderHtml(
            markdown = content,
            textColor = block.textColor,
            backgroundColor = UIUtil.getPanelBackground(),
            borderColor = JBColor.border(),
            codeBackgroundColor = UIUtil.getTextFieldBackground(),
            linkColor = JBColor(0x245DB3, 0x6A9BFF),
            fontFamily = UIUtil.getLabelFont().family,
            fontSize = UIUtil.getLabelFont().size,
        )
        block.textComponent.caretPosition = 0
        block.panel.revalidate()
        block.panel.repaint()
        if (block.contentPanel.isVisible) {
            scrollToBottom()
        }
    }

    private fun createTrackedToolBlock(
        collapsedByDefault: Boolean,
        insertBeforeAssistant: Boolean = false,
    ): ToolListBlock {
        val item = RenderItem("工具", "", collapsible = true, collapsedByDefault = collapsedByDefault)
        val block = createToolListBlock(item)
        if (insertBeforeAssistant && assistantBlock != null) {
            addRenderItem(item, assistantBlock?.renderItem)
            addBlockComponent(block.panel, assistantBlock?.panel)
        } else {
            addRenderItem(item)
            addBlockComponent(block.panel)
        }
        return block
    }

    private fun createToolListBlock(renderItem: RenderItem): ToolListBlock {
        val listPanel = JPanel(VerticalLayout(6)).apply {
            isOpaque = false
        }
        val listScroll = JBScrollPane(listPanel).apply {
            border = JBUI.Borders.empty(4, 8, 6, 8)
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = UIUtil.getLineHeight(JBTextArea())
            val visibleHeight = (UIUtil.getLineHeight(JBTextArea()) * TOOL_BLOCK_VISIBLE_LINES) + JBUI.scale(12)
            minimumSize = Dimension(0, visibleHeight)
            preferredSize = Dimension(JBUI.scale(480), visibleHeight)
            maximumSize = Dimension(Int.MAX_VALUE, visibleHeight)
        }
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        val headerLabel = JLabel("工具调用").apply {
            foreground = UIUtil.getLabelForeground()
//            font = font.deriveFont(font.style or Font.BOLD)
        }
        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(listScroll, BorderLayout.CENTER)
            isVisible = !renderItem.collapsedByDefault
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }
        val toggle = JButton(if (contentPanel.isVisible) "▼" else "▶").apply {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            margin = JBUI.insets(0)
        }
        toggle.addActionListener {
            contentPanel.isVisible = !contentPanel.isVisible
            toggle.text = if (contentPanel.isVisible) "▼" else "▶"
            if (contentPanel.isVisible) {
                scrollBlockContentToTop(listScroll)
            }
            panel.revalidate()
            panel.repaint()
        }
        header.add(toggle)
        header.add(headerLabel)
        panel.add(header, BorderLayout.NORTH)
        panel.add(contentPanel, BorderLayout.CENTER)
        return ToolListBlock(panel, contentPanel, listPanel, listScroll, headerLabel, renderItem, linkedMapOf())
    }


    private fun updateToolEntryCard(card: ToolEntryCard) {
        card.titleLabel.text = buildToolEntryTitle(card.state)
    }


    private fun buildToolEntryTitle(state: AgentToolRenderEntryState): String {
        val name = state.name.ifBlank { "未命名工具" }
        val timestamp = if (state.startedAt > 0) formatToolStartedAt(state.startedAt) else "--:--:--"
        val status = state.status.ifBlank { "调用中" }
        return "#${state.index + 1} $name · $timestamp · $status"
    }

    private fun formatToolStartedAt(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    private fun ensureToolEntryCard(
        block: ToolListBlock,
        toolId: String,
        index: Int,
        name: String,
        arguments: String,
    ): ToolEntryCard {
        block.entriesById[toolId]?.let { existing ->
            if (existing.state.name.isBlank() && name.isNotBlank()) {
                existing.state.name = name
            }
            if (existing.state.arguments.isBlank() && arguments.isNotBlank()) {
                existing.state.arguments = arguments
            }
            return existing
        }
        val renderState = block.renderItem.state ?: AgentRenderState().also { block.renderItem.state = it }
        val state = AgentToolRenderEntryState().apply {
            id = toolId
            this.index = if (index >= 0) index else renderState.toolEntries.size
            this.name = name
            startedAt = System.currentTimeMillis()
            status = "调用中"
            this.arguments = arguments
        }
        renderState.toolEntries.add(state)
        val entryCard = createToolEntryCard(state)
        block.entriesById[toolId] = entryCard
        block.listPanel.add(entryCard.panel)
        block.listPanel.revalidate()
        block.listPanel.repaint()
        if (block.contentPanel.isVisible) {
            scrollBlockContentToBottom(block.scrollPane)
        }
        return entryCard
    }

    private fun createToolEntryCard(state: AgentToolRenderEntryState): ToolEntryCard {
        val titleLabel = JLabel().apply {
            foreground = UIUtil.getLabelForeground()
//            font = font.deriveFont(font.style or Font.BOLD)
        }
        val previewButton = JButton("查看").apply {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            margin = JBUI.insets(0)
            toolTipText = "查看参数和返回值"
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(titleLabel)
            add(previewButton)
        }
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4)
            )
            add(header, BorderLayout.NORTH)
        }
        previewButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    showToolDetailPopup(ToolEntryCard(panel, titleLabel, previewButton, state), previewButton, e)
                }
            }
        })
        return ToolEntryCard(panel, titleLabel, previewButton, state).also {
            updateToolEntryCard(it)
        }
    }

    private fun showToolDetailPopup(card: ToolEntryCard, anchor: JComponent, e: MouseEvent) {
        val resultText = runCatching {
            gson.toJson(JsonParser.parseString(card.state.result))
        }.getOrElse { card.state.result }
        val argumentText = runCatching {
            gson.toJson(JsonParser.parseString(card.state.arguments))
        }.getOrElse { card.state.arguments }
        val argumentField = createJsonViewer(argumentText)
        val resultField = createJsonViewer(resultText)
        val content = JPanel(GridLayout(2, 1, 0, JBUI.scale(6))).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8)
            add(createToolJsonSection("参数", argumentField, 60))
            add(createToolJsonSection("返回值", resultField, 200))
        }
        JBPopupFactory.getInstance().createComponentPopupBuilder(content, argumentField)
            .setMovable(true)
            .setResizable(true)
            .setFocusable(true)
            .setRequestFocus(true)
            .setTitle(card.titleLabel.text)
            .createPopup()
            .show(RelativePoint(e))
    }


    private fun createToolJsonSection(title: String, field: LanguageTextField, height: Int): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(JLabel(title).apply {
                foreground = UIUtil.getContextHelpForeground()
//                font = font.deriveFont(font.style or Font.BOLD, font.size2D - 1f)
            }, BorderLayout.NORTH)
            add(field.apply {
                minimumSize = Dimension(0, height)
                preferredSize = Dimension(JBUI.scale(500), height)
                maximumSize = Dimension(Int.MAX_VALUE, height)
            }, BorderLayout.CENTER)
        }
    }

    private fun createJsonViewer(text: String = ""): LanguageTextField {
        val jsonLanguage = Language.findLanguageByID("JSON5") ?: Language.ANY
        return object : LanguageTextField(jsonLanguage, project, text, false) {
            override fun createEditor(): EditorEx {
                val editor = super.createEditor()
                editor.isViewer = true
                editor.setBorder(null)
                editor.scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                val settings = editor.settings
                settings.additionalLinesCount = 0
                settings.additionalColumnsCount = 1
                settings.isLineNumbersShown = true
                settings.isLineMarkerAreaShown = false
                settings.isIndentGuidesShown = false
                settings.isFoldingOutlineShown = false
                settings.isRightMarginShown = false
                settings.isUseSoftWraps = true
                return editor
            }
        }.apply {
            isEnabled = true
            border = JBUI.Borders.customLine(JBColor.border(), 1)
        }
    }


    private fun updateToolBlockSummary(block: ToolListBlock) {
        val count = block.entriesById.size
        val completed = block.entriesById.values.count { it.state.status == "已完成" }
        val summary = if (count <= 0) {
            "工具调用"
        } else {
            "工具调用 ($completed/$count)"
        }
        block.headerLabel.text = summary
        block.renderItem.content = summary
        block.renderItem.state?.content = summary
    }

    private fun resetMessages(session: ChatSession) {
        session.messages.clear()
        syncSessionSystemPrompt(session)
    }

    private fun updateStatus() {
        val provider = resolveSelectedProvider()
        val providerType = AgentProviderType.fromId(provider?.type)
        val apiKey = provider?.apiKey?.trim().orEmpty()
        val baseUrl = provider?.baseUrl?.trim()
            ?.ifBlank { AgentProviderSupport.defaultBaseUrl(providerType) }
            .orEmpty()
        val model = currentSession?.model?.trim()
            ?.ifBlank { resolveDefaultModel(provider) }
            ?: resolveDefaultModel(provider)
        val modelText = if (model.isBlank()) "未选择" else model
        val conversationMode = AgentConversationMode.fromId(currentSession?.state?.conversationMode).displayName
        val promptName = AgentSystemPromptSupport.resolvePromptName(
            ensureSystemPromptList(),
            currentSession?.state?.systemPromptId
        )
        val modelSettings = if (provider != null && model.isNotBlank()) AgentProviderSupport.findModelSettings(
            provider,
            model
        ) else null
        val streamText = if (modelSettings?.streamingEnabled != false) "流式" else "非流式"
        val mcpServers = McpSupport.safeServers(project.pluginState().agentMcpServers)
        val enabledCount = mcpServers.count { it.enabled }
        val mcpStatus = if (project.pluginState().agentMcpEnabled) {
            "MCP: $enabledCount/${mcpServers.size}"
        } else {
            "MCP: 未启用"
        }
        val skillStatus = "Skills: ${currentSession?.state?.enabledSkillIds?.size ?: 0}"
        val promptStatus = "提示词: $promptName"
        statusLabel.text = if (provider == null) {
            "供应方未配置 | 模型: $modelText | 模式: $conversationMode | $promptStatus | $skillStatus | $mcpStatus"
        } else if (apiKey.isBlank()) {
            "API Key 未配置 | 供应方: ${provider.name} (${providerType.displayName}) | Base URL: $baseUrl | 模型: $modelText | 模式: $conversationMode | $streamText | $promptStatus | $skillStatus | $mcpStatus"
        } else {
            "供应方: ${provider.name} (${providerType.displayName}) | Base URL: $baseUrl | 模型: $modelText | 模式: $conversationMode | $streamText | $promptStatus | $skillStatus | $mcpStatus"
        }
    }

    fun refreshStatus() {
        updateStatus()
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
        refreshAttachmentDrafts()
        updateToolbars()
    }

    private fun resolveCurrentModelSettings(): AgentModelSettings? {
        val provider = resolveSelectedProvider() ?: return null
        val model = currentSession?.model?.trim().orEmpty().ifBlank { resolveDefaultModel(provider) }
        if (model.isBlank()) {
            return null
        }
        return AgentProviderSupport.findModelSettings(provider, model)
    }

    private fun chooseAttachments() {
        val descriptor = FileChooserDescriptor(true, false, true, true, false, true).apply {
            title = "选择附件"
            isForcedToUseIdeaFileChooser = true
        }
        val files = FileChooser.chooseFiles(descriptor, project, null)
        if (files.isEmpty()) {
            return
        }
        addAttachmentFiles(files.map { File(it.path) })
    }

    private fun addAttachmentsFromTransferable(transferable: Transferable): Boolean {
        val files = AgentAttachmentClipboardSupport.extractFiles(transferable)
        return when {
            files.isNotEmpty() -> {
                addAttachmentFiles(files)
            }

            transferable.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
                val image = transferable.getTransferData(DataFlavor.imageFlavor) as? Image ?: return false
                val file = saveClipboardImage(image) ?: return false
                addAttachmentFiles(listOf(file))
            }

            else -> addAttachmentFiles(AgentAttachmentClipboardSupport.extractFiles(transferable))
        }
    }

    private fun saveClipboardImage(image: Image): File? {
        val buffered = if (image is BufferedImage) {
            image
        } else {
            BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).apply {
                val graphics = createGraphics()
                try {
                    graphics.drawImage(image, 0, 0, null)
                } finally {
                    graphics.dispose()
                }
            }
        }
        return runCatching {
            File.createTempFile("agent-attachment-", ".png").apply {
                deleteOnExit()
                ImageIO.write(buffered, "png", this)
            }
        }.getOrNull()
    }

    private fun addAttachmentFiles(files: List<File>): Boolean {
        val session = currentSession ?: return false
        if (files.isEmpty()) {
            return false
        }
        val allowed = AgentInputCapabilitySupport.allowedAttachmentKinds(resolveCurrentModelSettings())
        if (allowed.isEmpty()) {
            project.infoNotify("附件", "当前模型未启用附件能力")
            return false
        }
        var added = false
        files.filter { it.exists() && it.isFile }.forEach { file ->
            val draft = AgentAttachmentSupport.normalize(
                AgentAttachmentState(
                    name = file.name,
                    path = file.absolutePath,
                )
            )
            if (draft.kind !in allowed) {
                return@forEach
            }
            if (session.state.draftAttachments.none { it.path == draft.path }) {
                session.state.draftAttachments.add(draft)
                added = true
            }
        }
        if (!added) {
            val alreadyAddFiles = session.state.draftAttachments.map { it.path }.toSet()
            files.filter { !alreadyAddFiles.contains(it.path) || alreadyAddFiles.isEmpty() }.map { if(it.isFile){"文件: ${it.name},路径: ${it.path} 不支持"}else{"文件夹: ${it.name},路径: ${it.path} 不支持"} }
                .joinToString { "\n" }.ifNotBlank {
                    project.infoNotify("附件", "没有可添加的附件，或附件类型当前模型不支持")
                }
        }
        refreshAttachmentDrafts()
        return added
    }

    private fun refreshAttachmentDrafts() {
        attachmentDraftPanel.removeAll()
        val session = currentSession
        val drafts = session?.state?.draftAttachments.orEmpty()
        val visible = AgentInputCapabilitySupport.attachmentButtonVisible(resolveCurrentModelSettings())
        attachmentDraftScroll.isVisible = visible && drafts.isNotEmpty()
        drafts.forEachIndexed { index, draft ->
            attachmentDraftPanel.add(createAttachmentChip(draft))
            if (index < drafts.lastIndex) {
                attachmentDraftPanel.add(Box.createHorizontalStrut(JBUI.scale(6)))
            }
        }
        attachmentDraftPanel.revalidate()
        attachmentDraftPanel.repaint()
        attachmentDraftScroll.revalidate()
        attachmentDraftScroll.repaint()
        inputCenterPanel.revalidate()
        inputCenterPanel.repaint()
        content?.revalidate()
        content?.repaint()
        SwingUtilities.invokeLater {
            val scrollBar = attachmentDraftScroll.horizontalScrollBar
            val maxValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
            if (drafts.isEmpty()) {
                scrollBar.value = 0
            } else if (scrollBar.value > maxValue) {
                scrollBar.value = maxValue
            }
        }
        updateToolbars()
    }

    private fun createAttachmentChip(draft: AgentAttachmentState): JComponent {
        return AgentAttachmentChipUi.createDraftChip(
            attachment = draft,
            onOpen = { openAttachment(draft) },
            onRemove = {
                currentSession?.state?.draftAttachments?.removeIf { it.id == draft.id }
                refreshAttachmentDrafts()
            }
        )
    }

    private fun createMessageAttachmentPanel(attachments: List<AgentAttachmentState>): JComponent {
        val content = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(0, 12, 2, 8)
            attachments.forEachIndexed { index, attachment ->
                add(createHistoryAttachmentChip(attachment))
                if (index < attachments.lastIndex) {
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                }
            }
        }
        return AgentAttachmentChipUi.createHorizontalStrip(content).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun createHistoryAttachmentChip(draft: AgentAttachmentState): JComponent {
        return AgentAttachmentChipUi.createHistoryChip(
            attachment = draft,
            onOpen = { openAttachment(draft) }
        )
    }

    private fun openAttachment(attachment: AgentAttachmentState) {
        val path = attachment.path.trim()
        if (path.isBlank()) {
            project.infoNotify("附件", "附件没有可打开的本地路径")
            return
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path))
        if (file == null) {
            project.errorNotify("附件", "未找到文件: $path")
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun attachmentJsonArray(attachments: List<AgentAttachmentState>): com.google.gson.JsonArray {
        return com.google.gson.JsonArray().apply {
            attachments.forEach { attachment ->
                add(JsonObject().apply {
                    addProperty("id", attachment.id)
                    addProperty("name", attachment.name)
                    addProperty("path", attachment.path)
                    addProperty("mimeType", attachment.mimeType)
                    addProperty("size", attachment.size)
                    addProperty("kind", attachment.kind)
                    addProperty("deliveryMode", attachment.deliveryMode)
                })
            }
        }
    }

    private fun createCapabilitySelection(
        selectedValues: Collection<String>,
        options: List<AgentCapabilityOption>,
    ): CapabilitySelection {
        val selected = selectedValues.toSet()
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
        }
        val checkBoxes = linkedMapOf<String, JCheckBox>()
        options.forEach { option ->
            val checkBox = JCheckBox(option.label).apply {
                isOpaque = false
                isSelected = selected.contains(option.id)
                toolTipText = option.description
            }
            checkBoxes[option.id] = checkBox
            panel.add(checkBox)
        }
        return CapabilitySelection(panel, checkBoxes)
    }

    private fun selectedCapabilityIds(vararg selections: CapabilitySelection): MutableList<String> {
        return selections.asSequence()
            .flatMap { it.checkBoxes.asSequence() }
            .filter { (_, checkBox) -> checkBox.isSelected }
            .map { (id, _) -> id }
            .distinct()
            .toMutableList()
    }

    private data class RenderItem(
        val role: String,
        var content: String,
        val collapsible: Boolean,
        val collapsedByDefault: Boolean,
        val attachments: MutableList<AgentAttachmentState> = mutableListOf(),
        var state: AgentRenderState? = null,
    )

    private data class SystemPromptOption(val id: String, val label: String)

    private data class ModelSettingOption(val label: String, val value: String)

    private data class CapabilitySelection(
        val panel: JPanel,
        val checkBoxes: Map<String, JCheckBox>,
    )

    private class PasteAttachmentAction(
        private val onFiles: (List<File>) -> Unit,
        private val onImage: (Image) -> Unit,
        private val onTextFallback: (String) -> Unit,
    ) : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val transferable = CopyPasteManager.getInstance().contents ?: return
            val files = AgentAttachmentClipboardSupport.extractFiles(transferable)
            when {
                files.isNotEmpty() -> onFiles(files)
                transferable.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
                    val image = transferable.getTransferData(DataFlavor.imageFlavor) as? Image ?: return
                    onImage(image)
                }
                transferable.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                    val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return
                    onTextFallback(text)
                }
            }
        }
    }

    private class ChatSession(
        val id: String,
        var title: String,
        var autoTitle: Boolean,
        var model: String,
        var providerId: String,
        val messages: MutableList<JsonObject>,
        val renders: MutableList<RenderItem>,
        val state: AgentSessionState,
    ) {
        override fun toString(): String = title
    }

    private data class MessageBlock(
        val panel: JComponent,
        val textComponent: JTextComponent,
        val contentPanel: JComponent,
        val scrollPane: JScrollPane?,
        val renderItem: RenderItem?,
        val rendersMarkdown: Boolean,
        val textColor: java.awt.Color,
        var pendingMarkdownContent: String? = null,
        var markdownRenderTimer: javax.swing.Timer? = null,
        var rawContent: String = "",
    )

    private data class ToolListBlock(
        val panel: JComponent,
        val contentPanel: JComponent,
        val listPanel: JPanel,
        val scrollPane: JScrollPane,
        val headerLabel: JLabel,
        val renderItem: RenderItem,
        val entriesById: MutableMap<String, ToolEntryCard>,
    )

    private data class ToolEntryCard(
        val panel: JComponent,
        val titleLabel: JLabel,
        val previewButton: JButton,
        val state: AgentToolRenderEntryState,
    )
}
