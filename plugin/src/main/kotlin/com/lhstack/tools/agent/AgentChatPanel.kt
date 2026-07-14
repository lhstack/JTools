package com.lhstack.tools.agent

import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.llm.AssistantContent
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ProviderToolCall
import com.lhstack.tools.agent.model.llm.ToolCall
import com.lhstack.tools.agent.model.llm.ToolFunction
import com.lhstack.tools.agent.model.llm.ToolResult
import com.lhstack.tools.agent.model.llm.ToolResultContent
import com.lhstack.tools.agent.model.llm.UserContent
import com.lhstack.tools.agent.model.log.ModelLogService
import com.lhstack.tools.agent.model.tools.UpdateAgentDistillationTool
import com.lhstack.tools.agent.model.log.ModelRequestException
import com.lhstack.tools.agent.model.provider.AgentRuntime
import com.lhstack.tools.agent.model.provider.ModelStreamSink
import com.lhstack.tools.agent.model.provider.ToolEventSink
import com.lhstack.tools.const.Icons
import com.lhstack.tools.db.service.AgentRecord
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.ChatSessionRecord
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.ResourceConfigService
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.infoNotify
import com.intellij.lang.Language
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.LanguageTextField
import com.intellij.ui.awt.RelativePoint
import org.jdesktop.swingx.VerticalLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.ListSelectionModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTabbedPane
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter
import javax.swing.text.DefaultEditorKit
import javax.swing.text.JTextComponent

/**
 * Agent 对话面板。对话完全基于 Agent 发起：会话绑定一个 Agent，发送时经 AgentRuntime
 * 执行，历史以 model_request_logs 为唯一数据源。面板只负责编排 UI 与调用运行时，
 * 不持有任何模型/供应商状态，也不做任何旧结构兼容。
 */
class AgentChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true), com.intellij.openapi.Disposable {

    private companion object {
        const val ROLE_USER = "用户"
        const val ROLE_ASSISTANT = "助手"
        const val ROLE_REASONING = "推理"
        const val ROLE_ERROR = "错误"
        const val AUTO_TITLE = "新会话"
        val INPUT_COMPOSER_BACKGROUND = JBColor(Color(0xFFFFFF), Color(0x2B2F34))
        val INPUT_COMPOSER_BORDER = JBColor(Color(0xD3D9E2), Color(0x4E545A))
        val INPUT_COMPOSER_DIVIDER = JBColor(Color(0xE4E8EF), Color(0x43484D))
        val INPUT_COMPOSER_FOCUS_BORDER = JBColor(0x4B90FF, 0x4B90FF)
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val chatBrowser: AgentChatBrowser by lazy { AgentChatBrowser(gson, ::handleBrowserCommand, onDropFiles = ::addAttachmentFiles) }
    private val managementWindows by lazy { AgentManagementWindowManager(project, gson, ::refreshManagementData) }

    private val messageContainer = JPanel(VerticalLayout(8))
    private val chatScroll = JBScrollPane(messageContainer)
    private val inputArea = JBTextArea(3, 0)
    private val inputCenterPanel = JPanel(BorderLayout(0, 6)).apply { isOpaque = false }
    private val queueStrip = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }
    private val queueScroll = JBScrollPane(queueStrip).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        preferredSize = Dimension(0, JBUI.scale(40))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(42))
    }
    private val queuePanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
        add(queueScroll, BorderLayout.CENTER)
    }

    private val draftAttachments = mutableListOf<AgentAttachmentState>()
    private val draftAttachmentStrip = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }
    private val draftAttachmentPanel = AgentAttachmentChipUi.createHorizontalStrip(draftAttachmentStrip).apply {
        isVisible = false
    }

    private val statusLabel = JLabel()
    private val inputHintLabel = JLabel(AgentInputShortcutSupport.inputHint())
    private val sessionSelector = ComboBox<ChatSessionRecord>()
    private val agentSelector = ComboBox<AgentRecord>()

    private val actionToolbars = mutableListOf<ActionToolbar>()
    private val actionEnabledState = mutableMapOf<AnAction, Boolean>()


    private val queueOrder = AtomicLong(0)
    private val queueLock = Any()
    private val chatQueue = mutableListOf<ChatQueueItem>()
    private var currentSessionId: Long? = null
    private var renderedSessionId: Long? = null
    private var inputRestoreSequence = 0L
    private var browserInputRestore: AgentBrowserInputRestore? = null
    private var updatingSessionSelection = false
    private var updatingAgentSelection = false

    private var currentTurnView: AssistantTurnView? = null
    private val messageCards = mutableListOf<AgentChatCard>()

    private val newSessionAction = createAction("新建会话", Icons.agentSessionNewIcon()) { createSession() }
    private val clearAction = createAction("清空当前会话", Icons.agentSessionClearIcon()) { clearCurrentSession() }
    private val sessionManageAction = createAction("会话管理", Icons.agentSessionManageIcon()) { openSessionManager() }
    private val modelLogAction = createAction("模型日志", Icons.agentModelLogIcon()) { openModelLogDialog() }
    private val modelManageAction = createAction("供应商与模型", Icons.agentModelIcon()) { openModelManager() }
    private val promptManageAction = createAction("提示词管理", Icons.agentPromptIcon()) { openPromptManager() }
    private val agentManageAction = createAction("Agent 管理", Icons.agentManageIcon()) { openAgentManager() }
       private val skillManageAction = createAction("Skills 管理", Icons.agentSkillsIcon()) { openSkillManager() }
    private val globalConfigAction = createAction("全局配置", Icons.agentGlobalConfigIcon()) { openGlobalConfigManager() }
    private val attachmentAction = createAction("添加附件", Icons.agentAttachmentIcon()) { chooseAttachments() }

    init {
        setupSessionSelector()
        setupAgentSelector()
        setContent(chatBrowser.component)
        project.messageBus.connect(this).subscribe(LafManagerListener.TOPIC, LafManagerListener { syncBrowserState() })
        loadInitialData()
    }

    // -------- 选择器与输入初始化 --------

    private fun setupChatContainer() {
        messageContainer.background = UIUtil.getPanelBackground()
        messageContainer.isOpaque = true
        chatScroll.border = JBUI.Borders.customLine(JBColor.border(), 1)
        chatScroll.viewport.background = UIUtil.getPanelBackground()
        chatScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        chatScroll.verticalScrollBar.unitIncrement = 16
        chatScroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = Unit
        })
    }

    private fun setupInputArea() {
        val defaultPasteAction = inputArea.actionMap.get(DefaultEditorKit.pasteAction)
        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.margin = JBUI.insets(6)
        inputArea.background = UIUtil.getTextFieldBackground()
        inputArea.isOpaque = true
        inputArea.inputMap.put(AgentInputShortcutSupport.sendKeyStroke(), "sendMessage")
        inputArea.actionMap.put("sendMessage", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = sendMessage()
        })
        bindPasteAttachment(inputArea) { text ->
            if (text.isNotEmpty()) {
                inputArea.replaceSelection(text)
                return@bindPasteAttachment
            }
            defaultPasteAction?.actionPerformed(
                ActionEvent(inputArea, ActionEvent.ACTION_PERFORMED, DefaultEditorKit.pasteAction)
            )
        }
        inputArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean = canImportAttachment(support.transferable)
            override fun importData(support: TransferSupport): Boolean = importAttachmentTransferable(support.transferable)
        }
    }

    private fun setupSessionSelector() {
        sessionSelector.isEditable = false
        sessionSelector.renderer = simpleRenderer { (it as? ChatSessionRecord)?.title ?: "" }
        applyFixedWidth(sessionSelector, JBUI.scale(200))
        sessionSelector.addActionListener {
            if (updatingSessionSelection) return@addActionListener
            val selected = sessionSelector.selectedItem as? ChatSessionRecord ?: return@addActionListener
            switchSession(selected.id)
        }
    }

    private fun setupAgentSelector() {
        agentSelector.isEditable = false
        agentSelector.renderer = simpleRenderer { (it as? AgentRecord)?.name ?: "" }
        applyFixedWidth(agentSelector, JBUI.scale(200))
        agentSelector.addActionListener {
            if (updatingAgentSelection) return@addActionListener
            val agent = agentSelector.selectedItem as? AgentRecord ?: return@addActionListener
            bindCurrentSessionAgent(agent)
        }
    }

    // -------- 数据加载 --------

    private fun loadInitialData() {
        refreshAgentSelector(null)
        val sessions = ChatSessionService.listSessions()
        if (sessions.isEmpty()) {
            createSession()
        } else {
            currentSessionId = sessions.first().id
            refreshSessionSelector(sessions)
            switchSession(sessions.first().id)
        }
    }

    private fun refreshAgentSelector(selectedId: Long?) {
        updatingAgentSelection = true
        agentSelector.removeAllItems()
        AgentService.listAgents().forEach { agentSelector.addItem(it) }
        val target = (0 until agentSelector.itemCount)
            .map { agentSelector.getItemAt(it) }
            .firstOrNull { it.id == selectedId }
        if (target != null) agentSelector.selectedItem = target
        updatingAgentSelection = false
        syncBrowserState()
    }

    private fun refreshSessionSelector(sessions: List<ChatSessionRecord>) {
        updatingSessionSelection = true
        sessionSelector.removeAllItems()
        sessions.forEach { sessionSelector.addItem(it) }
        val target = (0 until sessionSelector.itemCount)
            .map { sessionSelector.getItemAt(it) }
            .firstOrNull { it.id == currentSessionId }
        if (target != null) sessionSelector.selectedItem = target
        updatingSessionSelection = false
        syncBrowserState()
    }

    // -------- 会话生命周期 --------

    private fun createSession() {
        val agent = agentSelector.selectedItem as? AgentRecord
        val record = ChatSessionService.createSession(AUTO_TITLE, agent?.id)
        currentSessionId = record.id
        refreshSessionSelector(ChatSessionService.listSessions())
        switchSession(record.id)
    }

    private fun switchSession(sessionId: Long) {
        val record = ChatSessionService.sessionById(sessionId) ?: return
        currentSessionId = record.id
        updatingSessionSelection = true
        selectSessionItem(record.id)
        updatingSessionSelection = false
        refreshAgentSelector(record.agentId)
        renderSessionHistory(record)
        refreshQueuePanel()
        updateActiveStopButton()
        updateStatus()
    }

    private fun selectSessionItem(sessionId: Long) {
        val target = (0 until sessionSelector.itemCount)
            .map { sessionSelector.getItemAt(it) }
            .firstOrNull { it.id == sessionId }
        if (target != null) sessionSelector.selectedItem = target
    }

    private fun bindCurrentSessionAgent(agent: AgentRecord) {
        val sessionId = currentSessionId ?: return
        val agentId = agent.id ?: return
        ChatSessionService.updateSessionAgent(sessionId, agentId)
        updateStatus()
    }

    private fun clearCurrentSession() {
        val sessionId = currentSessionId ?: return
        val record = ChatSessionService.sessionById(sessionId) ?: return
        val confirm = Messages.showYesNoDialog(
            project, "确定清空当前会话的全部对话记录？", "清空会话", Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return
        discardQueueItemsForSession(sessionId)
        ModelLogService.deleteChatTurns(
            ChatSessionService.SESSION_SOURCE_TYPE,
            ChatSessionService.sessionSourceId(record.agentId, record.id),
        )
        renderSessionHistory(record)
    }

    private fun clearCurrentSessionFromBrowser() {
        val sessionId = currentSessionId ?: return
        val record = ChatSessionService.sessionById(sessionId) ?: return
        discardQueueItemsForSession(sessionId)
        ModelLogService.deleteChatTurns(
            ChatSessionService.SESSION_SOURCE_TYPE,
            ChatSessionService.sessionSourceId(record.agentId, record.id),
        )
        renderSessionHistory(record)
    }

    private fun renameSession(record: ChatSessionRecord) {
        val name = Messages.showInputDialog(
            project, "输入新的会话名称", "重命名会话", Messages.getQuestionIcon(), record.title, null
        )?.trim().orEmpty()
        if (name.isBlank()) return
        ChatSessionService.renameSession(record.id, name)
        refreshSessionSelector(ChatSessionService.listSessions())
    }

    private fun deleteSession(record: ChatSessionRecord) {
        val confirm = Messages.showYesNoDialog(
            project, "确定删除会话 ${record.title}？其对话记录也会一并删除。", "删除会话", Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return
        discardQueueItemsForSession(record.id)
        ModelLogService.deleteChatTurns(
            ChatSessionService.SESSION_SOURCE_TYPE,
            ChatSessionService.sessionSourceId(record.agentId, record.id),
        )
        ChatSessionService.deleteSession(record.id)
        val remaining = ChatSessionService.listSessions()
        if (remaining.isEmpty()) {
            currentSessionId = null
            createSession()
        } else {
            currentSessionId = remaining.first().id
            refreshSessionSelector(remaining)
            switchSession(remaining.first().id)
        }
    }

    // -------- 会话历史渲染（数据源：model_request_logs）--------

    private fun renderSessionHistory(record: ChatSessionRecord) {
        renderedSessionId = record.id
        messageCards.clear()
        clearStreamingRefs()
        val sourceId = ChatSessionService.sessionSourceId(record.agentId, record.id)
        val entries = buildSessionRenderEntries(record.id, sourceId)
        entries.forEach { entry ->
            when (entry) {
                is SessionRenderEntry.History -> renderTurn(record.id, entry.turn)
                is SessionRenderEntry.Queue -> renderQueuedItem(entry.item)
            }
        }
        syncBrowserState()
    }

    private fun buildSessionRenderEntries(sessionId: Long, sourceId: String): List<SessionRenderEntry> {
        val queued = synchronized(queueLock) {
            chatQueue
                .filter { it.sessionId == sessionId && it.shouldRenderInHistory() }
                .map { SessionRenderEntry.Queue(it, queuedRenderOrder(it)) }
        }
        val queuedClientOrders = queued.mapTo(mutableSetOf()) { it.item.order }
        val history = ModelLogService.listChatTurns(ChatSessionService.SESSION_SOURCE_TYPE, sourceId)
            .asSequence()
            .filter { it.status != "running" && it.status != "failed" }
            .filterNot { chatTurnClientOrder(it) in queuedClientOrders }
            .map { SessionRenderEntry.History(it, it.logId) }
            .toList()
        return (history + queued).sortedWith(compareBy<SessionRenderEntry> { it.order }.thenBy { it.tieBreaker })
    }

    private fun queuedRenderOrder(item: ChatQueueItem): Long = Long.MAX_VALUE / 4 + item.order

    private fun chatTurnClientOrder(turn: ModelLogService.ChatTurn): Long? {
        val snapshot = jsonObject(turn.requestData, "request_snapshot")
        return snapshot?.get("client_message_order")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asLong
    }

    private fun renderTurn(sessionId: Long, turn: ModelLogService.ChatTurn) {
        renderTurnUserBubble(sessionId, turn)
        val structured = jsonObject(turn.responseData, "structured_response")
        if (structured != null) {
            val response = jsonString(structured.get("response"))
            val reasoning = reasoningText(structured)
            val hasTools = hasToolCalls(structured)
            if (response.isNotBlank() || reasoning.isNotBlank() || hasTools) {
                val turnView = createAssistantTurnView(sessionId) { deleteChatTurn(turn.logId) }
                if (response.isNotBlank()) setAssistantTurnResponse(turnView, response)
                if (reasoning.isNotBlank()) setAssistantTurnReasoning(turnView, reasoning, collapsedByDefault = false)
                renderTurnToolCalls(structured, turnView)
                turnView.card.finish(turn.createdAt, usageText(structured), usageDetails(structured))
                return
            }
        }
        if (turn.status == "failed" && !turn.errorData.isNullOrBlank()) {
            val turnView = createAssistantTurnView(sessionId) { deleteChatTurn(turn.logId) }
            turnView.card.setResponse("错误：${turn.errorData}")
            turnView.card.finish(turn.createdAt, null)
        }
    }

    private fun renderTurnUserBubble(sessionId: Long, turn: ModelLogService.ChatTurn) {
        if (turn.messageType == "agent_distillation") return
        val snapshot = jsonObject(turn.requestData, "request_snapshot") ?: return
        val prompt = jsonString(snapshot.get("prompt_message"))
        val attachments = readAttachmentSnapshots(snapshot)
        if (prompt.isBlank() && attachments.isEmpty()) return
        appendMessage(
            sessionId,
            ROLE_USER,
            prompt,
            collapsible = false,
            collapsedByDefault = false,
            attachments = attachments,
            onDelete = null,
            createdAt = turn.createdAt,
        )
    }

    private fun deleteChatTurn(logId: Long) {
        ModelLogService.deleteChatTurn(logId)
        currentSessionId?.let { refreshCurrentSessionHistoryIfVisible(it) }
        project.infoNotify("对话", "消息已删除")
    }

    private fun reasoningText(structured: JsonObject): String {
        val reasoning = structured.get("reasoning")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        return reasoning.mapNotNull { jsonString(it).takeIf { s -> s.isNotBlank() } }.joinToString("\n\n").trim()
    }

    private fun hasToolCalls(structured: JsonObject): Boolean =
        structured.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray?.isEmpty == false

    private fun usageText(structured: JsonObject): String? {
        val usage = structured.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val total = usageNumber(usage, "total_tokens").takeIf { it > 0 }
            ?: (usageNumber(usage, "input_tokens").takeIf { it > 0 } ?: usageNumber(usage, "prompt_tokens"))
                .saturatingAdd(usageNumber(usage, "output_tokens").takeIf { it > 0 } ?: usageNumber(usage, "completion_tokens"))
                .saturatingAdd(usageNumber(usage, "cache_creation_input_tokens"))
                .saturatingAdd(usageNumber(usage, "cache_read_input_tokens"))
        return if (total > 0) formatTokenUsage(total) else null
    }

    private fun usageDetails(structured: JsonObject): List<AgentBrowserUsageItem> {
        val usage = structured.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        return flattenUsageDetails(usage)
    }

    private fun flattenUsageDetails(value: JsonElement, prefix: String = ""): List<AgentBrowserUsageItem> {
        if (!value.isJsonObject) return emptyList()
        return value.asJsonObject.entrySet().flatMap { (key, entry) ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when {
                entry.isJsonObject -> flattenUsageDetails(entry, path)
                entry.isJsonPrimitive && entry.asJsonPrimitive.isNumber ->
                    listOf(AgentBrowserUsageItem(path, entry.asLong))
                else -> emptyList()
            }
        }
    }

    private fun usageNumber(usage: JsonObject, key: String): Long =
        usage.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L

    private fun Long.saturatingAdd(other: Long): Long =
        if (other > 0 && Long.MAX_VALUE - this < other) Long.MAX_VALUE else this + other

    private fun formatTokenUsage(value: Long): String = when {
        value >= 1_000_000 -> "${String.format("%.1f", value / 1_000_000.0).removeSuffix(".0")}m"
        value >= 1_000 -> "${String.format("%.1f", value / 1_000.0).removeSuffix(".0")}k"
        else -> "$value tokens"
    }

    private fun renderTurnToolCalls(structured: JsonObject, turnView: AssistantTurnView) {
        syncToolCalls(structured, turnView.card)
    }

    private fun syncToolCalls(structured: JsonObject, card: AgentAssistantMessageCard) {
        val calls = structured.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        if (calls.isEmpty) return
        val results = structured.get("tool_results")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val resultByCallId = mutableMapOf<String, String>()
        results.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val callId = jsonString(obj.get("internal_call_id"))
            if (callId.isNotBlank()) resultByCallId[callId] = jsonString(obj.get("result"))
        }
        calls.forEachIndexed { index, element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val callId = jsonString(obj.get("internal_call_id")).ifBlank { "tool-$index" }
            val name = jsonString(obj.get("tool_name"))
            val args = obj.get("args")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()
            card.ensureTool(callId, name, args)
            if (resultByCallId.containsKey(callId)) {
                card.updateToolResult(callId, resultByCallId[callId].orEmpty())
            }
        }
    }

    private fun readAttachmentSnapshots(snapshot: JsonObject): List<AgentAttachmentState> {
        val array = snapshot.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            AgentAttachmentState(
                id = jsonString(obj.get("id")).ifBlank { UUID.randomUUID().toString() },
                name = jsonString(obj.get("name")),
                path = jsonString(obj.get("path")),
                mimeType = jsonString(obj.get("mimeType")),
                size = obj.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
                kind = jsonString(obj.get("kind")).ifBlank { AgentAttachmentKind.FILE.id },
            )
        }
    }

    private fun jsonObject(parent: JsonObject?, key: String): JsonObject? {
        val element = parent?.get(key) ?: return null
        return element.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun jsonString(element: JsonElement?): String =
        element?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    // -------- 附件草稿 --------

    private fun pasteAttachmentsFromClipboard() {
        val transferable = systemClipboardContents() ?: CopyPasteManager.getInstance().contents ?: return
        if (importAttachmentTransferable(transferable)) return
        clipboardText(transferable).takeIf { AgentAttachmentClipboardSupport.parseFiles(it).isNotEmpty() }
            ?.let { addAttachmentFiles(AgentAttachmentClipboardSupport.parseFiles(it)) }
    }

    /** 弹出文件选择器，支持多选，加入草稿附件。 */
    private fun chooseAttachments() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
            .withTitle("选择附件")
        val files = FileChooser.chooseFiles(descriptor, project, null)
            .mapNotNull { it.takeIf { vf -> !vf.isDirectory } }
            .map { File(it.path) }
        addAttachmentFiles(files)
    }

    /**
     * 使用 IDE Paste action 的快捷键绑定粘贴附件。
     * Swing 文本组件自己的 paste action 在 IDEA action system 下不稳定，文件/图片粘贴必须绑定
     * IdeActions.ACTION_PASTE 的快捷键，和旧实现保持一致。
     */
    private fun bindPasteAttachment(component: JComponent, onTextFallback: (String) -> Unit) {
        val action = PasteAttachmentAction(
            onFiles = { files -> addAttachmentFiles(files) },
            onImage = { image -> imageToTempFile(image)?.let { addAttachmentFiles(listOf(it)) } },
            onTextFallback = onTextFallback,
        )
        val pasteAction = ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE) ?: return
        action.registerCustomShortcutSet(pasteAction.shortcutSet, component)
    }

    private inner class PasteAttachmentAction(
        private val onFiles: (List<File>) -> Unit,
        private val onImage: (Image) -> Unit,
        private val onTextFallback: (String) -> Unit,
    ) : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val systemClipboard = systemClipboardContents()
            if (systemClipboard != null && handleClipboardTransferable(systemClipboard, preferPlainText = true)) {
                return
            }
            val ideClipboard = CopyPasteManager.getInstance().contents
            if (ideClipboard != null && handleClipboardTransferable(ideClipboard, preferPlainText = false)) {
                return
            }
            onTextFallback("")
        }

        private fun handleClipboardTransferable(transferable: Transferable, preferPlainText: Boolean): Boolean {
            val text = clipboardText(transferable)
            if (preferPlainText && text.isNotEmpty() && AgentAttachmentClipboardSupport.parseFiles(text).isEmpty()) {
                onTextFallback(text)
                return true
            }
            val files = AgentAttachmentClipboardSupport.extractFiles(transferable)
            if (files.isNotEmpty()) {
                onFiles(files)
                return true
            }
            clipboardImage(transferable)?.let {
                onImage(it)
                return true
            }
            if (text.isNotEmpty()) {
                onTextFallback(text)
                return true
            }
            return false
        }
    }

    private fun canImportAttachment(transferable: Transferable): Boolean =
        AgentAttachmentClipboardSupport.extractFiles(transferable).isNotEmpty() ||
            transferable.isDataFlavorSupported(DataFlavor.imageFlavor)

    private fun importAttachmentTransferable(transferable: Transferable): Boolean {
        val files = AgentAttachmentClipboardSupport.extractFiles(transferable)
        if (files.isNotEmpty()) {
            addAttachmentFiles(files)
            return true
        }
        imageFromTransferable(transferable)?.let {
            addAttachment(it)
            return true
        }
        return false
    }

    private fun systemClipboardContents(): Transferable? =
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()

    /** 剪贴板图片转成临时 PNG 文件附件。 */
    private fun imageFromTransferable(transferable: Transferable): AgentAttachmentState? {
        val image = clipboardImage(transferable) ?: return null
        val tempFile = imageToTempFile(image) ?: return null
        return AgentAttachmentSupport.normalize(
            AgentAttachmentState(
                name = tempFile.name,
                path = tempFile.absolutePath,
                mimeType = "image/png",
                size = tempFile.length(),
                kind = AgentAttachmentKind.IMAGE.id,
            )
        )
    }

    private fun clipboardImage(transferable: Transferable): Image? {
        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
        return runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull()
    }

    private fun clipboardText(transferable: Transferable): String {
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return runCatching { transferable.getTransferData(DataFlavor.stringFlavor)?.toString().orEmpty() }.getOrDefault("")
        }
        transferable.transferDataFlavors.forEach { flavor ->
            if (flavor.isFlavorTextType) {
                val text = runCatching {
                    when (val data = transferable.getTransferData(flavor)) {
                        is java.io.Reader -> data.readText()
                        is java.io.InputStream -> data.bufferedReader().readText()
                        else -> data?.toString().orEmpty()
                    }
                }.getOrDefault("")
                if (text.isNotEmpty()) return text
            }
        }
        return ""
    }

    private fun imageToTempFile(image: Image): File? {
        val buffered = runCatching { toBufferedImage(image) }.getOrNull() ?: return null
        val tempFile = kotlin.io.path.createTempFile("jtools-agent-paste-", ".png").toFile()
        return if (runCatching { ImageIO.write(buffered, "png", tempFile) }.getOrDefault(false)) tempFile else null
    }

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val width = image.getWidth(null).coerceAtLeast(1)
        val height = image.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return buffered
    }

    private fun addAttachmentFiles(files: List<File>) {
        val added = files
            .filter { it.isFile }
            .map {
                AgentAttachmentSupport.normalize(
                    AgentAttachmentState(name = it.name, path = it.absolutePath, size = it.length())
                )
            }
        if (added.isEmpty()) return
        added.forEach(::addAttachment)
    }

    private fun addAttachment(attachment: AgentAttachmentState) {
        draftAttachments.removeAll { it.path.isNotBlank() && it.path == attachment.path }
        draftAttachments.add(attachment)
        refreshDraftAttachmentStrip()
    }

    private fun removeDraftAttachment(attachment: AgentAttachmentState) {
        draftAttachments.removeAll { it.id == attachment.id }
        refreshDraftAttachmentStrip()
    }

    private fun clearDraftAttachments() {
        draftAttachments.clear()
        refreshDraftAttachmentStrip()
    }

    private fun refreshDraftAttachmentStrip() {
        draftAttachmentStrip.removeAll()
        draftAttachments.forEach { attachment ->
            draftAttachmentStrip.add(
                AgentAttachmentChipUi.createDraftChip(
                    attachment = attachment,
                    onOpen = { openAttachment(attachment) },
                    onRemove = { removeDraftAttachment(attachment) },
                )
            )
            draftAttachmentStrip.add(Box.createHorizontalStrut(JBUI.scale(6)))
        }
        draftAttachmentPanel.isVisible = draftAttachments.isNotEmpty()
        draftAttachmentStrip.revalidate()
        draftAttachmentStrip.repaint()
        draftAttachmentPanel.revalidate()
        draftAttachmentPanel.repaint()
        draftAttachmentPanel.parent?.revalidate()
        draftAttachmentPanel.parent?.repaint()
        syncBrowserState()
    }

    // -------- 发送与运行 --------

    private fun ensureCurrentSessionForSend(): ChatSessionRecord {
        currentSessionId?.let(ChatSessionService::sessionById)?.let { return it }
        val agentId = (agentSelector.selectedItem as? AgentRecord)?.id
            ?: AgentService.listAgents().firstOrNull { it.enabled }?.id
        val record = ChatSessionService.createSession(AUTO_TITLE, agentId)
        currentSessionId = record.id
        refreshSessionSelector(ChatSessionService.listSessions())
        switchSession(record.id)
        return record
    }

    private fun sendMessage() {
        val record = ensureCurrentSessionForSend()
        val agent = resolveSessionAgent(record) ?: return
        val prompt = inputArea.text.trim()
        val attachments = draftAttachments.toList()
        if (prompt.isEmpty() && attachments.isEmpty()) return

        val item = ChatQueueItem(
            messageId = UUID.randomUUID().toString(),
            sessionId = record.id,
            agentId = agent.id ?: return,
            prompt = prompt,
            order = queueOrder.incrementAndGet(),
            attachments = attachments,
        )
        synchronized(queueLock) { chatQueue.add(item) }
        inputArea.text = ""
        clearDraftAttachments()
        maybeAutoRenameSession(record, prompt)
        refreshQueuePanel()
        processQueue()
    }

    private fun processQueue() {
        val items = nextRunnableQueueItems()
        items.forEach { item ->
            onUi { startQueueItemUi(item) }
            ApplicationManager.getApplication().executeOnPooledThread { runQueueItem(item) }
        }
    }

    private fun nextRunnableQueueItems(): List<ChatQueueItem> = synchronized(queueLock) {
        val processingSessionIds = chatQueue
            .asSequence()
            .filter { it.status == ChatQueueStatus.PROCESSING }
            .map { it.sessionId }
            .toMutableSet()
        chatQueue
            .filter { it.status == ChatQueueStatus.PENDING && processingSessionIds.add(it.sessionId) }
            .onEach { it.status = ChatQueueStatus.PROCESSING }
    }

    private fun runQueueItem(item: ChatQueueItem) {
        val record = ChatSessionService.sessionById(item.sessionId)
        val agent = AgentService.agentById(item.agentId)
        if (record == null || agent == null || !agent.enabled) {
            finishQueueItemError(item, "队列任务关联的会话或 Agent 已不存在")
            return
        }
        val history = buildHistory(record)
        val result = try {
            AgentRuntime.execute(buildQueueRequest(item, agent, record, history))
        } catch (e: Throwable) {
            if (!isQueueItemActive(item)) {
                (e as? ModelRequestException)?.logId?.let { ModelLogService.deleteModelLog(it) }
            } else if (item.token.isCancelled()) {
                finishQueueItemCancelled(item, (e as? ModelRequestException)?.logId)
            } else {
                val logId = (e as? ModelRequestException)?.logId
                finishQueueItemError(item, e.message ?: "调用失败", logId)
            }
            return
        }
        if (!isQueueItemActive(item)) {
            ModelLogService.deleteModelLog(result.logId)
            return
        }
        finishQueueItemSuccess(item, result)
    }

    private fun buildQueueRequest(
        item: ChatQueueItem,
        agent: AgentRecord,
        record: ChatSessionRecord,
        history: List<Message>,
    ): AgentRuntime.Request {
        val streamSink = object : ModelStreamSink {
            override fun onResponseDelta(text: String) = onUi { appendStreamingText(item, ROLE_ASSISTANT, text) }
            override fun onReasoningDelta(text: String) = onUi { appendStreamingText(item, ROLE_REASONING, text) }
        }
        val eventSink = object : ToolEventSink {
            override fun onToolCall(call: ProviderToolCall) = onUi { renderToolCallEvent(item, call) }
            override fun onToolResult(result: ToolResult) = onUi { renderToolResultEvent(item, result) }
        }
        val modalities = resolveModelModalities(agent)
        val attachmentContents = item.attachments.map { AgentAttachmentSupport.toUserContent(it, modalities) }
        val attachmentSnapshots = item.attachments.map { AgentAttachmentSupport.snapshotOf(it) }
        return AgentRuntime.Request(
            agentId = agent.id ?: item.agentId,
            prompt = item.prompt,
            triggerType = "chat",
            triggerId = record.id.toString(),
            workspace = resolveWorkspace(),
            skillsRootDir = ResourceConfigService.skillsRootDir(),
            history = history,
            attachments = attachmentContents,
            attachmentSnapshots = attachmentSnapshots,
            streamSink = streamSink,
            eventSink = eventSink,
            cancel = item.token,
            toolCancel = item.toolToken,
            clientMessageOrder = item.order,
            project = project,
        )
    }

    /** 读取 Agent 绑定模型声明的多模态能力（image/audio/video/text）。 */
    private fun resolveModelModalities(agent: AgentRecord): Set<String> {
        val modelId = agent.modelId ?: return emptySet()
        val model = CatalogService.modelById(modelId) ?: return emptySet()
        return runCatching {
            com.google.gson.JsonParser.parseString(model.modalities)
                .asJsonArray.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun scheduleSessionDistillation(sessionId: Long, sourceAgentId: Long) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runSessionDistillation(sessionId, sourceAgentId)
        }
    }

    private fun runSessionDistillation(sessionId: Long, sourceAgentId: Long) {
        val sourceAgent = AgentService.agentById(sourceAgentId) ?: return
        val config = sourceAgent.distillConfig
        if (!config.enabled) return
        val distillAgentId = config.agentId ?: run {
            onUi { project.errorNotify("Agent 蒸馏", "${sourceAgent.name} 已启用蒸馏，但未配置蒸馏 Agent") }
            return
        }
        val distillAgent = AgentService.agentById(distillAgentId) ?: run {
            onUi { project.errorNotify("Agent 蒸馏", "蒸馏 Agent `$distillAgentId` 不存在") }
            return
        }
        if (!distillAgent.enabled) {
            onUi { project.errorNotify("Agent 蒸馏", "蒸馏 Agent ${distillAgent.name} 已停用") }
            return
        }
        val session = ChatSessionService.sessionById(sessionId) ?: return
        val sourceId = ChatSessionService.sessionSourceId(sourceAgentId, sessionId)
        val logs = ModelLogService.listCompletedLogsForSource(
            sourceType = ChatSessionService.SESSION_SOURCE_TYPE,
            sourceId = sourceId,
            afterId = config.lastDistilledModelLogId,
            messageTypes = config.effectiveMessageTypes(),
        )
        if (logs.size < config.minMessages) return
        val maxSourceLogId = logs.maxOf { it.id }
        try {
            val personaDraft = java.util.concurrent.atomic.AtomicReference<com.lhstack.tools.db.config.AgentPersonaConfig?>()
            AgentRuntime.execute(
                distillAgent,
                AgentRuntime.Request(
                    agentId = distillAgent.id ?: distillAgentId,
                    prompt = buildSessionDistillationPrompt(sourceAgent, session, logs, config.extraPrompt),
                    triggerType = "distillation",
                    triggerId = sessionId.toString(),
                    workspace = resolveWorkspace(),
                    skillsRootDir = ResourceConfigService.skillsRootDir(),
                    extraTools = listOf(UpdateAgentDistillationTool(personaDraft, sourceAgent.extConfig.persona)),
                    logSourceType = ChatSessionService.SESSION_SOURCE_TYPE,
                    logSourceId = sourceId,
                    logMessageType = "agent_distillation",
                    logPromptMessage = null,
                    project = project,
                )
            )
            val distilledPersona = personaDraft.get()
                ?: throw IllegalStateException("蒸馏模型未调用 update_agent_distillation 工具提交 Agent 长期配置")
            AgentService.updateDistilledPersona(sourceAgentId, distilledPersona, maxSourceLogId)
            onUi { refreshCurrentSessionHistoryIfVisible(sessionId) }
        } catch (e: Throwable) {
            onUi {
                refreshCurrentSessionHistoryIfVisible(sessionId)
                project.errorNotify("Agent 蒸馏", e.message ?: "蒸馏失败")
            }
        }
    }

    private fun buildSessionDistillationPrompt(
        sourceAgent: AgentRecord,
        session: ChatSessionRecord,
        logs: List<ModelLogService.ModelLogRecord>,
        extraPrompt: String?,
    ): String = buildString {
        val persona = sourceAgent.extConfig.persona
        appendLine("你是 Agent 能力蒸馏助手。请根据以下 Agent 的历史消息，更新该 Agent 自身的长期专业配置，让它在持续处理同一领域任务时变得更专业。")
        appendLine()
        appendLine("内容必须使用简体中文，且应面向 Agent 助手自身，不要写成用户画像、任务流水或系统配置复述。每个字段都必须整理为完整版本，不能只返回新增内容；每个字段必须严格控制在对应最大字符数以内。")
        appendLine(); appendLine("蒸馏时间信息：")
        appendLine("- 当前蒸馏时间：${LocalDateTime.now().toString().replace('T', ' ')}")
        appendLine("- 本次历史消息数量：${logs.size}")
        appendLine("- 会话：${session.title} (#${session.id})")
        extraPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine(); appendLine("用户自定义的附加蒸馏要求："); appendLine(it)
        }
        appendLine(); appendLine("目标 Agent：${sourceAgent.name} (#${sourceAgent.id})")
        appendLine(); appendLine("当前 Agent 长期内容：")
        appendPersonaForDistillation("专业记忆", persona.memory, persona.memoryMaxChars)
        appendPersonaForDistillation("工作方法", persona.behaviorHabits, persona.behaviorHabitsMaxChars)
        appendPersonaForDistillation("角色设定", persona.soul, persona.soulMaxChars)
        appendPersonaForDistillation("能力画像", persona.profile, persona.profileMaxChars)
        appendPersonaForDistillation("边界约束", persona.guardrails, persona.guardrailsMaxChars)
        appendLine("---"); appendLine("历史消息：")
        logs.forEach { log ->
            appendLine("## 消息 #${log.id} [${log.messageType.orEmpty()} / ${log.status} / 时间: ${log.createdAt.orEmpty()}]")
            appendLine("输入："); appendLine(modelLogPrompt(log))
            val structured = log.responseData.get("structured_response")?.takeIf { it.isJsonObject }?.asJsonObject
            appendLine("输出：")
            appendLine(structured?.get("response")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
            log.errorData?.takeIf { it.isNotBlank() }?.let { appendLine("错误："); appendLine(it) }
            appendLine()
        }
        appendLine("---")
        appendLine("请对比当前长期内容和历史消息，重新整理合并后的完整版本，不要把新内容简单追加到旧内容后面。")
        appendLine("字段归属：memory 记录长期领域事实、稳定决策和可复用经验；behavior_habits 记录有效工作方法与质量标准；soul 记录稳定职责边界和专业定位；profile 记录专业方向、擅长任务与能力边界；guardrails 记录限制、禁区与安全要求。同一信息只能放入最匹配的一个字段。")
        appendLine("每条内容必须带时间状态标签并以“- ”开头。主动合并相似条目，删除重复、冲突、过期、临时和低价值内容；某字段无需更新时原样返回，除非确实应该清空。")
        appendLine("你必须调用 update_agent_distillation 工具提交 memory、behavior_habits、soul、profile、guardrails 的完整内容；如果工具返回 ok=false，必须根据 errors 压缩后重新调用，直到 ok=true。工具成功后，最终回复只输出本次 Agent 蒸馏更新摘要，不得输出 JSON。")
    }

    private fun StringBuilder.appendPersonaForDistillation(label: String, content: String, maxChars: Int) {
        appendLine("### $label（当前内容，最大字符数 $maxChars）")
        appendLine(content)
        appendLine()
    }

    private fun startQueueItemUi(item: ChatQueueItem) {
        ensureQueueUserCard(item)
        ensureQueueAssistantCard(item)
        renderQueuedItem(item)
        refreshQueuePanel()
        updateActiveStopButton()
        scrollQueueItemIfVisible(item)
    }

    private fun finishQueueItemSuccess(item: ChatQueueItem, result: AgentRuntime.ExecutionResult) = onUi {
        if (!isQueueItemActive(item)) return@onUi
        item.persistedLogId = result.logId
        applyFinalAssistantResult(item, result)
        item.status = ChatQueueStatus.COMPLETED
        completeQueueItem(item)
        scheduleSessionDistillation(item.sessionId, item.agentId)
    }

    private fun finishQueueItemCancelled(item: ChatQueueItem, logId: Long?) = onUi {
        if (!isQueueItemActive(item)) return@onUi
        item.status = ChatQueueStatus.CANCELLED
        item.persistedLogId = logId
        if (item.hasAssistantOutput) {
            logId?.let { ModelLogService.finishModelLogCancelled(it, cancelledResponseData(item)) }
            ensureQueueAssistantCard(item).finish(LocalDateTime.now().toString().replace('T', ' '), null)
            completeQueueItem(item, refreshHistory = logId != null, remove = logId != null)
        } else {
            logId?.let { ModelLogService.deleteModelLog(it) }
            if (currentSessionId == item.sessionId) {
                inputArea.text = item.prompt
            }
            completeQueueItem(item, refreshHistory = true)
        }
    }

    private fun finishQueueItemError(item: ChatQueueItem, error: String, logId: Long? = null) = onUi {
        if (!isQueueItemActive(item)) return@onUi
        item.status = ChatQueueStatus.FAILED
        item.persistedLogId = logId
        discardQueueCards(item)
        restoreFailedInput(item)
        completeQueueItem(item, refreshHistory = true)
        project.errorNotify("模型请求失败", error)
    }

    private fun discardQueueCards(item: ChatQueueItem) {
        item.userCard?.let(messageCards::remove)
        item.assistantCard?.let(messageCards::remove)
        item.userCard = null
        item.assistantCard = null
    }

    private fun restoreFailedInput(item: ChatQueueItem) {
        inputArea.text = item.prompt
        item.attachments.forEach { attachment ->
            draftAttachments.removeAll { existing -> existing.path.isNotBlank() && existing.path == attachment.path }
            draftAttachments.add(attachment)
        }
        browserInputRestore = AgentBrowserInputRestore(++inputRestoreSequence, item.prompt)
        refreshDraftAttachmentStrip()
    }

    private fun completeQueueItem(item: ChatQueueItem, refreshHistory: Boolean = true, remove: Boolean = true) {
        synchronized(queueLock) {
            if (remove) {
                chatQueue.remove(item)
            }
        }
        refreshQueuePanel()
        updateActiveStopButton()
        if (refreshHistory) {
            refreshCurrentSessionHistoryIfVisible(item.sessionId)
        }
        processQueue()
    }

    private fun discardQueueItemsForSession(sessionId: Long) {
        val removed = synchronized(queueLock) {
            val items = chatQueue.filter { it.sessionId == sessionId }
            items.forEach { item ->
                item.status = ChatQueueStatus.CANCELLED
                item.token.cancel()
                item.toolToken.cancel()
            }
            chatQueue.removeAll(items.toSet())
            items
        }
        if (removed.isEmpty()) return
        refreshQueuePanel()
        updateActiveStopButton()
        processQueue()
    }

    private fun cancelQueueItem(item: ChatQueueItem) {
        val sessionToRefresh = synchronized(queueLock) {
            when (item.status) {
                ChatQueueStatus.PENDING -> {
                    item.status = ChatQueueStatus.CANCELLED
                    chatQueue.remove(item)
                    item.sessionId
                }
                ChatQueueStatus.PROCESSING -> {
                    item.conversationCancelRequested = true
                    item.token.cancel()
                    null
                }
                else -> null
            }
        }
        refreshQueuePanel()
        sessionToRefresh?.let { refreshCurrentSessionHistoryIfVisible(it) }
        processQueue()
    }

    private fun isQueueItemActive(item: ChatQueueItem): Boolean = synchronized(queueLock) {
        item.status != ChatQueueStatus.CANCELLED && chatQueue.contains(item)
    }

    private fun stopQueueItem(item: ChatQueueItem) {
        if (item.status != ChatQueueStatus.PROCESSING) return
        if (item.runningToolCount > 0) {
            if (item.toolCancelRequested) return
            item.toolCancelRequested = true
            item.toolToken.cancel()
            val runningIds = item.runningToolIds.toList()
            item.canceledToolIds.addAll(runningIds)
            runningIds.forEach { toolId ->
                item.assistantCard?.updateToolResult(toolId, "用户手动取消")
            }
            refreshQueuePanel()
            updateActiveStopButton()
            return
        }
        cancelQueueItem(item)
    }

    private fun updateActiveStopButton() = Unit

    private fun hasActiveQueue(): Boolean = synchronized(queueLock) {
        chatQueue.any { it.status == ChatQueueStatus.PENDING || it.status == ChatQueueStatus.PROCESSING }
    }

    private fun refreshCurrentSessionHistoryIfVisible(sessionId: Long) {
        if (currentSessionId != sessionId) return
        val record = ChatSessionService.sessionById(sessionId) ?: return
        renderSessionHistory(record)
    }

    private fun resolveSessionAgent(record: ChatSessionRecord): AgentRecord? {
        val agentId = record.agentId
        if (agentId == null) {
            project.errorNotify("对话", "当前会话未绑定 Agent，请先在上方选择 Agent")
            return null
        }
        val agent = AgentService.agentById(agentId)
        if (agent == null) {
            project.errorNotify("对话", "会话绑定的 Agent 已不存在，请重新选择")
            return null
        }
        if (!agent.enabled) {
            project.errorNotify("对话", "Agent ${agent.name} 已停用")
            return null
        }
        return agent
    }

    private fun buildHistory(record: ChatSessionRecord): List<Message> {
        val sourceId = ChatSessionService.sessionSourceId(record.agentId, record.id)
        val turns = ModelLogService.listChatTurns(ChatSessionService.SESSION_SOURCE_TYPE, sourceId)
        val messages = mutableListOf<Message>()
        turns.filter { it.status != "running" && it.status != "failed" }.forEach { turn ->
            val snapshot = jsonObject(turn.requestData, "request_snapshot")
            val prompt = jsonString(snapshot?.get("prompt_message"))
            if (prompt.isNotBlank()) messages.add(Message.user(prompt))
            appendProviderHistory(messages, jsonObject(turn.responseData, "structured_response"))
        }
        return messages
    }

    /** 从对话日志还原 provider 的工具调用/工具结果消息，供下一轮上下文截断按工具轮次工作。 */
    private fun appendProviderHistory(messages: MutableList<Message>, structured: JsonObject?) {
        if (structured == null) return
        val calls: List<JsonElement> = structured.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()
        val results: List<JsonElement> = structured.get("tool_results")?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()
        val assistantContent = calls.mapNotNull { element ->
            val call = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val internalId = jsonString(call.get("internal_call_id")).ifBlank { jsonString(call.get("tool_call_id")) }
            if (internalId.isBlank()) return@mapNotNull null
            AssistantContent.ToolCall(
                ToolCall(
                    id = internalId,
                    callId = jsonString(call.get("tool_call_id")).takeIf { it.isNotBlank() },
                    function = ToolFunction(
                        name = jsonString(call.get("tool_name")),
                        arguments = jsonArgument(call.get("args")),
                    ),
                    signature = null,
                    additionalParams = null,
                ),
            )
        }
        if (assistantContent.isNotEmpty()) {
            messages.add(Message.Assistant(id = null, content = assistantContent))
        }
        val toolResults = results.mapNotNull { element ->
            val result = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val internalId = jsonString(result.get("internal_call_id")).ifBlank { jsonString(result.get("tool_call_id")) }
            if (internalId.isBlank()) return@mapNotNull null
            UserContent.ToolResult(
                ToolResult(
                    id = internalId,
                    callId = jsonString(result.get("tool_call_id")).takeIf { it.isNotBlank() },
                    content = listOf(ToolResultContent.Text(jsonString(result.get("result")))),
                ),
            )
        }
        if (toolResults.isNotEmpty()) messages.add(Message.User(toolResults))
        val response = jsonString(structured.get("response"))
        if (response.isNotBlank()) messages.add(Message.assistant(response))
    }

    private fun jsonArgument(element: JsonElement?): JsonElement {
        if (element == null || element.isJsonNull) return JsonObject()
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return element.deepCopy()
        return runCatching { JsonParser.parseString(element.asString) }.getOrElse { JsonPrimitive(element.asString) }
    }

    private fun maybeAutoRenameSession(record: ChatSessionRecord, source: String) {
        if (record.title != AUTO_TITLE) return
        val title = source.trim().take(20).ifBlank { return }
        ChatSessionService.renameSession(record.id, title)
        refreshSessionSelector(ChatSessionService.listSessions())
    }

    private fun onUi(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block)
    }

    private fun resolveWorkspace(): String =
        project.basePath?.replace("\\", "/")?.trim()?.ifBlank { null } ?: System.getProperty("user.dir")

    // -------- 实时消息渲染 --------

    private fun appendStreamingText(item: ChatQueueItem, role: String, text: String) {
        if (text.isEmpty()) return
        item.hasAssistantOutput = true
        val card = ensureQueueAssistantCard(item)
        when (role) {
            ROLE_REASONING -> {
                item.reasoningText += text
                card.appendReasoning(text)
            }
            else -> {
                item.responseText += text
                card.appendResponse(text)
            }
        }
        scrollQueueItemIfVisible(item)
    }

    private fun renderToolCallEvent(item: ChatQueueItem, call: ProviderToolCall) {
        item.hasAssistantOutput = true
        item.runningToolIds.add(call.id)
        item.runningToolCount = item.runningToolIds.size
        item.toolCalls[call.id] = QueuedToolSnapshot(call.id, call.name, call.argsString())
        ensureQueueAssistantCard(item).ensureTool(call.id, call.name, call.argsString())
        refreshQueuePanel()
        updateActiveStopButton()
        scrollQueueItemIfVisible(item)
    }

    private fun renderToolResultEvent(item: ChatQueueItem, result: ToolResult) {
        val text = result.content.filterIsInstance<ToolResultContent.Text>().joinToString("\n") { it.text }
        if (!item.canceledToolIds.contains(result.id)) {
            item.toolResults[result.id] = text
            ensureQueueAssistantCard(item).updateToolResult(result.id, text)
        }
        item.runningToolIds.remove(result.id)
        item.runningToolCount = item.runningToolIds.size
        if (item.canceledToolIds.remove(result.id) && item.canceledToolIds.isEmpty()) {
            resetCancelledTools(item)
        }
        refreshQueuePanel()
        updateActiveStopButton()
    }

    private fun resetCancelledTools(item: ChatQueueItem) {
        item.toolToken.reset()
        item.toolCancelRequested = false
    }

    private fun scrollQueueItemIfVisible(item: ChatQueueItem) {
        if (renderedSessionId == item.sessionId) syncBrowserState()
    }

    private fun deleteQueueTurn(item: ChatQueueItem) {
        item.persistedLogId?.let { ModelLogService.deleteModelLog(it) }
        synchronized(queueLock) { chatQueue.remove(item) }
        refreshQueuePanel()
        refreshCurrentSessionHistoryIfVisible(item.sessionId)
        project.infoNotify("对话", "消息已删除")
    }

    private fun cancelledResponseData(item: ChatQueueItem): JsonObject = JsonObject().apply {
        addProperty("retry_count", 0)
        add("structured_response", JsonObject().apply {
            add("reasoning", JsonArray().apply {
                item.reasoningText.trim().takeIf { it.isNotBlank() }?.let { add(it) }
            })
            addProperty("response", item.responseText.trim())
            add("tool_calls", JsonArray().apply {
                item.toolCalls.values.forEach { tool ->
                    add(JsonObject().apply {
                        addProperty("source", "ui_cancelled")
                        addProperty("tool_name", tool.name)
                        addProperty("internal_call_id", tool.id)
                        addProperty("args", tool.args)
                    })
                }
            })
            add("tool_results", JsonArray().apply {
                item.toolResults.forEach { (id, result) ->
                    add(JsonObject().apply {
                        addProperty("source", "ui_cancelled")
                        addProperty("internal_call_id", id)
                        addProperty("result", result)
                    })
                }
            })
            add("usage", JsonObject())
            addProperty("cancelled", true)
        })
    }

    private fun applyFinalAssistantResult(item: ChatQueueItem, result: AgentRuntime.ExecutionResult) {
        item.hasAssistantOutput = true
        val card = ensureQueueAssistantCard(item)
        val response = result.output.ifBlank { jsonString(result.value.get("response")) }
        item.responseText = response
        card.setResponse(response)
        val reasoning = reasoningText(result.value)
        if (reasoning.isNotBlank()) {
            item.reasoningText = reasoning
            card.setReasoning(reasoning, expanded = true)
        }
        syncToolCalls(result.value, card)
        card.finish(LocalDateTime.now().toString().replace('T', ' '), usageText(result.value), usageDetails(result.value))
    }

    private fun ensureQueueUserCard(item: ChatQueueItem): AgentUserMessageCard {
        val existing = item.userCard
        if (existing != null) return existing
        val card = AgentUserMessageCard(
            content = item.prompt,
            attachments = item.attachments,
        )
        item.userCard = card
        return card
    }

    private fun ensureQueueAssistantCard(item: ChatQueueItem): AgentAssistantMessageCard {
        val existing = item.assistantCard
        if (existing != null) return existing
        val card = AgentAssistantMessageCard(
            showToolDetail = { toolItem, anchor -> showAgentToolDetailPopup(toolItem, anchor) },
            onDelete = { deleteQueueTurn(item) },
            onCopyCode = { project.infoNotify("复制", "已复制代码块") },
        )
        item.assistantCard = card
        return card
    }

    private fun attachQueueCardIfVisible(item: ChatQueueItem, card: AgentChatCard) {
        if (renderedSessionId != item.sessionId) return
        addMessageCard(item.sessionId, card)
    }

    private fun renderQueuedItem(item: ChatQueueItem) {
        attachQueueCardIfVisible(item, ensureQueueUserCard(item))
        item.assistantCard?.let { attachQueueCardIfVisible(item, it) }
    }

    private fun ChatQueueItem.shouldRenderInHistory(): Boolean =
        status == ChatQueueStatus.PROCESSING || userCard != null || assistantCard != null

    private fun clearStreamingRefs() {
        currentTurnView = null
    }

    private fun reasoningColor(): Color = JBColor(0x6A6A6A, 0x9A9A9A)

    private fun ensureCurrentAssistantTurnView(sessionId: Long): AssistantTurnView =
        currentTurnView ?: createAssistantTurnView(sessionId).also { currentTurnView = it }

    private fun createAssistantTurnView(sessionId: Long, onDelete: (() -> Unit)? = null): AssistantTurnView {
        val card = AgentAssistantMessageCard(
            showToolDetail = { item, anchor -> showAgentToolDetailPopup(item, anchor) },
            onDelete = onDelete,
            onCopyCode = { project.infoNotify("复制", "已复制代码块") },
        )
        addMessageCard(sessionId, card)
        return AssistantTurnView(card)
    }

    private fun setAssistantTurnResponse(turnView: AssistantTurnView, content: String) {
        turnView.card.setResponse(content)
    }

    private fun setAssistantTurnReasoning(turnView: AssistantTurnView, content: String, collapsedByDefault: Boolean) {
        turnView.card.setReasoning(content, expanded = !collapsedByDefault)
    }

    // -------- 消息气泡基础设施 --------

    private fun appendMessage(
        sessionId: Long,
        role: String,
        content: String,
        collapsible: Boolean,
        collapsedByDefault: Boolean,
        attachments: List<AgentAttachmentState> = emptyList(),
        onDelete: (() -> Unit)? = null,
        createdAt: String? = null,
    ) {
        if (role == ROLE_USER) {
            addMessageCard(
                sessionId,
                AgentUserMessageCard(
                    content,
                    attachments,
                    onDelete,
                    createdAt,
                )
            )
            return
        }
        val card = AgentAssistantMessageCard(
            showToolDetail = { item, anchor -> showAgentToolDetailPopup(item, anchor) },
            onDelete = onDelete,
            onCopyCode = { project.infoNotify("复制", "已复制代码块") },
        )
        card.setResponse(if (role == ROLE_ERROR) "错误：$content" else content)
        addMessageCard(sessionId, card)
    }

    private fun createMessageBlock(
        role: String,
        textColor: Color,
        collapsible: Boolean,
        collapsedByDefault: Boolean,
        fixedHeight: Int? = null,
    ): MessageBlock {
        val textPane = createPlainTextArea(textColor)
        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(textPane, BorderLayout.CENTER)
        }
        val scrollPane: JScrollPane? = if (collapsible) {
            JBScrollPane(contentPanel).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                isVisible = !collapsedByDefault
                fixedHeight?.let {
                    preferredSize = Dimension(0, it)
                    maximumSize = Dimension(Int.MAX_VALUE, it)
                }
            }
        } else null
        val header = createBlockHeader(role, collapsible, collapsedByDefault, scrollPane, contentPanel)
        val body = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 8)
            add(header, BorderLayout.NORTH)
            add(scrollPane ?: contentPanel, BorderLayout.CENTER)
        }
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(body, if (role == ROLE_USER) BorderLayout.EAST else BorderLayout.CENTER)
        }
        return MessageBlock(panel, textPane, contentPanel)
    }

    private fun createBlockHeader(
        role: String,
        collapsible: Boolean,
        collapsedByDefault: Boolean,
        collapsibleTarget: JComponent?,
        contentPanel: JComponent,
    ): JComponent {
        val userRole = role == ROLE_USER
        val roleLabel = JLabel(role).apply {
            foreground = JBColor(0x8A8A8A, 0x9AA0A6)
            font = font.deriveFont(font.size2D - 1f)
            horizontalAlignment = if (userRole) SwingConstants.RIGHT else SwingConstants.LEFT
        }
        if (!collapsible || collapsibleTarget == null) {
            return JPanel(BorderLayout()).apply {
                isOpaque = false
                add(roleLabel, if (userRole) BorderLayout.EAST else BorderLayout.WEST)
            }
        }
        val toggle = JButton(if (collapsedByDefault) "展开" else "收起").apply {
            isFocusable = false
            isBorderPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(0, 6)
            foreground = JBColor(0x4B90FF, 0x4B90FF)
            addActionListener {
                collapsibleTarget.isVisible = !collapsibleTarget.isVisible
                text = if (collapsibleTarget.isVisible) "收起" else "展开"
                collapsibleTarget.revalidate()
                collapsibleTarget.repaint()
            }
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(roleLabel, BorderLayout.WEST)
            add(toggle, BorderLayout.EAST)
        }
    }

    private fun createPlainTextArea(textColor: Color): JTextComponent =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = textColor
            font = UIUtil.getLabelFont()
            border = JBUI.Borders.empty()
        }

    private fun setBlockContent(block: MessageBlock, content: String, immediate: Boolean) {
        block.rawContent = content
        block.textComponent.text = content
    }

    private fun appendBlockContent(block: MessageBlock, text: String) {
        if (text.isEmpty()) return
        block.rawContent += text
        block.textComponent.document.insertString(block.textComponent.document.length, text, null)
        block.textComponent.caretPosition = block.textComponent.document.length
        block.contentPanel.revalidate()
        block.contentPanel.repaint()
    }

    // -------- 工具卡片基础设施 --------

    private fun createToolListBlock(collapsedByDefault: Boolean): ToolListBlock {
        val listPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = !collapsedByDefault
        }
        val headerLabel = JLabel("工具调用").apply {
            foreground = JBColor(0x8A8A8A, 0x9AA0A6)
            font = font.deriveFont(font.size2D - 1f)
        }
        val toggle = JButton(if (collapsedByDefault) "展开" else "收起").apply {
            isFocusable = false
            isBorderPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(0, 6)
            foreground = JBColor(0x4B90FF, 0x4B90FF)
            addActionListener {
                listPanel.isVisible = !listPanel.isVisible
                text = if (listPanel.isVisible) "收起" else "展开"
            }
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerLabel, BorderLayout.WEST)
            add(toggle, BorderLayout.EAST)
        }
        val panel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 8)
            add(header, BorderLayout.NORTH)
            add(listPanel, BorderLayout.CENTER)
        }
        return ToolListBlock(panel, listPanel, headerLabel)
    }

    private fun addToolCard(block: ToolListBlock, callId: String, name: String, args: String): ToolCard {
        val card = ToolCard(id = callId, name = name.ifBlank { "工具" }, args = args)
        val titleLabel = JLabel().apply {
            font = font.deriveFont(font.size2D - 1f)
        }
        val detailButton = JButton("详情").apply {
            isFocusable = false
            isBorderPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(0, 6)
            foreground = JBColor(0x4B90FF, 0x4B90FF)
            addActionListener { showToolDetailPopup(card, this) }
        }
        val cardPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = true
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4, 8),
            )
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            add(titleLabel, BorderLayout.CENTER)
            add(detailButton, BorderLayout.EAST)
        }
        card.titleLabel = titleLabel
        card.panel = cardPanel
        block.cards[callId] = card
        block.listPanel.add(cardPanel)
        block.listPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        refreshToolCard(card)
        block.listPanel.revalidate()
        block.listPanel.repaint()
        return card
    }

    private fun refreshToolCard(card: ToolCard) {
        card.titleLabel?.text = "[${card.status}] ${card.name}"
    }

    private fun updateToolBlockSummary(block: ToolListBlock) {
        val total = block.cards.size
        val done = block.cards.values.count { it.status == "已完成" }
        block.headerLabel.text = "工具调用（$done/$total）"
    }

    private fun showAgentToolDetailPopup(item: AgentToolItem, anchor: JComponent) {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(420))
            add(createToolJsonSection("入参", item.args), BorderLayout.NORTH)
            add(createToolJsonSection("出参", item.result), BorderLayout.CENTER)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setResizable(true)
            .setMovable(true)
            .setTitle("工具 ${item.name}")
            .createPopup()
            .show(RelativePoint(anchor, java.awt.Point(0, anchor.height)))
    }

    private fun showToolDetailPopup(card: ToolCard, anchor: JComponent) {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(420))
            add(createToolJsonSection("参数", card.args), BorderLayout.NORTH)
            add(createToolJsonSection("结果", card.result), BorderLayout.CENTER)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setResizable(true)
            .setMovable(true)
            .setTitle("工具 ${card.name}")
            .createPopup()
            .show(RelativePoint(anchor, java.awt.Point(0, anchor.height)))
    }

    private fun createToolJsonSection(title: String, content: String): JComponent {
        val viewer = createJsonViewer(content)
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(JLabel(title), BorderLayout.NORTH)
            add(JBScrollPane(viewer).apply { preferredSize = Dimension(JBUI.scale(540), JBUI.scale(180)) }, BorderLayout.CENTER)
        }
    }

    private fun createJsonViewer(text: String): LanguageTextField {
        val language = Language.findLanguageByID("JSON") ?: Language.findLanguageByID("TEXT")
        return object : LanguageTextField(language, project, text, false) {
            override fun createEditor(): EditorEx = super.createEditor().apply {
                setViewer(true)
                setVerticalScrollbarVisible(true)
                setHorizontalScrollbarVisible(true)
            }
        }
    }

    // -------- 消息容器编排 --------

    private fun addMessageCard(sessionId: Long, card: AgentChatCard) {
        if (renderedSessionId != sessionId || messageCards.any { it.id == card.id }) return
        if (card is AgentAssistantMessageCard) card.onChanged = ::syncBrowserState
        messageCards.add(card)
        syncBrowserState()
    }

    private fun openAttachment(attachment: AgentAttachmentState) {
        openAttachmentPath(attachment.path)
    }

    private fun openAttachmentPath(rawPath: String) {
        val path = rawPath.trim().ifBlank { return }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    private fun openSessionManager() {
        val sessions = ChatSessionService.listSessions()
        if (sessions.isEmpty()) {
            project.infoNotify("会话管理", "当前还没有会话")
            return
        }
        val dialog = object : DialogWrapper(project, false) {
            private val listModel = javax.swing.DefaultListModel<ChatSessionRecord>().apply {
                sessions.forEach { addElement(it) }
            }
            private val list = com.intellij.ui.components.JBList(listModel).apply {
                selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
                cellRenderer = simpleRenderer { (it as? ChatSessionRecord)?.title ?: "" }
                selectedIndex = 0
            }

            init {
                title = "会话管理"
                setSize(JBUI.scale(420), JBUI.scale(480))
                init()
            }

            override fun createCenterPanel(): JComponent {
                val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(JButton("重命名").apply {
                        addActionListener {
                            val record = list.selectedValue ?: return@addActionListener
                            renameSession(record)
                            listModel.clear()
                            ChatSessionService.listSessions().forEach { listModel.addElement(it) }
                        }
                    })
                    add(JButton("删除").apply {
                        addActionListener {
                            val record = list.selectedValue ?: return@addActionListener
                            deleteSession(record)
                            listModel.clear()
                            ChatSessionService.listSessions().forEach { listModel.addElement(it) }
                        }
                    })
                }
                return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                    border = JBUI.Borders.empty(8)
                    add(JBScrollPane(list), BorderLayout.CENTER)
                    add(buttons, BorderLayout.SOUTH)
                }
            }

            override fun createActions(): Array<out javax.swing.Action?> = emptyArray()
        }
        dialog.showAndGet()
        refreshSessionSelector(ChatSessionService.listSessions())
    }

    private fun openModelLogDialog() {
        val dialog = object : DialogWrapper(project, false) {
            private var page = 1
            private val pageSize = 20
            private var rows: List<ModelLogService.ModelLogRecord> = emptyList()
            private val tableModel = object : DefaultTableModel(arrayOf("ID", "来源", "状态", "模型", "时间", "提示"), 0) {
                override fun isCellEditable(row: Int, column: Int): Boolean = false
                override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) java.lang.Long::class.java else String::class.java
            }
            private val table = JTable(tableModel).apply {
                setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                rowHeight = JBUI.scale(26)
                fillsViewportHeight = true
                autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
                rowSorter = TableRowSorter(tableModel)
            }
            private val pageLabel = JLabel()
            private val detailPanel = JPanel(BorderLayout())
            private val metadataArea = modelLogDetailTextArea()
            private val requestArea = modelLogDetailTextArea()
            private val responseArea = modelLogDetailTextArea()
            private val detailTabs = JTabbedPane().apply {
                addTab("元数据", JBScrollPane(metadataArea))
                addTab("请求", JBScrollPane(requestArea))
                addTab("响应", JBScrollPane(responseArea))
            }

            init {
                title = "模型日志"
                setSize(JBUI.scale(1180), JBUI.scale(760))
                isResizable = true
                init()
                configureColumns()
                table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) renderSelectedModelLog() }
                reloadPage(1)
            }

            override fun createCenterPanel(): JComponent {
                val tablePanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                    add(JBScrollPane(table), BorderLayout.CENTER)
                    add(pageBar(), BorderLayout.SOUTH)
                }
                val splitter = JBSplitter(false, .58f).apply {
                    firstComponent = tablePanel
                    secondComponent = detailPanel.apply {
                        border = JBUI.Borders.customLine(JBColor.border(), 1)
                        add(detailTabs, BorderLayout.CENTER)
                    }
                }
                return JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(8)
                    add(splitter, BorderLayout.CENTER)
                }
            }

            override fun createActions(): Array<out Action?> = emptyArray()

            private fun pageBar(): JComponent = JPanel(BorderLayout()).apply {
                add(pageLabel, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(JButton("上一页").apply { addActionListener { reloadPage(page - 1) } })
                    add(JButton("下一页").apply { addActionListener { reloadPage(page + 1) } })
                    add(JButton("刷新").apply { addActionListener { reloadPage(page) } })
                    add(JButton("删除").apply { addActionListener { deleteSelectedModelLog() } })
                }, BorderLayout.EAST)
            }

            private fun configureColumns() {
                table.columnModel.getColumn(0).preferredWidth = JBUI.scale(70)
                table.columnModel.getColumn(1).preferredWidth = JBUI.scale(90)
                table.columnModel.getColumn(2).preferredWidth = JBUI.scale(90)
                table.columnModel.getColumn(3).preferredWidth = JBUI.scale(180)
                table.columnModel.getColumn(4).preferredWidth = JBUI.scale(170)
                table.columnModel.getColumn(5).preferredWidth = JBUI.scale(430)
            }

            private fun reloadPage(targetPage: Int) {
                val result = ModelLogService.modelLogPage(targetPage, pageSize)
                page = result.page
                rows = result.rows
                tableModel.rowCount = 0
                rows.forEach { record ->
                    tableModel.addRow(arrayOf(
                        record.id,
                        if (isChatModelLog(record)) "chat" else record.sourceType,
                        record.status,
                        listOfNotNull(record.providerName, record.modelName).joinToString(" / "),
                        record.createdAt ?: record.startedAt.orEmpty(),
                        modelLogPrompt(record).replace('\n', ' ').take(120),
                    ))
                }
                val totalPages = ((result.total + pageSize - 1) / pageSize).coerceAtLeast(1)
                pageLabel.text = "第 $page / $totalPages 页，共 ${result.total} 条"
                if (tableModel.rowCount > 0) table.setRowSelectionInterval(0, 0) else clearModelLogDetail()
            }

            private fun renderSelectedModelLog() {
                val record = selectedRecord()
                if (record == null) {
                    clearModelLogDetail()
                    return
                }
                metadataArea.text = modelLogMetadata(record)
                requestArea.text = gson.toJson(record.requestData)
                responseArea.text = gson.toJson(record.responseData)
                metadataArea.caretPosition = 0
                requestArea.caretPosition = 0
                responseArea.caretPosition = 0
            }

            private fun clearModelLogDetail() {
                metadataArea.text = ""
                requestArea.text = ""
                responseArea.text = ""
            }

            private fun modelLogDetailTextArea(): JBTextArea = JBTextArea().apply {
                isEditable = false
                lineWrap = false
                font = UIUtil.getLabelFont()
            }

            private fun selectedRecord(): ModelLogService.ModelLogRecord? {
                val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return null
                val modelRow = table.convertRowIndexToModel(viewRow)
                return rows.getOrNull(modelRow)
            }

            private fun deleteSelectedModelLog() {
                val record = selectedRecord() ?: return
                if (isChatModelLog(record)) {
                    Messages.showInfoMessage(project, "会话日志不能在这里删除，请去会话里删除对应对话。", "模型日志")
                    return
                }
                val confirm = Messages.showYesNoDialog(
                    project,
                    "确定删除模型日志 #${record.id}？",
                    "删除模型日志",
                    Messages.getQuestionIcon(),
                )
                if (confirm != Messages.YES) return
                ModelLogService.deleteModelLog(record.id)
                reloadPage(page)
            }
        }
        dialog.showAndGet()
    }

    private fun modelLogMetadata(record: ModelLogService.ModelLogRecord): String = buildString {
        appendLine("ID: ${record.id}")
        appendLine("来源: ${if (isChatModelLog(record)) "chat" else record.sourceType}")
        appendLine("source_id: ${record.sourceId.orEmpty()}")
        appendLine("Agent ID: ${record.agentId ?: ""}")
        appendLine("消息类型: ${record.messageType.orEmpty()}")
        appendLine("模型: ${listOfNotNull(record.providerName, record.modelName).joinToString(" / ")}")
        appendLine("状态: ${record.status}")
        appendLine("开始时间: ${record.startedAt.orEmpty()}")
        appendLine("结束时间: ${record.finishedAt.orEmpty()}")
        appendLine("创建时间: ${record.createdAt.orEmpty()}")
        record.errorData?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("错误:")
            appendLine(it)
        }
    }

    private fun modelLogPrompt(record: ModelLogService.ModelLogRecord): String {
        val snapshot = record.requestData.get("request_snapshot")?.takeIf { it.isJsonObject }?.asJsonObject
        return snapshot?.get("prompt_message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }

    private fun isChatModelLog(record: ModelLogService.ModelLogRecord): Boolean {
        if (record.sourceType != ChatSessionService.SESSION_SOURCE_TYPE) return false
        val sessionId = record.sourceId?.substringAfter(':', missingDelimiterValue = "")?.toLongOrNull() ?: return false
        return ChatSessionService.sessionById(sessionId) != null
    }

    private fun openModelManager() {
        val panel = AwakeProviderModelConfigPanel(project)
        showConfigDialog("供应商与模型", JBUI.scale(1100), JBUI.scale(720), { panel.component }, { panel.dispose() })
    }

    private fun openPromptManager() {
        val panel = AwakePromptConfigPanel(project)
        showConfigDialog("提示词管理", JBUI.scale(1100), JBUI.scale(720), { panel.component }, { panel.dispose() })
    }

    private fun openAgentManager() {
        val panel = AwakeAgentConfigPanel(project) {
            refreshAgentSelector((agentSelector.selectedItem as? AgentRecord)?.id)
        }
        showConfigDialog("Agent 管理", JBUI.scale(1180), JBUI.scale(760), { panel.component }, { panel.dispose() })
        refreshAgentSelector((agentSelector.selectedItem as? AgentRecord)?.id)
    }

    private fun openSkillManager() {
        val panel = AwakeSkillsConfigPanel(project)
        showConfigDialog("Skills 管理", JBUI.scale(980), JBUI.scale(660), { panel.component }, { panel.dispose() })
    }

    private fun openGlobalConfigManager() {
        val panel = AwakeGlobalConfigPanel(project)
        showConfigDialog("全局配置", JBUI.scale(1100), JBUI.scale(760), { panel.component }, { panel.dispose() })
    }

    private fun showConfigDialog(
        dialogTitle: String,
        width: Int,
        height: Int,
        componentProvider: () -> JComponent,
        onDispose: () -> Unit = {},
    ) {
        val dialog = object : DialogWrapper(project, false) {
            private val panelComponent = componentProvider()

            init {
                title = dialogTitle
                setSize(width, height)
                isResizable = true
                Disposer.register(disposable) { onDispose() }
                init()
            }

            override fun createCenterPanel(): JComponent = panelComponent
            override fun createActions(): Array<out javax.swing.Action?> = emptyArray()
        }
        dialog.showAndGet()
    }

    private fun refreshQueuePanel() {
        syncBrowserState()
        updateToolbars()
    }

    // -------- UI 构建 --------

    private fun buildTopBar(): JComponent {
        val sessionPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JLabel("会话: "))
            add(sessionSelector)
            val group = DefaultActionGroup().apply {
                add(newSessionAction)
                add(clearAction)
                add(sessionManageAction)
                add(modelLogAction)
            }
            add(createToolbar("AgentSessionToolbar", group))
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 0, 8)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(sessionPanel)
            }, BorderLayout.EAST)
        }
    }

    private fun buildChatContainer(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8, 8, 6, 8)
        add(chatScroll, BorderLayout.CENTER)
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

        val agentPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 4, 0)
            add(JLabel("Agent: "))
            add(agentSelector)
            val group = DefaultActionGroup().apply {
                add(attachmentAction)
                add(modelManageAction)
                add(promptManageAction)
                add(agentManageAction)
                        add(skillManageAction)
                add(globalConfigAction)
            }
            add(createToolbar("AgentActionToolbar", group))
        }

        inputCenterPanel.removeAll()
        inputCenterPanel.add(inputScroll, BorderLayout.CENTER)

        val inputCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = INPUT_COMPOSER_BACKGROUND
            add(inputCenterPanel, BorderLayout.CENTER)
        }
        updateInputComposerChrome(inputCard, focused = false)
        inputArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = updateInputComposerChrome(inputCard, focused = true)
            override fun focusLost(e: FocusEvent) = updateInputComposerChrome(inputCard, focused = false)
        })

        val topPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(inputHintLabel, BorderLayout.WEST)
            })
            add(queuePanel)
            add(draftAttachmentPanel)
            add(agentPanel)
        }

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            isOpaque = false
            add(topPanel, BorderLayout.NORTH)
            add(inputCard, BorderLayout.CENTER)
        }
    }

    private fun updateInputComposerChrome(card: JPanel, focused: Boolean) {
        card.background = INPUT_COMPOSER_BACKGROUND
        inputArea.background = INPUT_COMPOSER_BACKGROUND
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                if (focused) INPUT_COMPOSER_FOCUS_BORDER else INPUT_COMPOSER_BORDER,
                JBUI.scale(1),
                true,
            ),
            JBUI.Borders.empty(8, 10),
        )
        card.revalidate()
        card.repaint()
    }

    // -------- action / toolbar / 状态 --------

    private fun createAction(
        description: String,
        icon: javax.swing.Icon,
        enabledProvider: (() -> Boolean)? = null,
        action: () -> Unit,
    ): AnAction = object : AnAction({ description }, AgentToolbarIconSupport.normalize(icon)) {
        override fun actionPerformed(e: AnActionEvent) = action()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (enabledProvider?.invoke() ?: true) && isActionEnabled(this)
        }
    }

    private fun createToolbar(id: String, group: DefaultActionGroup): JComponent {
        val toolbar = ActionManager.getInstance().createActionToolbar(id, group, true)
        toolbar.targetComponent = this
        toolbar.setMinimumButtonSize(AgentToolbarIconSupport.minimumButtonSize)
        actionToolbars.add(toolbar)
        return toolbar.component
    }

    private fun simpleRenderer(textProvider: (Any?) -> String): javax.swing.ListCellRenderer<Any?> =
        object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component = super.getListCellRendererComponent(list, textProvider(value), index, isSelected, cellHasFocus)
        }

    private fun applyFixedWidth(comboBox: ComboBox<*>, width: Int) {
        val size = Dimension(width, comboBox.preferredSize.height)
        comboBox.preferredSize = size
        comboBox.minimumSize = size
        comboBox.maximumSize = size
    }

    private fun updateToolbars() = actionToolbars.forEach { it.updateActionsAsync() }

    private fun setActionEnabled(action: AnAction, enabled: Boolean) {
        actionEnabledState[action] = enabled
    }

    private fun isActionEnabled(action: AnAction): Boolean = actionEnabledState[action] != false

    private fun updateStatus() {
        val session = currentSessionId?.let { ChatSessionService.sessionById(it) }
        val agent = session?.agentId?.let { AgentService.agentById(it) }
        statusLabel.text = when {
            session == null -> "无会话"
            agent == null -> "会话: ${session.title}    未绑定 Agent"
            else -> "会话: ${session.title}    Agent: ${agent.name}"
        }
        syncBrowserState()
    }

    override fun dispose() {
        if (chatBrowser.component.parent != null) Disposer.dispose(chatBrowser)
        Disposer.dispose(managementWindows)
    }

    /** 面板被选中时刷新会话与 Agent 列表，供入口 action 回调。 */
    fun refreshStatus() {
        val selectedAgentId = (agentSelector.selectedItem as? AgentRecord)?.id
        refreshAgentSelector(selectedAgentId)
        refreshSessionSelector(ChatSessionService.listSessions())
        updateStatus()
    }

    private fun handleBrowserCommand(command: AgentBrowserCommand): Any? {
        val payload = command.payload
        val id = payload.get("id")?.takeUnless { it.isJsonNull }?.asString
        val text = payload.get("text")?.takeUnless { it.isJsonNull }?.asString
        return when (command.type) {
            "ui.ready" -> syncBrowserState()
            "session.select" -> id?.toLongOrNull()?.let(::switchSession)
            "session.new" -> createSession()
            "session.clear" -> clearCurrentSessionFromBrowser()
            "agent.select" -> id?.toLongOrNull()?.let { agentId ->
                AgentService.agentById(agentId)?.let(::bindCurrentSessionAgent)
                refreshAgentSelector(agentId)
            }
            "message.send" -> { inputArea.text = text.orEmpty(); sendMessage() }
            "window.open" -> managementWindows.open(text ?: error("缺少管理页面"))
            "code.copy" -> CopyPasteManager.getInstance().setContents(StringSelection(text.orEmpty()))
            "link.open" -> text?.let { BrowserUtil.browse(it) }
            "attachment.choose" -> chooseAttachments()
            "attachment.paste" -> pasteAttachmentsFromClipboard()
            "attachment.remove" -> id?.let { attachmentId -> draftAttachments.firstOrNull { it.id == attachmentId }?.let(::removeDraftAttachment) }
            "attachment.open" -> text?.let(::openAttachmentPath)
            "queue.stop" -> id?.let { messageId -> synchronized(queueLock) { chatQueue.firstOrNull { it.messageId == messageId } }?.let { if (it.status == ChatQueueStatus.PROCESSING) stopQueueItem(it) else cancelQueueItem(it) } }
            "message.delete" -> id?.let { messageId -> messageCards.firstOrNull { it.id == messageId }?.delete() }
            else -> handleBrowserManagementCommand(command.type, payload)
        }
    }

    private fun refreshManagementData() {
        refreshAgentSelector(currentSessionId?.let(ChatSessionService::sessionById)?.agentId)
        refreshSessionSelector(ChatSessionService.listSessions())
        currentSessionId?.let(::refreshCurrentSessionHistoryIfVisible)
        syncBrowserState()
    }

    private fun handleBrowserManagementCommand(type: String, payload: JsonObject): Any? =
        AgentBrowserManagement.handle(project, type, payload) {
            val sessions = ChatSessionService.listSessions()
            if (sessions.isEmpty()) {
                createSession()
            } else {
                if (currentSessionId !in sessions.map { it.id }) currentSessionId = sessions.first().id
                refreshAgentSelector(currentSessionId?.let(ChatSessionService::sessionById)?.agentId)
                refreshSessionSelector(sessions)
                currentSessionId?.let(::refreshCurrentSessionHistoryIfVisible)
                syncBrowserState()
            }
        }

    private fun syncBrowserState() {
        val sessions = ChatSessionService.listSessions().map { AgentBrowserOption(it.id, it.title) }
        val agents = AgentService.listAgents().mapNotNull { agent -> agent.id?.let { AgentBrowserOption(it, agent.name) } }
        val currentAgentId = currentSessionId?.let(ChatSessionService::sessionById)?.agentId
        val queue = synchronized(queueLock) {
            chatQueue.filter { it.status == ChatQueueStatus.PENDING || it.status == ChatQueueStatus.PROCESSING }.map { item ->
                AgentBrowserQueueItem(item.messageId, item.sessionId, ChatSessionService.sessionById(item.sessionId)?.title ?: "会话 ${item.sessionId}", item.prompt.take(40), item.status.label, item.status == ChatQueueStatus.PROCESSING)
            }
        }
        val background = UIUtil.getPanelBackground()
        chatBrowser.replaceState(AgentBrowserState(
            dark = ColorUtil.isDark(background),
            theme = AgentBrowserTheme(
                background = cssColor(background),
                panel = cssColor(UIUtil.getPanelBackground()),
                input = cssColor(UIUtil.getTextFieldBackground()),
                text = cssColor(UIUtil.getLabelForeground()),
                muted = cssColor(UIUtil.getContextHelpForeground()),
                border = cssColor(JBColor.border()),
                accent = cssColor(JBColor(0x3574F0, 0x548AF7)),
            ),
            sessions = sessions,
            agents = agents,
            currentSessionId = currentSessionId,
            currentAgentId = currentAgentId,
            messages = messageCards.map(AgentChatCard::toBrowserMessage),
            queue = queue,
            drafts = draftAttachments.map { it.toBrowserAttachment() },
            inputRestore = browserInputRestore,
        ))
    }

    private fun cssColor(color: Color): String = "#${ColorUtil.toHex(color)}"

    // -------- 渲染数据结构 --------

    private data class AgentBrowserState(
        @SerializedName("dark") val dark: Boolean,
        @SerializedName("theme") val theme: AgentBrowserTheme,
        @SerializedName("sessions") val sessions: List<AgentBrowserOption>,
        @SerializedName("agents") val agents: List<AgentBrowserOption>,
        @SerializedName("currentSessionId") val currentSessionId: Long?,
        @SerializedName("currentAgentId") val currentAgentId: Long?,
        @SerializedName("messages") val messages: List<AgentBrowserMessage>,
        @SerializedName("queue") val queue: List<AgentBrowserQueueItem>,
        @SerializedName("drafts") val drafts: List<AgentBrowserAttachment>,
        @SerializedName("inputRestore") val inputRestore: AgentBrowserInputRestore?,
    )
    private data class AgentBrowserInputRestore(
        @SerializedName("sequence") val sequence: Long,
        @SerializedName("text") val text: String,
    )
    private data class AgentBrowserTheme(
        @SerializedName("background") val background: String,
        @SerializedName("panel") val panel: String,
        @SerializedName("input") val input: String,
        @SerializedName("text") val text: String,
        @SerializedName("muted") val muted: String,
        @SerializedName("border") val border: String,
        @SerializedName("accent") val accent: String,
    )
    private data class AgentBrowserOption(
        @SerializedName("id") val id: Long,
        @SerializedName("name") val name: String,
    )
    private data class AgentBrowserQueueItem(
        @SerializedName("id") val id: String,
        @SerializedName("sessionId") val sessionId: Long,
        @SerializedName("title") val title: String,
        @SerializedName("prompt") val prompt: String,
        @SerializedName("status") val status: String,
        @SerializedName("processing") val processing: Boolean,
    )

    private enum class ChatQueueStatus(val label: String) {
        PENDING("排队中"),
        PROCESSING("进行中"),
        COMPLETED("已完成"),
        FAILED("失败"),
        CANCELLED("已取消"),
    }

    private data class ChatQueueItem(
        val messageId: String,
        val sessionId: Long,
        val agentId: Long,
        val prompt: String,
        val order: Long,
        val attachments: List<AgentAttachmentState> = emptyList(),
        val token: ModelCancel = ModelCancel(),
        val toolToken: ModelCancel = ModelCancel(),
        var status: ChatQueueStatus = ChatQueueStatus.PENDING,
        var runningToolCount: Int = 0,
        var toolCancelRequested: Boolean = false,
        var conversationCancelRequested: Boolean = false,
        var hasAssistantOutput: Boolean = false,
        var persistedLogId: Long? = null,
        var responseText: String = "",
        var reasoningText: String = "",
        val toolCalls: MutableMap<String, QueuedToolSnapshot> = linkedMapOf(),
        val toolResults: MutableMap<String, String> = linkedMapOf(),
        val runningToolIds: MutableSet<String> = linkedSetOf(),
        val canceledToolIds: MutableSet<String> = linkedSetOf(),
        var userCard: AgentUserMessageCard? = null,
        var assistantCard: AgentAssistantMessageCard? = null,
    )

    private data class QueuedToolSnapshot(
        val id: String,
        val name: String,
        val args: String,
    )

    private sealed class SessionRenderEntry(open val order: Long, open val tieBreaker: Long) {
        data class History(val turn: ModelLogService.ChatTurn, override val order: Long) :
            SessionRenderEntry(order, turn.logId)

        data class Queue(val item: ChatQueueItem, override val order: Long) :
            SessionRenderEntry(order, item.order)
    }

    private data class AssistantTurnView(
        val card: AgentAssistantMessageCard,
    )

    private data class MessageBlock(
        val panel: JComponent,
        val textComponent: JTextComponent,
        val contentPanel: JComponent,
        var rawContent: String = "",
    )

    private data class ToolListBlock(
        val panel: JComponent,
        val listPanel: JPanel,
        val headerLabel: JLabel,
        val cards: LinkedHashMap<String, ToolCard> = LinkedHashMap(),
    )

    private data class ToolCard(
        val id: String,
        val name: String,
        val args: String,
        var result: String = "",
        var status: String = "调用中",
        var titleLabel: JLabel? = null,
        var panel: JComponent? = null,
    )
}

