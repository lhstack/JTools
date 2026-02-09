package com.lhstack.tools.agent

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.infoNotify
import com.lhstack.tools.plugins.pluginState
import org.jdesktop.swingx.VerticalLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Dimension
import java.util.UUID
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.ListSelectionModel

class AgentProviderConfigPanel(private val project: Project) {
    private val root = JPanel(BorderLayout())
    private val providerListModel = DefaultListModel<AgentProviderState>()
    private val providerList = JBList(providerListModel)
    private val nameField = JBTextField()
    private val typeCombo = ComboBox(AgentProviderType.entries.toTypedArray())
    private val apiKeyField = JPasswordField()
    private val baseUrlField = JBTextField()
    private val headersArea = JBTextArea(3, 0)
    private val headersScroll = JBScrollPane(headersArea)
    private val headerNameField = JBTextField().apply { columns = 12 }
    private val headerValueField = JBTextField().apply { columns = 18 }
    private val headerAddButton = javax.swing.JButton("添加")
    private val maxTokensField = JBTextField()
    private val maxTokensLabel = JLabel("Max Tokens:")
    private val proxyEnabledCheck = JCheckBox("启用")
    private val proxyTypeCombo = ComboBox(AgentProxyType.entries.toTypedArray())
    private val proxyHostField = JBTextField().apply { columns = 22 }
    private val proxyPortField = JBTextField().apply { columns = 6 }
    private val defaultCheck = JCheckBox("设为默认供应方")
    private var currentProvider: AgentProviderState? = null

    val component: JComponent
        get() = root

    init {
        configureFixedWidths()
        buildUi()
        refreshProviderList()
    }

    fun dispose() {}

    private fun buildUi() {
        providerList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        providerList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val provider = value as? AgentProviderState
                val type = AgentProviderType.fromId(provider?.type).displayName
                val text = if (provider == null) "" else "${provider.name} ($type)"
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        providerList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadProvider(providerList.selectedValue)
            }
        }

        val listPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(providerList), BorderLayout.CENTER)
            add(buildListButtons(), BorderLayout.SOUTH)
        }

        val detailPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(buildFormPanel()), BorderLayout.CENTER)
            add(buildActionsPanel(), BorderLayout.SOUTH)
        }

        val split = com.intellij.ui.JBSplitter(false, 0.3f).apply {
            border = JBUI.Borders.empty(8)
            firstComponent = listPanel
            secondComponent = detailPanel
        }

        root.add(split, BorderLayout.CENTER)
    }

    private fun buildListButtons(): JComponent {
        val group = DefaultActionGroup().apply {
            add(iconAction("新增供应方", Icons.mcpAddIcon()) { addProvider() })
            add(iconAction("复制供应方", Icons.mcpCopyIcon()) { duplicateProvider() })
            add(iconAction("删除供应方", Icons.mcpDeleteIcon()) { deleteProvider() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AgentProviderListToolbar", group, true)
        toolbar.targetComponent = providerList
        return toolbar.component
    }

    private fun buildFormPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val labelInsets = JBUI.insets(2, 0, 2, 8)
        val fieldInsets = JBUI.insets(2, 0, 2, 0)
        val constraints = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        var row = 0

        fun addRow(labelComponent: JLabel, field: JComponent) {
            constraints.gridx = 0
            constraints.gridy = row
            constraints.weightx = 0.0
            constraints.insets = labelInsets
            panel.add(labelComponent, constraints)
            constraints.gridx = 1
            constraints.weightx = 0.0
            constraints.insets = fieldInsets
            panel.add(field, constraints)
            row++
        }

        addRow(JLabel("名称:"), nameField)
        addRow(JLabel("类型:"), typeCombo)
        addRow(JLabel("API Key:"), apiKeyField)
        addRow(JLabel("Base URL:"), baseUrlField)
        addRow(JLabel("Headers(KEY=VAL):"), headersScroll)
        addRow(JLabel("Header 快捷添加:"), buildHeaderQuickAddPanel())
        addRow(JLabel("Header 提示:"), JLabel("每行一个，支持 Header: Value 或 Header=Value"))
        addRow(maxTokensLabel, maxTokensField)
        addRow(JLabel("代理启用:"), proxyEnabledCheck)
        addRow(JLabel("代理类型:"), proxyTypeCombo)
        addRow(JLabel("代理地址:"), buildProxyAddressPanel())
        addRow(JLabel("默认:"), defaultCheck)

        typeCombo.addActionListener {
            val selected = typeCombo.selectedItem as? AgentProviderType ?: return@addActionListener
            if (baseUrlField.text.isBlank()) {
                baseUrlField.text = AgentProviderSupport.defaultBaseUrl(selected)
            }
            updateMaxTokensVisibility(selected)
        }
        proxyEnabledCheck.addActionListener { updateProxyFieldsEnabled() }
        updateProxyFieldsEnabled()
        headersArea.toolTipText = "每行一个，支持 Header: Value 或 Header=Value"

        return panel
    }

    private fun buildActionsPanel(): JComponent {
        val group = DefaultActionGroup().apply {
            add(iconAction("保存配置", Icons.mcpSaveIcon()) { saveProvider() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AgentProviderActionsToolbar", group, true)
        toolbar.targetComponent = root
        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.EAST)
        }
    }

    private fun iconAction(text: String, icon: javax.swing.Icon, action: () -> Unit): AnAction {
        return object : AnAction({ text }, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                action()
            }
        }
    }

    private fun refreshProviderList() {
        providerListModel.clear()
        val providers = project.pluginState().agentProviders
        if (providers.isEmpty()) {
            val provider = AgentProviderState().apply {
                id = UUID.randomUUID().toString()
                name = "OpenAI"
                type = AgentProviderType.OPENAI.id
                baseUrl = AgentProviderSupport.defaultBaseUrl(AgentProviderType.OPENAI)
            }
            AgentProviderSupport.normalizeProvider(provider)
            providers.add(provider)
            if (project.pluginState().agentActiveProviderId.isBlank()) {
                project.pluginState().agentActiveProviderId = provider.id
            }
        } else {
            providers.forEach { AgentProviderSupport.normalizeProvider(it) }
        }
        project.pluginState().agentProviders.forEach { providerListModel.addElement(it) }
        if (providerListModel.size > 0 && providerList.selectedIndex < 0) {
            providerList.selectedIndex = 0
        }
    }

    private fun addProvider() {
        val provider = AgentProviderState().apply {
            id = UUID.randomUUID().toString()
            name = "供应方 ${project.pluginState().agentProviders.size + 1}"
            type = AgentProviderType.OPENAI.id
            baseUrl = AgentProviderSupport.defaultBaseUrl(AgentProviderType.OPENAI)
            maxTokens = 1024
        }
        AgentProviderSupport.normalizeProvider(provider)
        project.pluginState().agentProviders.add(provider)
        providerListModel.addElement(provider)
        providerList.setSelectedValue(provider, true)
    }

    private fun duplicateProvider() {
        val current = currentProvider ?: return
        val copy = AgentProviderState().apply {
            id = UUID.randomUUID().toString()
            name = "${current.name} Copy"
            type = current.type
            apiKey = current.apiKey
            baseUrl = current.baseUrl
            customHeaders = current.customHeaders
            maxTokens = current.maxTokens
            models = current.models.toMutableList()
            activeModel = current.activeModel
        }
        AgentProviderSupport.normalizeProvider(copy)
        project.pluginState().agentProviders.add(copy)
        providerListModel.addElement(copy)
        providerList.setSelectedValue(copy, true)
    }

    private fun deleteProvider() {
        val current = currentProvider ?: return
        project.pluginState().agentProviders.remove(current)
        providerListModel.removeElement(current)
        if (project.pluginState().agentActiveProviderId == current.id) {
            project.pluginState().agentActiveProviderId =
                if (providerListModel.size > 0) providerListModel.getElementAt(0).id else ""
        }
        if (providerListModel.size > 0) {
            providerList.selectedIndex = 0
        } else {
            loadProvider(null)
        }
    }

    private fun loadProvider(provider: AgentProviderState?) {
        currentProvider = provider
        headerNameField.text = ""
        headerValueField.text = ""
        if (provider == null) {
            nameField.text = ""
            typeCombo.selectedItem = AgentProviderType.OPENAI
            apiKeyField.text = ""
            baseUrlField.text = ""
            headersArea.text = ""
            maxTokensField.text = ""
            proxyEnabledCheck.isSelected = false
            proxyTypeCombo.selectedItem = AgentProxyType.HTTP
            proxyHostField.text = ""
            proxyPortField.text = ""
            defaultCheck.isSelected = false
            updateMaxTokensVisibility(AgentProviderType.OPENAI)
            updateProxyFieldsEnabled()
            return
        }
        AgentProviderSupport.normalizeProvider(provider)
        val providerType = AgentProviderType.fromId(provider.type)
        nameField.text = provider.name
        typeCombo.selectedItem = providerType
        apiKeyField.text = provider.apiKey
        baseUrlField.text = provider.baseUrl
        headersArea.text = provider.customHeaders
        maxTokensField.text = provider.maxTokens.toString()
        proxyEnabledCheck.isSelected = provider.proxyEnabled
        proxyTypeCombo.selectedItem = AgentProxyType.fromId(provider.proxyType)
        proxyHostField.text = provider.proxyHost
        proxyPortField.text = if (provider.proxyPort > 0) provider.proxyPort.toString() else ""
        defaultCheck.isSelected = project.pluginState().agentActiveProviderId == provider.id
        updateMaxTokensVisibility(providerType)
        updateProxyFieldsEnabled()
    }

    private fun saveProvider() {
        val provider = currentProvider ?: run {
            showError("请先选择供应方")
            return
        }
        val name = nameField.text.trim()
        if (name.isBlank()) {
            showError("供应方名称不能为空")
            return
        }
        val providerType = (typeCombo.selectedItem as? AgentProviderType) ?: AgentProviderType.OPENAI
        if (providerType == AgentProviderType.ANTHROPIC) {
            val maxTokens = maxTokensField.text.trim().toIntOrNull()
            if (maxTokens == null || maxTokens <= 0) {
                showError("Max Tokens 必须是正整数")
                return
            }
            provider.maxTokens = maxTokens
        }
        val headerRaw = headersArea.text
        val headerResult = AgentProviderSupport.parseCustomHeaders(headerRaw)
        if (headerResult.invalidLines.isNotEmpty()) {
            showError("Header 格式错误: ${headerResult.invalidLines.first()}")
            return
        }
        val proxyEnabled = proxyEnabledCheck.isSelected
        val proxyHost = proxyHostField.text.trim()
        val proxyPort = proxyPortField.text.trim().toIntOrNull()
        if (proxyEnabled) {
            if (proxyHost.isBlank()) {
                showError("代理地址不能为空")
                return
            }
            if (proxyPort == null || proxyPort !in 1..65535) {
                showError("代理端口必须是 1-65535")
                return
            }
        }
        provider.name = name
        provider.type = providerType.id
        provider.apiKey = String(apiKeyField.password).trim()
        provider.baseUrl = baseUrlField.text.trim()
        provider.customHeaders = headerRaw.trim()
        AgentProviderSupport.normalizeProvider(provider)
        provider.proxyEnabled = proxyEnabled
        provider.proxyType = (proxyTypeCombo.selectedItem as? AgentProxyType)?.id ?: AgentProxyType.HTTP.id
        provider.proxyHost = proxyHost
        provider.proxyPort = proxyPort ?: 0
        if (defaultCheck.isSelected) {
            project.pluginState().agentActiveProviderId = provider.id
        }
        providerList.repaint()
        showInfo("供应方已保存")
    }

    private fun showError(message: String) {
        project.errorNotify("供应方配置", message)
    }

    private fun showInfo(message: String) {
        project.infoNotify("供应方配置", message)
    }

    private fun configureFixedWidths() {
        val fieldWidth = JBUI.scale(520)
        val mediumWidth = JBUI.scale(240)
        val smallWidth = JBUI.scale(120)
        val hostWidth = JBUI.scale(340)
        val headerNameWidth = JBUI.scale(170)
        val headerValueWidth = JBUI.scale(250)
        setFixedWidth(nameField, fieldWidth)
        setFixedWidth(typeCombo, fieldWidth)
        setFixedWidth(apiKeyField, fieldWidth)
        setFixedWidth(baseUrlField, fieldWidth)
        setFixedWidth(headersScroll, fieldWidth)
        setFixedWidth(maxTokensField, mediumWidth)
        setFixedWidth(proxyTypeCombo, mediumWidth)
        setFixedWidth(proxyHostField, hostWidth)
        setFixedWidth(proxyPortField, smallWidth)
        setFixedWidth(headerNameField, headerNameWidth)
        setFixedWidth(headerValueField, headerValueWidth)
    }

    private fun setFixedWidth(component: JComponent, width: Int) {
        val size = component.preferredSize
        val dimension = Dimension(width, size.height)
        component.preferredSize = dimension
        component.minimumSize = dimension
        component.maximumSize = dimension
    }

    private fun buildHeaderQuickAddPanel(): JComponent {
        headerAddButton.addActionListener { addHeaderFromQuickInput() }
        headerValueField.addActionListener { addHeaderFromQuickInput() }
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("Name"))
            add(headerNameField)
            add(JLabel("Value"))
            add(headerValueField)
            add(headerAddButton)
        }
    }

    private fun addHeaderFromQuickInput() {
        val name = headerNameField.text.trim()
        val value = headerValueField.text.trim()
        if (name.isBlank() || value.isBlank()) {
            showError("Header Name/Value 不能为空")
            return
        }
        val line = "$name: $value"
        val current = headersArea.text
        val separator = if (current.isBlank() || current.endsWith("\n")) "" else "\n"
        headersArea.text = current + separator + line
        headerNameField.text = ""
        headerValueField.text = ""
        headerNameField.requestFocusInWindow()
    }

    private fun buildProxyAddressPanel(): JComponent {
        val portPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel(":"))
            add(proxyPortField)
        }
        return JPanel(BorderLayout(6, 0)).apply {
            add(proxyHostField, BorderLayout.CENTER)
            add(portPanel, BorderLayout.EAST)
        }
    }

    private fun updateMaxTokensVisibility(type: AgentProviderType) {
        val visible = type == AgentProviderType.ANTHROPIC
        maxTokensLabel.isVisible = visible
        maxTokensField.isVisible = visible
        maxTokensLabel.parent?.revalidate()
        maxTokensLabel.parent?.repaint()
    }

    private fun updateProxyFieldsEnabled() {
        val enabled = proxyEnabledCheck.isSelected
        proxyTypeCombo.isEnabled = enabled
        proxyHostField.isEnabled = enabled
        proxyPortField.isEnabled = enabled
    }
}
