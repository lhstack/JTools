package com.lhstack.tools.agent

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.infoNotify
import com.lhstack.tools.plugins.pluginState
import org.jdesktop.swingx.VerticalLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.net.URI
import java.util.UUID
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class McpConfigPanel(
    private val project: Project,
    private val onInsertText: ((String) -> Unit)? = null,
) {
    private val availabilityService = McpAvailabilityService.getInstance(project)
    private val availabilityListener = McpAvailabilityService.AvailabilityListener { _, _ ->
        SwingUtilities.invokeLater { serverList.repaint() }
    }
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val root = JPanel(BorderLayout())
    private val serverListModel = DefaultListModel<McpServerState>()
    private val serverList = JBList(serverListModel)
    private val globalEnabledCheck = JCheckBox("启用 MCP", project.pluginState().agentMcpEnabled)
    private val nameField = JBTextField()
    private val enabledCheck = JCheckBox("启用此服务")
    private val transportCombo = ComboBox(
        arrayOf(McpTransportType.STDIO.id, McpTransportType.SSE.id, McpTransportType.STREAMABLE_HTTP.id)
    )
    private val stdioCommandField = JBTextField()
    private val stdioArgsArea = JBTextArea(3, 0)
    private val stdioEnvArea = JBTextArea(3, 0)
    private val urlField = JBTextField()
    private val headersArea = JBTextArea(3, 0)
    private val stdioArgsScroll = JBScrollPane(stdioArgsArea)
    private val stdioEnvScroll = JBScrollPane(stdioEnvArea)
    private val headersScroll = JBScrollPane(headersArea)
    private val authTypeCombo = ComboBox(
        arrayOf(McpAuthType.NONE.id, McpAuthType.HEADER.id, McpAuthType.BASIC.id, McpAuthType.QUERY.id)
    )
    private val authHeaderNameField = JBTextField("Authorization")
    private val authHeaderValueField = JBTextField()
    private val authUsernameField = JBTextField()
    private val authPasswordField = JBTextField()
    private val authQueryParamField = JBTextField()
    private val authQueryValueField = JBTextField()
    private val toolPanel = JPanel(VerticalLayout(4))
    private val resourceListModel = DefaultListModel<McpResourceDescriptorState>()
    private val resourceList = JBList(resourceListModel)
    private val promptListModel = DefaultListModel<McpPromptDescriptorState>()
    private val promptList = JBList(promptListModel)
    private var currentServer: McpServerState? = null
    private var formPanel: JPanel? = null
    private val stdioRows = mutableListOf<Row>()
    private val sseRows = mutableListOf<Row>()
    private val authRows = mutableListOf<Row>()
    private val authHeaderRows = mutableListOf<Row>()
    private val authBasicRows = mutableListOf<Row>()
    private val authQueryRows = mutableListOf<Row>()

    val component: JComponent
        get() = root

    init {
        availabilityService.addListener(availabilityListener)
        buildUi()
        refreshServerList()
    }

    fun dispose() {
        availabilityService.removeListener(availabilityListener)
    }

    private fun buildUi() {
        serverList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        serverList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val server = value as? McpServerState
                val text = if (server == null) "" else {
                    val name = server.name.ifBlank { "MCP Server" }
                    if (!server.enabled) {
                        "$name (disabled)"
                    } else {
                        val status = when (availabilityService.getAvailability(server.id)) {
                            true -> "可用"
                            false -> "不可用"
                            else -> "未知"
                        }
                        "$name ($status)"
                    }
                }
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        serverList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadServer(serverList.selectedValue)
            }
        }

        val listPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(serverList), BorderLayout.CENTER)
            add(buildServerListButtons(), BorderLayout.SOUTH)
        }

        val detailContent = JPanel(VerticalLayout(8)).apply {
            add(buildFormPanel())
            add(buildTabsPanel())
        }
        val detailPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(detailContent), BorderLayout.CENTER)
            add(buildActionsPanel(), BorderLayout.SOUTH)
        }

        val split = JBSplitter(false, 0.28f).apply {
            border = JBUI.Borders.empty(8)
            firstComponent = listPanel
            secondComponent = detailPanel
        }

        root.add(globalEnabledCheck, BorderLayout.NORTH)
        root.add(split, BorderLayout.CENTER)
        globalEnabledCheck.addActionListener {
            project.pluginState().agentMcpEnabled = globalEnabledCheck.isSelected
        }
        configureFixedWidths()
    }

    private fun buildServerListButtons(): JComponent {
        val group = DefaultActionGroup().apply {
            add(iconAction("新增 MCP 服务", Icons.mcpAddIcon()) { addServer() })
            add(iconAction("复制 MCP 服务", Icons.mcpCopyIcon()) { duplicateServer() })
            add(iconAction("删除 MCP 服务", Icons.mcpDeleteIcon()) { deleteServer() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("McpServerListToolbar", group, true)
        toolbar.targetComponent = serverList
        return toolbar.component
    }

    private fun buildFormPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        formPanel = panel
        val labelInsets = JBUI.insets(2, 0, 2, 8)
        val fieldInsets = JBUI.insets(2, 0, 2, 0)
        val constraints = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        var row = 0

        fun addRow(label: String, field: JComponent): Row {
            val labelComponent = JLabel(label)
            constraints.gridx = 0
            constraints.gridy = row
            constraints.weightx = 0.0
            constraints.insets = labelInsets
            panel.add(labelComponent, constraints)
            constraints.gridx = 1
            constraints.weightx = 1.0
            constraints.insets = fieldInsets
            panel.add(field, constraints)
            row++
            return Row(labelComponent, field)
        }

        addRow("名称:", nameField)
        addRow("启用:", enabledCheck)
        addRow("传输:", transportCombo)
        stdioRows.add(addRow("STDIO Command:", stdioCommandField))
        stdioRows.add(addRow("STDIO Args(每行一个):", stdioArgsScroll))
        stdioRows.add(addRow("STDIO Env(KEY=VAL):", stdioEnvScroll))
        sseRows.add(addRow("HTTP URL:", urlField))
        sseRows.add(addRow("Headers(KEY=VAL):", headersScroll))
        authRows.add(addRow("Auth Type:", authTypeCombo))
        authHeaderRows.add(addRow("Header Name:", authHeaderNameField))
        authHeaderRows.add(addRow("Header Value:", authHeaderValueField))
        authBasicRows.add(addRow("Basic Username:", authUsernameField))
        authBasicRows.add(addRow("Basic Password:", authPasswordField))
        authQueryRows.add(addRow("Query Param:", authQueryParamField))
        authQueryRows.add(addRow("Query Value:", authQueryValueField))

        transportCombo.addActionListener { updateVisibility() }
        authTypeCombo.addActionListener { updateVisibility() }
        updateVisibility()
        return panel
    }

    private fun buildTabsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val tabs = javax.swing.JTabbedPane()
        toolPanel.isOpaque = false
        tabs.addTab("Tools", JBScrollPane(toolPanel))
        tabs.addTab("Resources", buildResourcePanel())
        tabs.addTab("Prompts", buildPromptPanel())
        panel.add(tabs, BorderLayout.CENTER)
        return panel
    }

    private fun buildResourcePanel(): JComponent {
        resourceList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val resource = value as? McpResourceDescriptorState
                val text = resource?.name?.takeIf { it.isNotBlank() } ?: resource?.uri ?: ""
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        val group = DefaultActionGroup().apply {
            add(iconAction("插入资源内容", Icons.mcpInsertContentIcon(), requiresInsert = true) { insertSelectedResourceContent() })
            add(iconAction("插入资源 URI", Icons.mcpInsertUriIcon(), requiresInsert = true) { insertSelectedResourceUri() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("McpResourceToolbar", group, true)
        toolbar.targetComponent = resourceList
        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(resourceList), BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.SOUTH)
        }
    }

    private fun buildPromptPanel(): JComponent {
        promptList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val prompt = value as? McpPromptDescriptorState
                val text = prompt?.name ?: ""
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        val group = DefaultActionGroup().apply {
            add(iconAction("插入提示模板内容", Icons.mcpInsertPromptIcon(), requiresInsert = true) { insertSelectedPrompt() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("McpPromptToolbar", group, true)
        toolbar.targetComponent = promptList
        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(promptList), BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.SOUTH)
        }
    }

    private fun buildActionsPanel(): JComponent {
        val group = DefaultActionGroup().apply {
            add(iconAction("测试 MCP 连接", Icons.mcpTestIcon()) { testServer() })
            add(iconAction("刷新工具列表", Icons.mcpRefreshToolsIcon()) { refreshTools() })
            add(iconAction("刷新资源列表", Icons.mcpRefreshResourcesIcon()) { refreshResources() })
            add(iconAction("刷新提示列表", Icons.mcpRefreshPromptsIcon()) { refreshPrompts() })
            add(iconAction("保存 MCP 配置", Icons.mcpSaveIcon()) { saveServer() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("McpActionsToolbar", group, true)
        toolbar.targetComponent = root
        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.EAST)
        }
    }

    private fun iconAction(text: String, icon: javax.swing.Icon, requiresInsert: Boolean = false, action: () -> Unit): AnAction {
        return object : AnAction({ text }, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                action()
            }

            override fun update(e: AnActionEvent) {
                if (requiresInsert) {
                    e.presentation.isEnabled = onInsertText != null
                }
            }
        }
    }

    private fun refreshServerList() {
        serverListModel.clear()
        McpSupport.safeServers(project.pluginState().agentMcpServers).forEach { server ->
            McpSupport.ensureServerId(server)
            serverListModel.addElement(server)
        }
        if (serverListModel.size > 0 && serverList.selectedIndex < 0) {
            serverList.selectedIndex = 0
        }
    }

    private fun addServer() {
        val server = McpServerState().apply {
            id = UUID.randomUUID().toString()
            name = "MCP Server ${project.pluginState().agentMcpServers.size + 1}"
            enabled = false
        }
        project.pluginState().agentMcpServers.add(server)
        serverListModel.addElement(server)
        serverList.setSelectedValue(server, true)
        McpClientManager.invalidate(server.id)
    }

    private fun duplicateServer() {
        val current = currentServer ?: return
        val copy = McpServerState().apply {
            id = UUID.randomUUID().toString()
            name = current.name + " Copy"
            enabled = current.enabled
            transport = current.transport
            stdioCommand = current.stdioCommand
            stdioArgs = current.stdioArgs.toMutableList()
            stdioEnv = current.stdioEnv.toMutableMap()
            url = current.url
            headers = current.headers.toMutableMap()
            authType = current.authType
            authHeaderName = current.authHeaderName
            authHeaderValue = current.authHeaderValue
            authUsername = current.authUsername
            authPassword = current.authPassword
            authQueryParam = current.authQueryParam
            authQueryValue = current.authQueryValue
            disabledTools = current.disabledTools.toMutableList()
        }
        project.pluginState().agentMcpServers.add(copy)
        serverListModel.addElement(copy)
        serverList.setSelectedValue(copy, true)
        McpClientManager.invalidate(copy.id)
    }

    private fun deleteServer() {
        val current = currentServer ?: return
        project.pluginState().agentMcpServers.remove(current)
        serverListModel.removeElement(current)
        McpClientManager.invalidate(current.id)
        if (serverListModel.size > 0) {
            serverList.selectedIndex = 0
        } else {
            loadServer(null)
        }
    }

    private fun loadServer(server: McpServerState?) {
        currentServer = server
        if (server == null) {
            nameField.text = ""
            enabledCheck.isSelected = false
            transportCombo.selectedItem = McpTransportType.STDIO.id
            stdioCommandField.text = ""
            stdioArgsArea.text = ""
            stdioEnvArea.text = ""
            urlField.text = ""
            headersArea.text = ""
            authTypeCombo.selectedItem = McpAuthType.NONE.id
            authHeaderNameField.text = ""
            authHeaderValueField.text = ""
            authUsernameField.text = ""
            authPasswordField.text = ""
            authQueryParamField.text = ""
            authQueryValueField.text = ""
            toolPanel.removeAll()
            toolPanel.add(JLabel("未加载"))
            resourceListModel.clear()
            promptListModel.clear()
            return
        }
        McpSupport.ensureServerId(server)
        nameField.text = server.name
        enabledCheck.isSelected = server.enabled
        transportCombo.selectedItem = server.transport
        stdioCommandField.text = server.stdioCommand
        stdioArgsArea.text = McpSupport.formatArgs(server.stdioArgs)
        stdioEnvArea.text = McpSupport.formatKeyValueLines(server.stdioEnv)
        urlField.text = server.url
        headersArea.text = McpSupport.formatKeyValueLines(server.headers)
        authTypeCombo.selectedItem = server.authType
        authHeaderNameField.text = server.authHeaderName
        authHeaderValueField.text = server.authHeaderValue
        authUsernameField.text = server.authUsername
        authPasswordField.text = server.authPassword
        authQueryParamField.text = server.authQueryParam
        authQueryValueField.text = server.authQueryValue
        updateVisibility()
        loadCachedLists(server)
    }

    private fun loadCachedLists(server: McpServerState) {
        val client = McpClientManager.getClient(server)
        renderTools(server, client.getCachedTools())
        val cachedResources = client.getCachedResources()
        resourceListModel.clear()
        cachedResources?.forEach { resourceListModel.addElement(it) }
        val cachedPrompts = client.getCachedPrompts()
        promptListModel.clear()
        cachedPrompts?.forEach { promptListModel.addElement(it) }
    }

    private fun renderTools(server: McpServerState, tools: List<McpToolDescriptorState>?) {
        toolPanel.removeAll()
        if (tools == null) {
            toolPanel.add(JLabel("未加载"))
        } else if (tools.isEmpty()) {
            toolPanel.add(JLabel("未发现工具"))
        } else {
            val disabled = server.disabledTools.toMutableSet()
            tools.forEach { tool ->
                val check = JCheckBox(tool.name, !disabled.contains(tool.name)).apply {
                    toolTipText = tool.description.ifBlank { tool.name }
                    addActionListener {
                        val current = server.disabledTools.toMutableSet()
                        if (isSelected) {
                            current.remove(tool.name)
                        } else {
                            current.add(tool.name)
                        }
                        server.disabledTools = current.toMutableList()
                    }
                }
                toolPanel.add(check)
            }
        }
        toolPanel.revalidate()
        toolPanel.repaint()
    }

    private fun updateVisibility() {
        val transport = McpTransportType.fromId(transportCombo.selectedItem?.toString())
        val isStdio = transport == McpTransportType.STDIO
        setRowsVisible(stdioRows, isStdio)
        setRowsVisible(sseRows, !isStdio)
        setRowsVisible(authRows, !isStdio)
        val authType = McpAuthType.fromId(authTypeCombo.selectedItem?.toString())
        setRowsVisible(authHeaderRows, !isStdio && authType == McpAuthType.HEADER)
        setRowsVisible(authBasicRows, !isStdio && authType == McpAuthType.BASIC)
        setRowsVisible(authQueryRows, !isStdio && authType == McpAuthType.QUERY)
        formPanel?.revalidate()
        formPanel?.repaint()
    }

    private fun configureFixedWidths() {
        val fieldWidth = JBUI.scale(480)
        setFixedWidth(nameField, fieldWidth)
        setFixedWidth(transportCombo, fieldWidth)
        setFixedWidth(stdioCommandField, fieldWidth)
        setFixedWidth(stdioArgsScroll, fieldWidth)
        setFixedWidth(stdioEnvScroll, fieldWidth)
        setFixedWidth(urlField, fieldWidth)
        setFixedWidth(headersScroll, fieldWidth)
        setFixedWidth(authTypeCombo, fieldWidth)
        setFixedWidth(authHeaderNameField, fieldWidth)
        setFixedWidth(authHeaderValueField, fieldWidth)
        setFixedWidth(authUsernameField, fieldWidth)
        setFixedWidth(authPasswordField, fieldWidth)
        setFixedWidth(authQueryParamField, fieldWidth)
        setFixedWidth(authQueryValueField, fieldWidth)
    }

    private fun setFixedWidth(component: JComponent, width: Int) {
        val size = component.preferredSize
        val dimension = Dimension(width, size.height)
        component.preferredSize = dimension
        component.minimumSize = dimension
        component.maximumSize = dimension
    }

    private fun setRowsVisible(rows: List<Row>, visible: Boolean) {
        rows.forEach { row ->
            row.label.isVisible = visible
            row.field.isVisible = visible
        }
    }

    private data class Row(val label: JLabel, val field: JComponent)

    private data class ConnectionConfigSnapshot(
        val transport: String,
        val stdioCommand: String,
        val stdioArgs: List<String>,
        val stdioEnv: Map<String, String>,
        val url: String,
        val headers: Map<String, String>,
        val authType: String,
        val authHeaderName: String,
        val authHeaderValue: String,
        val authUsername: String,
        val authPassword: String,
        val authQueryParam: String,
        val authQueryValue: String,
    )

    private fun showError(message: String) {
        project.errorNotify("MCP", message)
    }

    private fun showInfo(message: String) {
        project.infoNotify("MCP", message)
    }

    private fun requireCurrentServer(): McpServerState? {
        val server = currentServer
        if (server == null) {
            showError("请先选择 MCP 服务")
        }
        return server
    }

    private fun defaultNewServerName(): String {
        return "MCP Server ${project.pluginState().agentMcpServers.size + 1}"
    }

    private fun applyFormToServer(server: McpServerState, defaultName: String) {
        server.name = nameField.text.trim().ifBlank { defaultName }
        server.enabled = enabledCheck.isSelected
        server.transport = transportCombo.selectedItem?.toString() ?: McpTransportType.STDIO.id
        server.stdioCommand = stdioCommandField.text.trim()
        server.stdioArgs = McpSupport.parseArgs(stdioArgsArea.text)
        server.stdioEnv = McpSupport.parseKeyValueLines(stdioEnvArea.text)
        server.url = urlField.text.trim()
        server.headers = McpSupport.parseKeyValueLines(headersArea.text)
        server.authType = authTypeCombo.selectedItem?.toString() ?: McpAuthType.NONE.id
        server.authHeaderName = authHeaderNameField.text.trim()
        server.authHeaderValue = authHeaderValueField.text.trim()
        server.authUsername = authUsernameField.text.trim()
        server.authPassword = authPasswordField.text
        server.authQueryParam = authQueryParamField.text.trim()
        server.authQueryValue = authQueryValueField.text.trim()
    }

    private fun ensureCurrentServerFromForm(): McpServerState? {
        if (currentServer != null) {
            return currentServer
        }
        val server = McpServerState().apply {
            id = UUID.randomUUID().toString()
        }
        applyFormToServer(server, defaultNewServerName())
        project.pluginState().agentMcpServers.add(server)
        serverListModel.addElement(server)
        currentServer = server
        serverList.setSelectedValue(server, true)
        McpClientManager.invalidate(server.id)
        return server
    }

    private fun applyServerChanges(createIfMissing: Boolean): McpServerState? {
        val server = if (createIfMissing) {
            ensureCurrentServerFromForm()
        } else {
            requireCurrentServer()
        } ?: return null
        val defaultName = server.name.ifBlank { "MCP Server" }
        val wasEnabled = server.enabled
        val before = snapshotConnectionConfig(server)
        applyFormToServer(server, defaultName)
        val after = snapshotConnectionConfig(server)
        if (before != after) {
            McpClientManager.invalidate(server.id)
        }
        serverList.repaint()
        if (!wasEnabled && server.enabled) {
            availabilityService.requestCheck(server)
        }
        return server
    }

    private fun snapshotConnectionConfig(server: McpServerState): ConnectionConfigSnapshot {
        return ConnectionConfigSnapshot(
            transport = server.transport,
            stdioCommand = server.stdioCommand,
            stdioArgs = server.stdioArgs.toList(),
            stdioEnv = server.stdioEnv.toMap(),
            url = server.url,
            headers = server.headers.toMap(),
            authType = server.authType,
            authHeaderName = server.authHeaderName,
            authHeaderValue = server.authHeaderValue,
            authUsername = server.authUsername,
            authPassword = server.authPassword,
            authQueryParam = server.authQueryParam,
            authQueryValue = server.authQueryValue
        )
    }

    private fun saveServer() {
        if (applyServerChanges(true) != null) {
            showInfo("配置已保存")
        }
    }

    private fun refreshTools() {
        val server = applyServerChanges(true) ?: return
        if (!validateServerForConnection(server)) {
            return
        }
        renderTools(server, null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val tools = try {
                McpClientManager.getClient(server).listTools(force = true)
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showError("获取工具失败: ${e.message ?: "unknown"}")
                }
                emptyList()
            }
            val disabled = server.disabledTools.toMutableSet()
            ApplicationManager.getApplication().invokeLater {
                renderTools(server, tools)
            }
        }
    }

    private fun refreshResources() {
        val server = applyServerChanges(true) ?: return
        if (!validateServerForConnection(server)) {
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val resources = try {
                McpClientManager.getClient(server).listResources(force = true)
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showError("获取资源失败: ${e.message ?: "unknown"}")
                }
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                resourceListModel.clear()
                resources.forEach { resourceListModel.addElement(it) }
            }
        }
    }

    private fun refreshPrompts() {
        val server = applyServerChanges(true) ?: return
        if (!validateServerForConnection(server)) {
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val prompts = try {
                McpClientManager.getClient(server).listPrompts(force = true)
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showError("获取提示失败: ${e.message ?: "unknown"}")
                }
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                promptListModel.clear()
                prompts.forEach { promptListModel.addElement(it) }
            }
        }
    }

    private fun testServer() {
        val server = applyServerChanges(true) ?: return
        if (!validateServerForConnection(server)) {
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = try {
                McpClientManager.getClient(server).ping()
                true
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showError("连接失败: ${e.message ?: "unknown"}")
                }
                false
            }
            if (ok) {
                ApplicationManager.getApplication().invokeLater {
                    showInfo("连接成功")
                }
            }
        }
    }

    private fun insertSelectedResourceUri() {
        val selected = resourceList.selectedValue ?: run {
            showError("请先选择资源")
            return
        }
        val text = selected.uri
        onInsertText?.invoke(text)
    }

    private fun insertSelectedResourceContent() {
        val server = requireCurrentServer() ?: return
        val selected = resourceList.selectedValue ?: run {
            showError("请先选择资源")
            return
        }
        if (!validateServerForConnection(server)) {
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                McpClientManager.getClient(server).readResource(selected.uri)
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showError("读取资源失败: ${e.message ?: "unknown"}")
                }
                return@executeOnPooledThread
            }
            val text = formatResourceResult(result)
            ApplicationManager.getApplication().invokeLater {
                onInsertText?.invoke(text)
            }
        }
    }

    private fun insertSelectedPrompt() {
        val server = requireCurrentServer() ?: return
        val selected = promptList.selectedValue ?: run {
            showError("请先选择提示")
            return
        }
        if (!validateServerForConnection(server)) {
            return
        }
        val argsInput = Messages.showInputDialog(
            root,
            "请输入 JSON 参数(可选)",
            "提示模板参数",
            null
        )
        val args = parseJsonObject(argsInput)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                McpClientManager.getClient(server).getPrompt(selected.name, args)
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    showError("获取提示失败: ${e.message ?: "unknown"}")
                }
                return@executeOnPooledThread
            }
            val text = formatPromptResult(result)
            ApplicationManager.getApplication().invokeLater {
                onInsertText?.invoke(text)
            }
        }
    }

    private fun parseJsonObject(value: String?): JsonObject? {
        if (value.isNullOrBlank()) {
            return null
        }
        return try {
            JsonParser.parseString(value).asJsonObject
        } catch (_: Throwable) {
            SwingUtilities.invokeLater {
                showError("参数必须是 JSON Object")
            }
            null
        }
    }

    private fun formatResourceResult(result: JsonObject): String {
        val contents = result.getAsJsonArray("contents")
        if (contents != null && contents.size() > 0) {
            val texts = contents.mapNotNull { item ->
                val obj = item.asJsonObject
                obj.get("text")?.takeIf { !it.isJsonNull }?.asString
            }
            if (texts.isNotEmpty()) {
                return texts.joinToString("\n")
            }
        }
        return gson.toJson(result)
    }

    private fun formatPromptResult(result: JsonObject): String {
        val prompt = result.getAsJsonObject("prompt") ?: result
        val messages = prompt.getAsJsonArray("messages")
        if (messages != null && messages.size() > 0) {
            val parts = messages.mapNotNull { element ->
                val obj = element.asJsonObject
                val role = obj.get("role")?.asString ?: "system"
                val content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("text")?.takeIf { !it.isJsonNull }?.asString
                    ?: ""
                "$role: $content"
            }
            return parts.joinToString("\n")
        }
        return gson.toJson(result)
    }

    private fun validateServerForConnection(server: McpServerState): Boolean {
        val transport = McpTransportType.fromId(server.transport)
        if (transport == McpTransportType.STDIO) {
            if (server.stdioCommand.isBlank()) {
                showError("STDIO Command 不能为空")
                return false
            }
        } else {
            if (server.url.isBlank()) {
                showError("HTTP URL 不能为空")
                return false
            }
            if (!isValidUrl(server.url)) {
                showError("HTTP URL 需要包含协议，例如 http:// 或 https://")
                return false
            }
            val authType = McpAuthType.fromId(server.authType)
            when (authType) {
                McpAuthType.HEADER -> {
                    if (server.authHeaderName.isBlank() || server.authHeaderValue.isBlank()) {
                        showError("Header 认证需要填写 Header Name 与 Header Value")
                        return false
                    }
                }
                McpAuthType.BASIC -> {
                    if (server.authUsername.isBlank() || server.authPassword.isBlank()) {
                        showError("Basic 认证需要填写用户名与密码")
                        return false
                    }
                }
                McpAuthType.QUERY -> {
                    if (server.authQueryParam.isBlank() || server.authQueryValue.isBlank()) {
                        showError("Query 认证需要填写参数名与值")
                        return false
                    }
                }
                else -> Unit
            }
        }
        return true
    }

    private fun isValidUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            !uri.scheme.isNullOrBlank()
        } catch (_: Throwable) {
            false
        }
    }
}
