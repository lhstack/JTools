package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.plugins.pluginState
import org.jdesktop.swingx.VerticalLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class AgentChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val messageContainer = JPanel(VerticalLayout(8))
    private val chatScroll = JBScrollPane(messageContainer)
    private val inputArea = JBTextArea(3, 0)
    private val sendButton = JButton("发送")
    private val clearButton = JButton("清空")
    private val statusLabel = JLabel()
    private val inputHintLabel = JLabel("Shift+Enter 发送, Enter 换行")
    private val client = AgentClient()
    private val messages = mutableListOf<JsonObject>()
    private val sending = AtomicBoolean(false)

    private var assistantBlock: MessageBlock? = null
    private var reasoningBlock: MessageBlock? = null
    private var suppressToolMarkup = false

    init {
        setupChatContainer()
        setupInputArea()
        val root = JPanel(BorderLayout())
        root.add(buildTopBar(), BorderLayout.NORTH)
        root.add(buildChatContainer(), BorderLayout.CENTER)
        root.add(buildInputBar(), BorderLayout.SOUTH)
        setContent(root)
        resetMessages()
        updateStatus()
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

    private fun buildTopBar(): JComponent {
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(clearButton.apply {
                addActionListener {
                    clearConversation()
                }
            })
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 0, 8)
            add(statusLabel, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.EAST)
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
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            add(inputHintLabel, BorderLayout.NORTH)
            add(inputScroll, BorderLayout.CENTER)
            add(sendButton.apply {
                addActionListener { sendMessage() }
            }, BorderLayout.EAST)
        }
    }

    private fun sendMessage() {
        if (sending.get() || !sendButton.isEnabled || !inputArea.isEnabled) {
            return
        }
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
        val model = project.pluginState().agentModel.trim().ifBlank { "gpt-4o-mini" }
        val maxToolIterations = project.pluginState().agentMaxToolIterations.takeIf { it > 0 } ?: 5
        inputArea.text = ""
        appendMessage("用户", text, collapsible = false, collapsedByDefault = false)
        sendButton.isEnabled = false
        clearButton.isEnabled = false
        setInputEnabled(false)
        updateStatus()

        val userMessage = JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", text)
        }
        messages.add(userMessage)

        ApplicationManager.getApplication().executeOnPooledThread {
            val toolRegistry = AgentToolRegistry.build(project)
            val assistantStarted = AtomicBoolean(false)
            val reasoningStarted = AtomicBoolean(false)
            val reasoningClosed = AtomicBoolean(false)
            val streamedContent = AtomicBoolean(false)
            var toolStreamingUsed = false
            val result = client.complete(
                messages,
                toolRegistry,
                apiKey,
                baseUrl,
                model,
                onDelta = { delta ->
                    streamedContent.set(true)
                    ApplicationManager.getApplication().invokeLater {
                        if (reasoningStarted.get() && reasoningClosed.compareAndSet(false, true)) {
                            closeReasoningBlock()
                        }
                        val filtered = filterAssistantDelta(delta)
                        if (filtered.isBlank()) {
                            return@invokeLater
                        }
                        if (assistantStarted.compareAndSet(false, true)) {
                            assistantBlock = createMessageBlock("助手", UIUtil.getLabelForeground(), false, false)
                            addMessageBlock(assistantBlock!!)
                        }
                        appendToBlock(assistantBlock, filtered)
                    }
                },
                onReasoningDelta = { delta ->
                    streamedContent.set(true)
                    ApplicationManager.getApplication().invokeLater {
                        if (reasoningStarted.compareAndSet(false, true)) {
                            reasoningBlock = createMessageBlock("推理", JBColor(0x6A6A6A, 0x9A9A9A), true, false)
                            addMessageBlock(reasoningBlock!!)
                        }
                        appendToBlock(reasoningBlock, delta)
                    }
                },
                onToolCall = { event ->
                    toolStreamingUsed = true
                    ApplicationManager.getApplication().invokeLater {
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
                        appendToolMessage(
                            "工具结果",
                            "name=${toolLog.name}\nresult=${truncate(toolLog.result)}",
                            collapsible = true,
                            collapsedByDefault = true
                        )
                    }
                },
                maxToolIterations = maxToolIterations
            )
            ApplicationManager.getApplication().invokeLater {
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
                sendButton.isEnabled = true
                clearButton.isEnabled = true
                setInputEnabled(true)
                sending.set(false)
            }
        }
    }

    private fun appendMessage(role: String, content: String, collapsible: Boolean, collapsedByDefault: Boolean) {
        val color = if (role == "推理") JBColor(0x6A6A6A, 0x9A9A9A) else UIUtil.getLabelForeground()
        val block = createMessageBlock(role, color, collapsible, collapsedByDefault)
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
        val block = createMessageBlock(role, color, collapsible, collapsedByDefault)
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
        collapsedByDefault: Boolean
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

        return MessageBlock(panel, contentArea)
    }

    private fun clearConversation() {
        messageContainer.removeAll()
        messageContainer.revalidate()
        messageContainer.repaint()
        assistantBlock = null
        reasoningBlock = null
        resetMessages()
    }

    private fun resetMessages() {
        messages.clear()
        val systemMessage = JsonObject().apply {
            addProperty("role", "system")
            addProperty(
                "content",
                "你是 JTools 智能体, 可调用工具完成任务。插件工具名称以 plugin_ 开头, 系统工具以 jtools_ 开头。避免连续重复调用同一个工具, 如果无法获得新信息请停止并向用户说明。不要在回答内容中输出任何 tool_call/tool_result 标记或 XML 块。"
            )
        }
        messages.add(systemMessage)
    }

    private fun updateStatus() {
        val apiKey = project.pluginState().agentOpenApiKey.trim()
        val baseUrl = project.pluginState().agentOpenApiBaseUrl.trim().ifBlank { "https://api.openai.com/v1" }
        val model = project.pluginState().agentModel.trim().ifBlank { "gpt-4o-mini" }
        statusLabel.text = if (apiKey.isBlank()) {
            "API Key 未配置 | Base URL: $baseUrl | Model: $model"
        } else {
            "OpenAPI: 已配置 | Base URL: $baseUrl | Model: $model"
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

    private data class MessageBlock(val panel: JComponent, val textArea: JBTextArea)
}
