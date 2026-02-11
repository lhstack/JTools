package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.infoNotify
import com.lhstack.tools.plugins.pluginState
import org.jdesktop.swingx.VerticalLayout
import java.awt.*
import java.awt.event.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

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
    private val modelSettingsAction = createAction("模型扩展设置", Icons.modelTuningIcon()) { openModelSettingsDialog() }
    private val providerManageAction = createAction("供应方管理", Icons.providerConfigIcon()) { openProviderManager() }
    private val mcpManageAction = createAction("MCP 配置", Icons.mcpConfigIcon()) { openMcpManager() }
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
    private val providerModel = DefaultComboBoxModel<AgentProviderState>()
    private val sessionSelector = ComboBox<ChatSession>()
    private val providerSelector = ComboBox<AgentProviderState>()
    private val modelSelector = ComboBox<String>()
    private val comboFixedWidth = JBUI.scale(180)
    private val projectKey = resolveProjectKey()
    private val client = AgentClient()
    private val sending = AtomicBoolean(false)
    private val requestCounter = AtomicInteger(0)
    @Volatile private var activeRequestId = 0
    private var cancelToken: AgentClient.CancelToken? = null
    private var currentSession: ChatSession? = null
    private var updatingSessionSelection = false
    private var updatingProviderSelection = false
    private var updatingModelSelection = false

    private var assistantBlock: MessageBlock? = null
    private var reasoningBlock: MessageBlock? = null
    private var suppressToolMarkup = false
    private val modelCache = mutableMapOf<String, ModelCacheEntry>()
    private val modelLoadInFlight = mutableSetOf<String>()
    private val modelLoadListeners = mutableMapOf<String, MutableList<(List<String>) -> Unit>>()

    private data class ModelCacheEntry(val models: List<String>, val loadedAt: Long)

    init {
        setupChatContainer()
        setupInputArea()
        setupSessionSelector()
        setupProviderSelector()
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


    private fun configureComboBox(
        comboBox: ComboBox<*>,
        fixedWidth: Int,
        textProvider: (Any?) -> String,
        ellipsizeEditor: Boolean = false
    ) {
        applyFixedWidth(comboBox, fixedWidth)
        comboBox.renderer = createEllipsisRenderer(comboBox, textProvider)
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
        textProvider: (Any?) -> String
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
                label.toolTipText = fullText.takeIf { it.isNotBlank() }
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
        if (state.projectKey.isBlank()) {
            state.projectKey = projectKey
        }
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
        updatingSessionSelection = true
        sessionSelector.selectedItem = session
        updatingSessionSelection = false
        val resolvedProvider = resolveProviderForSession(session.state)
        session.providerId = resolvedProvider?.id.orEmpty()
        refreshProviderSelector(session.providerId)
        val resolvedModel = session.model.trim().ifBlank { resolveDefaultModel(resolvedProvider) }
        session.model = resolvedModel
        session.state.model = resolvedModel
        if (resolvedProvider != null && resolvedModel.isNotBlank()) {
            resolvedProvider.activeModel = resolvedModel
            ensureModelExists(resolvedProvider, resolvedModel)
        }
        refreshModelSelector(resolvedModel)
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
        updateStatus()
        updateModelSettingsAction()
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
        val schemaStrictCheck = JCheckBox("Strict").apply { isSelected = settings?.openAiResponseFormatSchemaStrict == true }
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

        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4)
            fill = GridBagConstraints.HORIZONTAL
        }
        var row = 0
        fun addRow(label: String, component: JComponent) {
            c.gridx = 0
            c.gridy = row
            c.weightx = 0.0
            c.gridwidth = 1
            panel.add(JLabel(label), c)
            c.gridx = 1
            c.weightx = 1.0
            panel.add(component, c)
            row++
        }

        addRow("模型:", JLabel(model))
        addRow("思考强度:", effortCombo)
        addRow("Temperature:", temperatureField)
        addRow("Top P:", topPField)
        addRow("Max Tokens:", maxTokensField)
        addRow("Max Completion Tokens:", maxCompletionTokensField)
        addRow("Presence Penalty:", presencePenaltyField)
        addRow("Frequency Penalty:", frequencyPenaltyField)
        addRow("Seed:", seedField)
        addRow("Stop Sequences:", JBScrollPane(stopArea))
        addRow("Response Format:", responseFormatCombo)
        addRow("Schema Name:", schemaNameField)
        addRow("Schema Desc:", schemaDescField)
        addRow("Schema Strict:", schemaStrictCheck)
        addRow("Schema JSON:", JBScrollPane(schemaArea))
        addRow("Logprobs:", logprobsCombo)
        addRow("Top Logprobs:", topLogprobsField)
        addRow("Tool Choice:", toolChoiceCombo)
        addRow("Parallel Tool Calls:", parallelToolCallsCombo)

        val dialog = object : DialogWrapper(project, false) {
            init {
                title = "模型扩展设置 - OpenAI"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val scroll = JBScrollPane(panel)
                scroll.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(520))
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
                if (logprobsValue == "true" && !requireNumber(topLogprobsField, "Top Logprobs", integerOnly = true)) return
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
                target.openAiParallelToolCalls = (parallelToolCallsCombo.selectedItem as? ModelSettingOption)?.value.orEmpty()
                super.doOKAction()
            }
        }
        dialog.showAndGet()
    }

    private fun openAnthropicModelSettingsDialog(provider: AgentProviderState, model: String) {
        val settings = AgentProviderSupport.findModelSettings(provider, model)
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

        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4)
            fill = GridBagConstraints.HORIZONTAL
        }
        var row = 0
        fun addRow(label: String, component: JComponent) {
            c.gridx = 0
            c.gridy = row
            c.weightx = 0.0
            c.gridwidth = 1
            panel.add(JLabel(label), c)
            c.gridx = 1
            c.weightx = 1.0
            panel.add(component, c)
            row++
        }

        addRow("模型:", JLabel(model))
        addRow("思考模式:", thinkingCombo)
        addRow("预算 Tokens:", budgetField)
        addRow("Max Tokens:", maxTokensField)
        addRow("Temperature:", temperatureField)
        addRow("Top P:", topPField)
        addRow("Top K:", topKField)
        addRow("Stop Sequences:", JBScrollPane(stopArea))
        addRow("Service Tier:", serviceTierCombo)
        addRow("Inference Geo:", inferenceGeoField)
        addRow("Metadata User ID:", metadataUserIdField)
        addRow("Output Effort:", outputEffortCombo)
        addRow("Output Schema JSON:", JBScrollPane(outputSchemaArea))

        val dialog = object : DialogWrapper(project, false) {
            init {
                title = "模型扩展设置 - Anthropic"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val scroll = JBScrollPane(panel)
                scroll.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(520))
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
        dialog.showAndGet()
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
        session.renders.clear()
        session.state.renders.clear()
        resetMessages(session)
        if (session == currentSession) {
            renderSession(session)
        }
    }

    private fun openSessionManager() {
        object:DialogWrapper(project,false) {
            init {
                this.title = "会话管理"
                this.setSize(420,320)
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

                val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
                    add(newButton)
                    add(renameButton)
                    add(deleteButton)
                }
                return JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(8)
                    add(JBScrollPane(list), BorderLayout.CENTER)
                    add(buttonPanel, BorderLayout.SOUTH)
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
        object:DialogWrapper(project,false){
            init {
                this.title = "模型管理"
                this.setSize(760,360)
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
                    val input = Messages.showInputDialog(project, "请输入模型名称", "新增模型", null) ?: return@toolbarAction
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
                    val confirmed = Messages.showYesNoDialog(project, "确定要删除模型 \"$current\" 吗？", "删除模型", null)
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
                val leftPanel = JPanel(BorderLayout()).apply {
                    add(leftHeader, BorderLayout.NORTH)
                    add(JBScrollPane(currentList), BorderLayout.CENTER)
                    add(leftToolbar, BorderLayout.SOUTH)
                }
                val rightHeader = JLabel("SDK 模型列表")
                val rightHeaderPanel = JPanel(BorderLayout(6, 0)).apply {
                    add(rightHeader, BorderLayout.WEST)
                    add(sdkSearchField, BorderLayout.CENTER)
                }
                val rightToolbarGroup = DefaultActionGroup().apply {
                    add(refreshSdkAction)
                    add(addFromSdkAction)
                }
                val rightToolbar = createToolbar("AgentModelManagerRightToolbar", rightToolbarGroup, sdkList)
                val rightPanel = JPanel(BorderLayout()).apply {
                    add(rightHeaderPanel, BorderLayout.NORTH)
                    add(JBScrollPane(sdkList), BorderLayout.CENTER)
                    add(rightToolbar, BorderLayout.SOUTH)
                }
                return JPanel(GridLayout(1, 2, 12, 0)).apply {
                    border = JBUI.Borders.empty(8)
                    add(leftPanel)
                    add(rightPanel)
                }
            }
        }.showAndGet()
    }

    private fun openProviderManager() {
        val dialog = object : DialogWrapper(project, false) {
            private val panel = AgentProviderConfigPanel(project)

            init {
                title = "供应方配置"
                setSize(JBUI.scale(1000), JBUI.scale(520))
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

    private fun openMcpManager() {
        val dialog = object: DialogWrapper(project,false){
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
                this.setSize(JBUI.scale(920), JBUI.scale(520))
                this.setResizable(false)
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
        setActionEnabled(sendAction, false)
        setActionEnabled(stopAction, true)
        sessionSelector.isEnabled = false
        providerSelector.isEnabled = false
        modelSelector.isEnabled = false
        setActionEnabled(providerManageAction, false)
        setActionEnabled(modelManageAction, false)
        setActionEnabled(modelSettingsAction, false)
        setActionEnabled(mcpManageAction, false)
        setInputEnabled(false)
        updateToolbars()
    }

    private fun finishRequestUi() {
        setActionEnabled(sendAction, true)
        setActionEnabled(stopAction, false)
        sessionSelector.isEnabled = true
        providerSelector.isEnabled = true
        modelSelector.isEnabled = true
        setActionEnabled(providerManageAction, true)
        setActionEnabled(modelManageAction, true)
        setActionEnabled(modelSettingsAction, true)
        setActionEnabled(mcpManageAction, true)
        setInputEnabled(true)
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
        closeAssistantBlock()
        closeReasoningBlock()
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
            }
            add(selectorPanel, BorderLayout.NORTH)
            val sendGroup = DefaultActionGroup().apply {
                add(providerManageAction)
                add(modelManageAction)
                add(modelSettingsAction)
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
        if (providerType == AgentProviderType.ANTHROPIC && provider.maxTokens <= 0) {
            project.errorNotify("智能体", "请在供应方配置中设置 Max Tokens")
            return
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
        updateCurrentModel(model)
        updateCurrentProvider(provider)
        val maxToolIterations = project.pluginState().agentMaxToolIterations.takeIf { it > 0 } ?: 5
        val toolTimeoutMs = project.pluginState().agentToolTimeoutMs.takeIf { it > 0 } ?: 120_000
        val requestId = requestCounter.incrementAndGet()
        activeRequestId = requestId
        val token = AgentClient.CancelToken()
        cancelToken = token
        suppressToolMarkup = false
        SwingUtilities.invokeLater { inputArea.text = "" }
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
                provider,
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
                        if (filtered.isEmpty()) {
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
        val mcpServers = McpSupport.safeServers(project.pluginState().agentMcpServers)
        val enabledCount = mcpServers.count { it.enabled }
        val mcpStatus = if (project.pluginState().agentMcpEnabled) {
            "MCP: $enabledCount/${mcpServers.size}"
        } else {
            "MCP: 未启用"
        }
        statusLabel.text = if (provider == null) {
            "供应方未配置 | Model: $modelText | $mcpStatus"
        } else if (apiKey.isBlank()) {
            "API Key 未配置 | 供应方: ${provider.name} (${providerType.displayName}) | Base URL: $baseUrl | Model: $modelText | $mcpStatus"
        } else {
            "供应方: ${provider.name} (${providerType.displayName}) | Base URL: $baseUrl | Model: $modelText | $mcpStatus"
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

    private data class ModelSettingOption(val label: String, val value: String)

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

    private data class MessageBlock(val panel: JComponent, val textArea: JBTextArea, val renderItem: RenderItem?)
}
