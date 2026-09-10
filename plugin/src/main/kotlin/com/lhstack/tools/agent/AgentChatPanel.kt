package com.lhstack.tools.agent

import com.lhstack.tools.agent.coding.CodingAttachmentStore
import com.lhstack.tools.agent.coding.CodingRuntimeSupport
import com.lhstack.tools.agent.coding.CodingSessionSupport
import com.lhstack.tools.agent.coding.ContextCompactionService
import com.lhstack.tools.agent.coding.MessageProcessor
import com.lhstack.tools.agent.coding.MessageTaskStatus
import com.lhstack.tools.agent.coding.toBrowserJson
import com.lhstack.tools.db.service.MessageStoreService

import com.lhstack.tools.concurrent.AgentExecutors
import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
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
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lhstack.tools.agent.model.log.ModelLogService
import com.lhstack.tools.db.service.AgentRecord
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.ChatSessionRecord
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.CodingEnvironmentService
import com.lhstack.tools.db.service.SettingService
import com.lhstack.tools.db.service.ChatSessionType
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.infoNotify
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Component
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
import javax.swing.ButtonGroup
import javax.swing.JRadioButton
import javax.swing.JTextField
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
 * Agent 对话面板。对话发送走 MessageProcessor；Agent 运行/润色仍复用 AgentRuntime
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
        const val ACTIVE_SESSION_SETTING_PREFIX = "agent.chat.active_session_id:"
        val INPUT_COMPOSER_BACKGROUND = JBColor(Color(0xFFFFFF), Color(0x2B2F34))
        val INPUT_COMPOSER_BORDER = JBColor(Color(0xD3D9E2), Color(0x4E545A))
        val INPUT_COMPOSER_DIVIDER = JBColor(Color(0xE4E8EF), Color(0x43484D))
        val INPUT_COMPOSER_FOCUS_BORDER = JBColor(0x4B90FF, 0x4B90FF)
    }

    @Volatile
    private var disposed = false

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val chatBrowser: AgentChatBrowser by lazy { AgentChatBrowser(gson, ::handleBrowserCommand, onDropFiles = ::addAttachmentFiles) }
    private val managementWindows by lazy { AgentManagementWindowManager(project, gson, ::refreshManagementData) }

    private val inputArea = JBTextArea(3, 0)
    private val draftAttachments = mutableListOf<AgentAttachmentState>()
    private val draftAttachmentStrip = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }
    private val draftAttachmentPanel = AgentAttachmentChipUi.createHorizontalStrip(draftAttachmentStrip).apply {
        isVisible = false
    }

    private val sessionSelector = ComboBox<ChatSessionRecord>()
    private val agentSelector = ComboBox<AgentRecord>()

    private var currentSessionId: Long? = null
    private var inputRestoreSequence = 0L
    private var historyRevision = 0L
    private var browserInputRestore: AgentBrowserInputRestore? = null
    private var updatingSessionSelection = false
    private var updatingAgentSelection = false

    private val browserEvents = mutableListOf<JsonObject>()
    private val browserTasks = mutableListOf<JsonObject>()
    private var agentRunDeliverySubscription: AutoCloseable? = null
    private val browserStateTimer = javax.swing.Timer(40) { syncBrowserStateNow() }.apply { isRepeats = false }

    init {
        setupSessionSelector()
        setupAgentSelector()
        setContent(chatBrowser.component)
        val connection = project.messageBus.connect(this)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { syncBrowserState() })
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    syncBrowserState()
                }
            },
        )
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    if (AgentEditorFileContextSupport.isEnabled(currentProjectPath())) {
                        syncBrowserState()
                    }
                }
            },
            this,
        )
        agentRunDeliverySubscription = AgentRunService.subscribeDeliveries(currentProjectPath(), ::enqueueAgentRunDelivery)
        MessageProcessor.addListener(::onMessageRuntimeEvent)
        loadInitialData()
    }

    // -------- 选择器与输入初始化 --------

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
        }
    }

    // -------- 数据加载 --------

    private fun loadInitialData() {
        refreshAgentSelector(null)
        val sessions = visibleSessions()
        if (sessions.isEmpty()) {
            showEmptySessionState()
        } else {
            val targetId = resolveRestoredSessionId(sessions) ?: sessions.first().id
            currentSessionId = targetId
            refreshSessionSelector(sessions)
            switchSession(targetId)
        }
    }

    private fun refreshAgentSelector(selectedId: Long?) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            onUiWait { refreshAgentSelector(selectedId) }
            return
        }
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
        if (!ApplicationManager.getApplication().isDispatchThread) {
            onUiWait { refreshSessionSelector(sessions) }
            return
        }
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

    private fun chooseAndCreateSession() {
        val dialog = object : DialogWrapper(project, false) {
            private val nameField = JTextField(28)
            private val projectType = JRadioButton("项目会话（仅当前项目可见）", true)
            private val globalType = JRadioButton("全局会话（所有项目共享）")

            init {
                title = "新建会话"
                ButtonGroup().apply {
                    add(projectType)
                    add(globalType)
                }
                init()
            }

            override fun createCenterPanel(): JComponent = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(8)
                add(JLabel("会话名称"))
                add(Box.createVerticalStrut(JBUI.scale(6)))
                add(nameField)
                add(Box.createVerticalStrut(JBUI.scale(14)))
                add(JLabel("会话范围"))
                add(Box.createVerticalStrut(JBUI.scale(6)))
                add(projectType)
                add(globalType)
            }

            override fun getPreferredFocusedComponent(): JComponent = nameField

            override fun doOKAction() {
                val name = nameField.text.trim()
                if (name.isBlank()) {
                    setErrorText("请输入会话名称", nameField)
                    return
                }
                createSession(if (projectType.isSelected) ChatSessionType.PROJECT else ChatSessionType.GLOBAL, name)
                super.doOKAction()
            }
        }
        dialog.show()
    }

    private fun createSession(sessionType: ChatSessionType, title: String = AUTO_TITLE) {
        val environment = requireSelectedEnvironment()
        val current = currentSessionId?.let(ChatSessionService::sessionById)
        val fallback = CatalogService.listProvidersWithModels()
            .asSequence()
            .mapNotNull { item -> item.models.firstOrNull { it.enabled == 1 }?.let { item.provider.id to it.id } }
            .firstOrNull()
        val record = ChatSessionService.createSession(
            title = title,
            codingEnvironmentId = environment.id,
            sessionType = sessionType,
            workspacePath = if (sessionType == ChatSessionType.GLOBAL) {
                CodingSessionSupport.globalWorkspacePath()
            } else {
                currentProjectPath()
            },
            providerId = current?.providerId ?: fallback?.first,
            modelId = current?.modelId ?: fallback?.second,
            promptId = current?.promptId,
        )
        currentSessionId = record.id
        refreshSessionSelector(visibleSessions())
        switchSession(record.id)
    }

    private fun switchSession(sessionId: Long) {
        val record = ChatSessionService.visibleSessionById(sessionId, currentProjectPath()) ?: return
        currentSessionId = record.id
        rememberActiveSession(record.id)
        updatingSessionSelection = true
        selectSessionItem(record.id)
        updatingSessionSelection = false
        reloadBrowserConversation(record.id)
        updateStatus()
    }

    private fun selectSessionItem(sessionId: Long) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            onUiWait { selectSessionItem(sessionId) }
            return
        }
        val target = (0 until sessionSelector.itemCount)
            .map { sessionSelector.getItemAt(it) }
            .firstOrNull { it.id == sessionId }
        if (target != null) sessionSelector.selectedItem = target
    }

    private fun bindCurrentSessionEnvironment(environmentId: Long) {
        val sessionId = currentSessionId ?: return
        ChatSessionService.updateSessionCodingEnvironment(sessionId, environmentId)
        reloadBrowserConversation(sessionId)
        updateStatus()
    }

    private fun clearCurrentSession() {
        val sessionId = currentSessionId ?: return
        val record = ChatSessionService.sessionById(sessionId) ?: return
        val confirm = Messages.showYesNoDialog(
            project, "确定清空当前会话的全部对话记录？", "清空会话", Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return
        MessageStoreService.clearSession(record.id)
        reloadBrowserConversation(record.id)
    }

    private fun refreshBrowserMessageCache() {
        val sessionId = currentSessionId ?: return
        reloadBrowserConversation(sessionId)
    }

    private fun clearCurrentSessionFromBrowser() {
        val sessionId = currentSessionId ?: return
        MessageStoreService.clearSession(sessionId)
        reloadBrowserConversation(sessionId)
    }

    private fun renameSession(record: ChatSessionRecord) {
        val name = Messages.showInputDialog(
            project, "输入新的会话名称", "重命名会话", Messages.getQuestionIcon(), record.title, null
        )?.trim().orEmpty()
        if (name.isBlank()) return
        ChatSessionService.renameSession(record.id, name)
        refreshSessionSelector(visibleSessions())
    }

    private fun deleteSession(record: ChatSessionRecord) {
        val confirm = Messages.showYesNoDialog(
            project, "确定删除会话 ${record.title}？其对话记录也会一并删除。", "删除会话", Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return
        ChatSessionService.deleteSession(record.id)
        val remaining = visibleSessions()
        if (remaining.isEmpty()) {
            showEmptySessionState()
        } else {
            val targetId = remaining.first().id
            currentSessionId = targetId
            refreshSessionSelector(remaining)
            switchSession(targetId)
        }
    }

    // -------- 附件草稿 --------

    private fun pasteAttachmentsFromClipboard() {
        onUiWait {
            val transferable = systemClipboardContents() ?: CopyPasteManager.getInstance().contents ?: return@onUiWait
            if (importAttachmentTransferable(transferable)) return@onUiWait
            clipboardText(transferable).takeIf { AgentAttachmentClipboardSupport.parseFiles(it).isNotEmpty() }
                ?.let { addAttachmentFiles(AgentAttachmentClipboardSupport.parseFiles(it)) }
        }
    }

    /** 弹出文件选择器，支持多选，加入草稿附件。 */
    private fun chooseAttachments() {
        onUiWait {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle("选择附件")
            val files = FileChooser.chooseFiles(descriptor, project, null)
                .mapNotNull { it.takeIf { vf -> !vf.isDirectory } }
                .map { File(it.path) }
            addAttachmentFiles(files)
        }
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
        val added = normalizeAttachmentFiles(files)
        if (added.isEmpty()) return
        added.forEach(::addAttachment)
    }

    private fun restoreDraftAttachments(payload: JsonObject) {
        val items = payload.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray ?: com.google.gson.JsonArray()
        draftAttachments.clear()
        items.forEach { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            draftAttachments += AgentAttachmentState(
                id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: java.util.UUID.randomUUID().toString(),
                name = obj.get("file_name")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: "",
                path = obj.get("path")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                mimeType = obj.get("content_type")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: obj.get("mimeType")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: "",
                size = obj.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                kind = obj.get("kind")?.takeIf { it.isJsonPrimitive }?.asString ?: AgentAttachmentKind.FILE.id,
            )
        }
        refreshDraftAttachmentStrip()
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
        chatBrowser.patchState(com.google.gson.JsonObject().apply {
            add("drafts", com.google.gson.Gson().toJsonTree(draftAttachments.map { it.toBrowserAttachment() }).asJsonArray)
        })
    }

    // -------- 发送与运行 --------

    private fun ensureCurrentSessionForSend(): ChatSessionRecord {
        currentSessionId?.let { ChatSessionService.visibleSessionById(it, currentProjectPath()) }?.let { return it }
        throw IllegalStateException("请先创建编码环境和会话")
    }

    private fun sendMessage(): com.google.gson.JsonObject {
        val prompt = inputArea.text.trim()
        val fileContext = if (AgentEditorFileContextSupport.isEnabled(currentProjectPath())) {
            AgentEditorFileContextSupport.collectAll(project)
        } else {
            emptyList()
        }
        val promptWithContext = AgentEditorFileContextSupport.prependToPrompt(prompt, fileContext)
        if (promptWithContext.isEmpty() && draftAttachments.isEmpty()) {
            throw IllegalArgumentException("请输入消息或添加附件")
        }
        val record = ensureCurrentSessionForSend()
        val attachmentIds = draftAttachments.map { CodingAttachmentStore.persist(record.id, it) }
        val task = MessageProcessor.enqueue(record.id, promptWithContext, attachmentIds)
        inputArea.text = ""
        clearDraftAttachments()
        return task
    }

    private fun enqueueAgentRunDelivery(delivery: AgentRunDelivery) {
        try {
            MessageProcessor.enqueue(delivery.sessionId, delivery.content)
            AgentRunService.markDeliveryAccepted(delivery.runId)
        } catch (error: Throwable) {
            project.errorNotify("对话", error.message ?: "无法接收 Agent 运行投递")
        }
    }

    private fun persistChosenAttachments(): List<JsonObject> {
        val sessionId = currentSessionId ?: throw IllegalStateException("没有选中会话")
        val files = onUiWait {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, true).withTitle("选择附件")
            FileChooser.chooseFiles(descriptor, project, null)
                .mapNotNull { it.takeIf { vf -> !vf.isDirectory } }
                .map { File(it.path) }
        }
        return persistAttachmentFiles(sessionId, files)
    }

    private fun persistPastedAttachments(): List<JsonObject> {
        val sessionId = currentSessionId ?: throw IllegalStateException("没有选中会话")
        val transferable = onUiWait { systemClipboardContents() ?: CopyPasteManager.getInstance().contents } ?: return emptyList()
        val files = AgentAttachmentClipboardSupport.extractFiles(transferable).toMutableList()
        if (files.isEmpty()) {
            imageFromTransferable(transferable)?.path?.takeIf { it.isNotBlank() }?.let { files += File(it) }
        }
        if (files.isEmpty()) {
            files += AgentAttachmentClipboardSupport.parseFiles(clipboardText(transferable))
        }
        return persistAttachmentFiles(sessionId, files)
    }

    private fun persistAttachmentFiles(sessionId: Long, files: List<File>): List<JsonObject> =
        normalizeAttachmentFiles(files).map { draft ->
            val id = CodingAttachmentStore.persist(sessionId, draft)
            val stored = MessageStoreService.listAttachmentsByIds(sessionId, listOf(id)).singleOrNull()
                ?: throw IllegalStateException("附件 `$id` 写入后无法读取")
            JsonObject().apply {
                addProperty("id", stored.id)
                addProperty("file_name", stored.fileName)
                addProperty("name", stored.fileName)
                addProperty("path", stored.path)
                addProperty("content_type", stored.contentType)
                addProperty("mimeType", stored.contentType)
                addProperty("size", stored.size)
                addProperty("kind", stored.kind)
            }
        }

    private fun payloadAttachmentIds(payload: JsonObject): List<Long> {
        val value = payload.get("attachments") ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { item ->
            when {
                item.isJsonPrimitive && item.asJsonPrimitive.isNumber -> item.asLong
                item.isJsonPrimitive && item.asJsonPrimitive.isString -> item.asString.toLongOrNull()
                item.isJsonObject -> item.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
                    ?: item.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                else -> null
            }
        }
    }

    private fun onUi(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) action()
        }
    }

    private fun <T> onUiWait(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        val result = java.util.concurrent.atomic.AtomicReference<T>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        application.invokeAndWait {
            runCatching(action).onSuccess(result::set).onFailure(failure::set)
        }
        failure.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get()
    }

    private fun normalizeAttachmentFiles(files: List<File>): List<AgentAttachmentState> = files
        .filter(File::isFile)
        .map { AgentAttachmentSupport.normalize(AgentAttachmentState(name = it.name, path = it.absolutePath, size = it.length())) }

    private fun showEmptySessionState() {
        currentSessionId = null
        rememberActiveSession(null)
        browserEvents.clear()
        browserTasks.clear()
        historyRevision++
        refreshSessionSelector(emptyList())
        syncBrowserState()
    }

    private fun requireSelectedEnvironment(): com.lhstack.tools.db.service.CodingEnvironmentRecord {
        val currentId = currentSessionId?.let(ChatSessionService::sessionById)?.codingEnvironmentId
        val environments = CodingEnvironmentService.listEnabled()
        require(environments.isNotEmpty()) { "没有可用编码环境，请先在环境管理中创建" }
        return environments.firstOrNull { it.id == currentId } ?: environments.first()
    }

    private fun resolveWorkspace(): String =
        project.basePath?.replace("\\", "/")?.trim()?.ifBlank { null } ?: System.getProperty("user.dir")


    private fun previewAttachment(payload: com.google.gson.JsonObject): Map<String, String> {
        val path = payload.get("path")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        if (path.isNotEmpty()) {
            val draft = draftAttachments.firstOrNull { it.path == path }
                ?: AgentAttachmentSupport.normalize(AgentAttachmentState(name = File(path).name, path = path, size = File(path).length()))
            val mime = draft.mimeType.ifBlank { "application/octet-stream" }
            val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(draft.path))
            return mapOf(
                "url" to "data:$mime;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}",
                "fileName" to draft.name,
                "contentType" to mime,
            )
        }
        val sessionId = payload.get("sessionId")?.takeUnless { it.isJsonNull }?.asString?.toLongOrNull()
            ?: error("缺少会话 ID")
        val attachmentId = payload.get("id")?.takeUnless { it.isJsonNull }?.asString?.toLongOrNull()
            ?: error("缺少附件 ID")
        val attachment = MessageStoreService.listAttachmentsByIds(sessionId, listOf(attachmentId)).singleOrNull()
            ?: error("附件 `$attachmentId` 不存在")
        val mime = attachment.contentType.ifBlank { "application/octet-stream" }
        val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(attachment.path))
        return mapOf(
            "url" to "data:$mime;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}",
            "fileName" to attachment.fileName,
            "contentType" to mime,
            "kind" to attachment.kind,
        )
    }

    private fun saveCodeBlock(payload: JsonObject): Map<String, Any?> {
        val content = payload.get("text")?.takeUnless { it.isJsonNull }?.asString
            ?: error("缺少代码内容")
        val suggested = CodeBlockSaveSupport.suggestedFileName(
            payload.get("filename")?.takeUnless { it.isJsonNull }?.asString,
        )
        val extension = CodeBlockSaveSupport.extensionOf(suggested) ?: "txt"
        val fileName = suggested
        return onUiWait {
            val descriptor = FileSaverDescriptor("保存代码块", "选择保存位置并设置文件名", extension)
            descriptor.isForcedToUseIdeaFileChooser = true
            val wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save(project.guessProjectDir(), fileName)
            val file = wrapper?.file ?: return@onUiWait mapOf("saved" to false)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            mapOf("saved" to true, "path" to file.absolutePath)
        }
    }

    private fun openAttachment(attachment: AgentAttachmentState) {
        openAttachmentPath(attachment.path)
    }

    private fun openAttachmentPath(rawPath: String) {
        onUiWait { openAttachmentPathOnEdt(rawPath) }
    }

    private fun openAttachmentPathOnEdt(rawPath: String) {
        val path = rawPath.trim().ifBlank { return }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    private fun openSessionManager() {
        val sessions = visibleSessions()
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
                            visibleSessions().forEach { listModel.addElement(it) }
                        }
                    })
                    add(JButton("删除").apply {
                        addActionListener {
                            val record = list.selectedValue ?: return@addActionListener
                            deleteSession(record)
                            listModel.clear()
                            visibleSessions().forEach { listModel.addElement(it) }
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
        refreshSessionSelector(visibleSessions())
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
        appendLine("Agent ID: ${record.agentId}")
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

    private fun updateStatus() {
        syncBrowserState()
    }

    override fun dispose() {
        disposed = true
        browserStateTimer.stop()
        agentRunDeliverySubscription?.close()
        if (chatBrowser.component.parent != null) Disposer.dispose(chatBrowser)
        Disposer.dispose(managementWindows)
    }

    /** 面板被选中时刷新会话与 Agent 列表，供入口 action 回调。 */
    fun refreshStatus() {
        val selectedAgentId = (agentSelector.selectedItem as? AgentRecord)?.id
        refreshAgentSelector(selectedAgentId)
        refreshSessionSelector(visibleSessions())
        updateStatus()
    }

    private fun handleBrowserCommand(command: AgentBrowserCommand): Any? {
        val payload = command.payload
        val id = payload.get("id")?.takeUnless { it.isJsonNull }?.asString
        val text = payload.get("text")?.takeUnless { it.isJsonNull }?.asString
        return when (command.type) {
            "ui.ready" -> syncBrowserState()
            "session.select" -> id?.toLongOrNull()?.let(::switchSession)
            "session.tasks" -> com.google.gson.JsonObject().apply {
                val sessionId = id?.toLongOrNull() ?: currentSessionId ?: error("缺少会话 ID")
                add("tasks", com.google.gson.JsonArray().apply {
                    MessageStoreService.sessionTasks(sessionId).map(MessageStoreService::sessionTaskJson).forEach(::add)
                })
            }
            "session.new" -> createSession(
                payload.get("sessionType")?.takeUnless { it.isJsonNull }?.asString
                    ?.let(ChatSessionType::from)
                    ?: ChatSessionType.PROJECT,
                payload.get("name")?.takeUnless { it.isJsonNull }?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: AUTO_TITLE,
            )
            "session.clear" -> clearCurrentSessionFromBrowser()
            "session.modelSettings.get" -> currentSessionModelSettings()
            "session.modelSettings.save" -> saveSessionModelSettings(payload)
            "context.get" -> currentSessionId?.let(ContextCompactionService::contextStatus) ?: error("没有选中会话")
            "context.compact" -> currentSessionId?.let(ContextCompactionService::compact) ?: error("没有选中会话")
            "skills.runtime" -> currentSessionId?.let(ChatSessionService::sessionById)?.agentId?.let(CodingRuntimeSupport::runtimeSkillsJson) ?: com.google.gson.JsonArray()
            "cache.refresh" -> refreshBrowserMessageCache()
            "environment.select" -> id?.toLongOrNull()?.let { environmentId ->
                bindCurrentSessionEnvironment(environmentId)
            }
            "message.send" -> { inputArea.text = text.orEmpty(); sendMessage() }
            "window.open" -> onUiWait { managementWindows.open(text ?: error("缺少管理页面")) }
            "code.copy" -> CopyPasteManager.getInstance().setContents(StringSelection(text.orEmpty()))
            "code.save" -> saveCodeBlock(payload)
            "link.open" -> text?.let { BrowserUtil.browse(it) }
            "attachment.choose" -> chooseAttachments()
            "attachment.paste" -> pasteAttachmentsFromClipboard()
            "attachment.remove" -> id?.let { attachmentId -> draftAttachments.firstOrNull { it.id == attachmentId }?.let(::removeDraftAttachment) }
            "draft.restore" -> restoreDraftAttachments(payload)
            "attachment.open" -> text?.let(::openAttachmentPath)
            "attachment.preview" -> previewAttachment(payload)
            "appendAttachment.choose" -> persistChosenAttachments()
            "appendAttachment.paste" -> persistPastedAttachments()
            "polish.start" -> mapOf(
                "taskId" to AgentPolishService.start(project, text ?: error("缺少润色内容")),
            )
            "polish.poll" -> AgentPolishService.poll(payload.polishTaskId())
            "polish.cancel" -> {
                // 取消会关闭底层 HTTP 连接，放到后台线程，避免阻塞 EDT 卡住界面。
                val taskId = payload.polishTaskId()
                AgentExecutors.shared.submit { AgentPolishService.cancel(taskId) }
                Unit
            }
            "fileContext.toggle" -> {
                val projectPath = currentProjectPath()
                val enabled = payload.get("enabled")?.takeUnless { it.isJsonNull }?.asBoolean
                    ?: !AgentEditorFileContextSupport.isEnabled(projectPath)
                AgentEditorFileContextSupport.setEnabled(projectPath, enabled)
                syncBrowserState()
                enabled
            }
            "queue.stop" -> currentSessionId?.let { sessionId ->
                val taskId = id?.toLongOrNull()
                    ?: return@let MessageProcessor.cancelActiveTaskForSession(sessionId)?.let { target ->
                        mapOf("cancelled" to true, "target" to target.name.lowercase())
                    } ?: mapOf("cancelled" to false, "target" to "none")
                when (MessageStoreService.messageTaskStatus(sessionId, taskId)) {
                    MessageTaskStatus.PENDING -> if (MessageProcessor.cancelPending(sessionId, taskId)) {
                        mapOf("cancelled" to true, "deleted" to true, "target" to "queue")
                    } else {
                        // pending 在查询与删除之间可能已被 Worker claim，按最新状态重试一次。
                        val target = MessageProcessor.cancelActiveTask(sessionId, taskId)
                        mapOf("cancelled" to (target != null), "target" to (target?.name?.lowercase() ?: "none"))
                    }
                    MessageTaskStatus.PROCESSING -> {
                        val target = MessageProcessor.cancelActiveTask(sessionId, taskId)
                        if (target != null) mapOf("cancelled" to true, "target" to target.name.lowercase())
                        else mapOf("cancelled" to false, "target" to "completed")
                    }
                    MessageTaskStatus.COMPLETED,
                    MessageTaskStatus.FAILED,
                    MessageTaskStatus.CANCELLED,
                    null -> mapOf("cancelled" to false, "target" to "completed")
                }
            }
            "queue.append" -> currentSessionId?.let { sessionId ->
                val turnId = payload.get("turnId")?.takeUnless { it.isJsonNull }?.asString
                    ?: error("缺少 turnId")
                MessageProcessor.enqueueAppend(sessionId, turnId, text.orEmpty(), payloadAttachmentIds(payload))
                reloadBrowserTasks(sessionId)
            }
            "queue.edit" -> currentSessionId?.let { sessionId ->
                val taskId = requireNotNull(id?.toLongOrNull()) { "缺少队列消息 ID" }
                MessageProcessor.updatePending(sessionId, taskId, requireNotNull(text) { "缺少队列消息内容" }, payloadAttachmentIds(payload))
                reloadBrowserTasks(sessionId)
            }
            "timeline.event" -> {
                val eventId = requireNotNull(id?.toLongOrNull()) { "缺少事件 ID" }
                MessageStoreService.messageEvent(eventId, true)?.toBrowserJson()
            }
            "timeline.deleteTurn" -> currentSessionId?.let { sessionId ->
                val turnId = payload.get("turnId")?.takeUnless { it.isJsonNull }?.asString
                    ?: error("缺少 turnId")
                MessageProcessor.deleteTurn(sessionId, turnId)
                reloadBrowserConversation(sessionId)
            }
            "attachment.openById" -> {
                val sessionId = payload.get("sessionId")?.asLong ?: currentSessionId ?: error("缺少会话 ID")
                val attachmentId = requireNotNull(id?.toLongOrNull()) { "缺少附件 ID" }
                val attachment = MessageStoreService.listAttachmentsByIds(sessionId, listOf(attachmentId)).firstOrNull()
                    ?: error("附件不存在")
                openAttachmentPath(attachment.path)
            }
            "message.delete" -> currentSessionId?.let { sessionId ->
                val turnId = payload.get("turnId")?.takeUnless { it.isJsonNull }?.asString
                    ?: error("缺少 turnId")
                MessageProcessor.deleteTurn(sessionId, turnId)
                reloadBrowserConversation(sessionId)
            }
            else -> handleBrowserManagementCommand(command.type, payload)
        }
    }

    /** 润色任务 ID；缺失时直接报错，不静默放过导致后续查询到不存在的任务。 */
    private fun JsonObject.polishTaskId(): String =
        get("taskId")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            ?: error("缺少润色任务 ID")

    private fun refreshManagementData() {
        syncSelectedSessionAfterCatalogChange()
    }

    private fun handleBrowserManagementCommand(type: String, payload: JsonObject): Any? =
        AgentBrowserManagement.handle(project, type, payload) {
            syncSelectedSessionAfterCatalogChange()
        }

    private fun syncSelectedSessionAfterCatalogChange() {
        val sessions = visibleSessions()
        if (sessions.isEmpty()) {
            showEmptySessionState()
            return
        }
        val targetId = when {
            currentSessionId != null && sessions.any { it.id == currentSessionId } -> currentSessionId!!
            else -> resolveRestoredSessionId(sessions) ?: sessions.first().id
        }
        if (currentSessionId != targetId) {
            switchSession(targetId)
        } else {
            rememberActiveSession(targetId)
            refreshAgentSelector(ChatSessionService.sessionById(targetId)?.agentId)
            refreshSessionSelector(sessions)
            reloadBrowserConversation(targetId)
            syncBrowserState()
        }
    }

    private fun currentProjectPath(): String =
        ChatSessionService.normalizeProjectPath(project.basePath ?: resolveWorkspace())

    private fun visibleSessions(): List<ChatSessionRecord> =
        ChatSessionService.listVisibleSessions(currentProjectPath())

    private fun activeSessionSettingKey(projectPath: String = currentProjectPath()): String =
        ACTIVE_SESSION_SETTING_PREFIX + ChatSessionService.normalizeProjectPath(projectPath)

    private fun rememberActiveSession(sessionId: Long?) {
        val key = activeSessionSettingKey()
        if (sessionId == null) {
            SettingService.setSetting(key, "")
        } else {
            SettingService.setSetting(key, sessionId.toString())
        }
    }

    private fun loadRememberedSessionId(projectPath: String = currentProjectPath()): Long? {
        val raw = SettingService.setting(activeSessionSettingKey(projectPath))?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun resolveRestoredSessionId(sessions: List<ChatSessionRecord>): Long? {
        val remembered = loadRememberedSessionId() ?: return null
        return sessions.firstOrNull { it.id == remembered }?.id
    }

    private fun syncBrowserState() {
        if (ApplicationManager.getApplication().isDispatchThread) {
            browserStateTimer.restart()
        } else {
            ApplicationManager.getApplication().invokeLater { browserStateTimer.restart() }
        }
    }

    private fun syncBrowserStateNow() {
        val sessions = visibleSessions().map {
            AgentBrowserSession(it.id, it.title, it.sessionType.value, it.projectPath)
        }
        val agents = AgentService.listAgents().mapNotNull { agent -> agent.id?.let { AgentBrowserOption(it, agent.name) } }
        val currentRecord = currentSessionId?.let(ChatSessionService::sessionById)
        val currentAgentId = currentRecord?.agentId
        val currentEnvironmentId = currentRecord?.codingEnvironmentId
        val environments = CodingEnvironmentService.listEnabled().map {
            AgentBrowserOption(it.id, it.name)
        }
        val background = UIUtil.getPanelBackground()
        val fileContextEnabled = AgentEditorFileContextSupport.isEnabled(currentProjectPath())
        val polishAgent = AgentPolishService.configuredAgent()
        val fileContextSnapshots = if (fileContextEnabled) {
            AgentEditorFileContextSupport.collectAll(project)
        } else {
            emptyList()
        }
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
            environments = environments,
            currentSessionId = currentSessionId,
            currentAgentId = currentAgentId,
            currentEnvironmentId = currentEnvironmentId,
            currentSession = currentBrowserSession(),
            providers = browserProviders(),
            prompts = browserPrompts(),
            context = currentSessionId?.let(ContextCompactionService::contextStatus),
            skills = currentSessionId?.let(ChatSessionService::sessionById)?.codingEnvironmentId?.let(CodingRuntimeSupport::runtimeSkillsJson) ?: com.google.gson.JsonArray(),
            messages = emptyList(),
            events = browserEvents.toList(),
            tasks = browserTasks.toList(),
            historyRevision = historyRevision,
            queue = emptyList(),
            drafts = draftAttachments.map { it.toBrowserAttachment() },
            inputRestore = browserInputRestore,
            polishEnabled = polishAgent != null,
            polishAgentName = polishAgent?.name,
            fileContextEnabled = fileContextEnabled,
            fileContextLabel = AgentEditorFileContextSupport.buttonLabel(fileContextEnabled),
            fileContextChip = fileContextSnapshots.takeIf { it.isNotEmpty() }?.let { snapshots ->
                val primary = snapshots.first()
                AgentBrowserFileContextChip(
                    label = AgentEditorFileContextSupport.formatChipLabel(snapshots) ?: primary.fileName,
                    tooltip = AgentEditorFileContextSupport.formatChipTooltip(snapshots) ?: primary.path,
                    path = primary.path,
                    startLine = primary.startLine,
                    endLine = primary.endLine,
                    items = snapshots.map { snap ->
                        AgentBrowserFileContextItem(
                            path = snap.path,
                            startLine = snap.startLine,
                            endLine = snap.endLine,
                        )
                    },
                )
            },
        ))
    }

    private fun onMessageRuntimeEvent(payload: JsonObject) {
        val sessionId = payload.get("session_id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return
        if (currentSessionId != sessionId) return
        onUi {
            when (payload.get("type")?.asString) {
                "message_event" -> {
                    val event = upsertBrowserEvent(payload) ?: return@onUi
                    chatBrowser.patchState(com.google.gson.JsonObject().apply {
                        add("events", com.google.gson.JsonArray().apply { add(event) })
                    })
                }
                "message_task" -> {
                    if (payload.get("deleted")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                        val turnId = payload.get("turn_id")?.takeUnless { it.isJsonNull }?.asString
                        if (!turnId.isNullOrBlank()) {
                            browserEvents.removeAll { it.get("turn_id")?.takeUnless { value -> value.isJsonNull }?.asString == turnId }
                        }
                    }
                    chatBrowser.patchState(com.google.gson.JsonObject().apply {
                        add("message_task", payload)
                    })
                }
                "session_usage" -> {
                    val context = ContextCompactionService.contextStatus(sessionId)
                    chatBrowser.patchState(com.google.gson.JsonObject().apply {
                        add("context", context)
                    })
                }
            }
        }
    }

    private fun upsertBrowserEvent(payload: JsonObject): JsonObject? {
        val id = payload.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
        val index = browserEvents.indexOfFirst { it.get("id")?.asLong == id }
        val event = if (payload.has("event_type")) payload.deepCopy() else {
            MessageStoreService.messageEvent(id, true)?.toBrowserJson() ?: return null
        }
        if (index >= 0) browserEvents[index] = event else browserEvents += event
        browserEvents.sortBy { it.get("id")?.asLong ?: 0L }
        return event
    }

    private fun reloadBrowserConversation(sessionId: Long) {
        val events = MessageStoreService.listMessageEvents(sessionId, includeContext = true).map { it.toBrowserJson() }
        val tasks = MessageStoreService.sessionTasks(sessionId).map(MessageStoreService::sessionTaskJson)
        browserEvents.clear()
        browserEvents += events
        browserTasks.clear()
        browserTasks += tasks
        historyRevision++
        syncBrowserState()
    }

    private fun reloadBrowserTasks(sessionId: Long) {
        browserTasks.clear()
        browserTasks += MessageStoreService.sessionTasks(sessionId).map(MessageStoreService::sessionTaskJson)
        chatBrowser.patchState(com.google.gson.JsonObject().apply {
            addProperty("session_id", sessionId)
            add("tasks", com.google.gson.JsonArray().apply { browserTasks.forEach(::add) })
        })
    }

    private fun currentSessionModelSettings(): JsonObject = currentBrowserSession()
        ?: throw IllegalStateException("没有选中会话")

    private fun saveSessionModelSettings(payload: JsonObject): JsonObject {
        val sessionId = currentSessionId ?: throw IllegalStateException("没有选中会话")
        val providerId = payload.get("provider_id")?.takeUnless { it.isJsonNull }?.asLong
            ?: throw IllegalArgumentException("缺少供应商")
        val modelId = payload.get("model_id")?.takeUnless { it.isJsonNull }?.asLong
            ?: throw IllegalArgumentException("缺少模型")
        val promptId = payload.get("prompt_id")?.takeUnless { it.isJsonNull }?.asLong
        val snapshot = payload.get("model_snapshot")?.takeIf { it.isJsonObject }?.asJsonObject
        val reasoningLevel = payload.get("reasoning_level")?.takeUnless { it.isJsonNull }?.asString
        val reasoningConfig = payload.get("reasoning_config")?.takeIf { it.isJsonObject }?.asJsonObject
        val maxHistoryRounds = payload.get("max_history_rounds")?.takeUnless { it.isJsonNull }?.asInt
        ChatSessionService.updateSessionSettings(
            id = sessionId,
            providerId = providerId,
            modelId = modelId,
            promptId = promptId,
            modelSnapshot = snapshot,
            reasoningLevel = reasoningLevel,
            reasoningConfig = reasoningConfig,
            maxHistoryRounds = maxHistoryRounds,
        )
        syncBrowserState()
        return currentBrowserSession() ?: throw IllegalStateException("会话更新后无法读取")
    }

    private fun currentBrowserSession(): JsonObject? {
        val record = currentSessionId?.let(ChatSessionService::sessionById) ?: return null
        return JsonObject().apply {
            addProperty("id", record.id)
            addProperty("title", record.title)
            addProperty("agent_id", record.agentId)
            addProperty("coding_environment_id", record.codingEnvironmentId)
            addProperty("provider_id", record.providerId)
            addProperty("model_id", record.modelId)
            if (record.promptId == null) add("prompt_id", com.google.gson.JsonNull.INSTANCE)
            else addProperty("prompt_id", record.promptId)
            addProperty("cwd", record.cwd)
            add("model_snapshot", record.modelSnapshot)
            record.config.get("reasoning_level")?.let { add("reasoning_level", it) }
            record.config.get("reasoning_config")?.let { add("reasoning_config", it) }
            record.config.get("max_history_rounds")?.let { add("max_history_rounds", it) }
        }
    }

    private fun browserProviders(): List<JsonObject> =
        CatalogService.listProvidersWithModels().map { item ->
            JsonObject().apply {
                addProperty("id", item.provider.id)
                addProperty("name", item.provider.name)
                addProperty("kind", item.provider.kind)
                addProperty("api", item.provider.api)
                addProperty("anthropic_version", item.provider.anthropicVersion)
                add(
                    "provider_config",
                    runCatching { JsonParser.parseString(item.provider.providerConfig) }.getOrNull()
                        ?.takeIf { it.isJsonObject } ?: JsonObject(),
                )
                add("models", JsonArray().apply {
                    item.models.filter { it.enabled == 1 }.forEach { model ->
                        add(JsonObject().apply {
                            addProperty("id", model.id)
                            addProperty("provider_id", model.providerId)
                            addProperty("alias", model.alias)
                            addProperty("model_id", model.modelId)
                            addProperty("display_name", model.displayName)
                            addProperty("api", model.api)
                            model.contextWindow?.let { addProperty("context_window", it) }
                            add(
                                "modalities",
                                runCatching { JsonParser.parseString(model.modalities) }.getOrNull()
                                    ?.takeIf { it.isJsonArray } ?: JsonArray(),
                            )
                            add("model_params", optionalJson(model.modelParams))
                            add("execution_params", optionalJson(model.executionParams))
                            add("additional_params", optionalJson(model.additionalParams))
                        })
                    }
                })
            }
        }

    private fun browserPrompts(): List<JsonObject> =
        CatalogService.listPromptTemplates().map { template ->
            JsonObject().apply {
                addProperty("id", template.id)
                addProperty("name", template.name)
            }
        }

    private fun optionalJson(text: String?): JsonElement {
        val raw = text?.takeIf { it.isNotBlank() } ?: return com.google.gson.JsonNull.INSTANCE
        return runCatching { JsonParser.parseString(raw) }.getOrNull() ?: com.google.gson.JsonNull.INSTANCE
    }

    private fun cssColor(color: Color): String = "#${ColorUtil.toHex(color)}"

    // -------- 渲染数据结构 --------

    private data class AgentBrowserState(
        @SerializedName("dark") val dark: Boolean,
        @SerializedName("theme") val theme: AgentBrowserTheme,
        @SerializedName("sessions") val sessions: List<AgentBrowserSession>,
        @SerializedName("agents") val agents: List<AgentBrowserOption>,
        @SerializedName("environments") val environments: List<AgentBrowserOption>,
        @SerializedName("currentSessionId") val currentSessionId: Long?,
        @SerializedName("currentAgentId") val currentAgentId: Long?,
        @SerializedName("currentEnvironmentId") val currentEnvironmentId: Long?,
        @SerializedName("currentSession") val currentSession: JsonObject?,
        @SerializedName("providers") val providers: List<JsonObject>,
        @SerializedName("prompts") val prompts: List<JsonObject>,
        @SerializedName("context") val context: JsonObject?,
        @SerializedName("skills") val skills: com.google.gson.JsonArray,
        @SerializedName("messages") val messages: List<JsonObject>,
        @SerializedName("events") val events: List<JsonObject>,
        @SerializedName("tasks") val tasks: List<JsonObject>,
        @SerializedName("historyRevision") val historyRevision: Long,
        @SerializedName("queue") val queue: List<JsonObject>,
        @SerializedName("drafts") val drafts: List<AgentBrowserAttachment>,
        @SerializedName("inputRestore") val inputRestore: AgentBrowserInputRestore?,
        @SerializedName("polishEnabled") val polishEnabled: Boolean,
        @SerializedName("polishAgentName") val polishAgentName: String?,
        @SerializedName("fileContextEnabled") val fileContextEnabled: Boolean,
        @SerializedName("fileContextLabel") val fileContextLabel: String,
        @SerializedName("fileContextChip") val fileContextChip: AgentBrowserFileContextChip?,
    )
    private data class AgentBrowserFileContextChip(
        @SerializedName("label") val label: String,
        @SerializedName("tooltip") val tooltip: String,
        @SerializedName("path") val path: String,
        @SerializedName("startLine") val startLine: Int?,
        @SerializedName("endLine") val endLine: Int?,
        @SerializedName("items") val items: List<AgentBrowserFileContextItem> = emptyList(),
    )
    private data class AgentBrowserFileContextItem(
        @SerializedName("path") val path: String,
        @SerializedName("startLine") val startLine: Int?,
        @SerializedName("endLine") val endLine: Int?,
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
    private data class AgentBrowserSession(
        @SerializedName("id") val id: Long,
        @SerializedName("name") val name: String,
        @SerializedName("sessionType") val sessionType: String,
        @SerializedName("projectPath") val projectPath: String?,
    )
    private data class AgentBrowserOption(
        @SerializedName("id") val id: Long,
        @SerializedName("name") val name: String,
    )
}
