package com.lhstack.tools.agent

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.infoNotify
import com.lhstack.tools.plugins.pluginState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.UUID
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

class AgentProviderConfigPanel(private val project: Project,private val saveCallback: (state: AgentProviderState) -> Unit)  {
    private val root = JPanel(BorderLayout())
    private val providerListModel = DefaultListModel<AgentProviderState>()
    private val providerList = JBList(providerListModel)

    private val nameField = JBTextField()
    private val providerTypeCombo = ComboBox(AgentFormUi.providerTypeOptions())
    private val vendorTemplateCombo = ComboBox<AgentSelectOption>()
    private val apiKeyField = JPasswordField()
    private val baseUrlField = JBTextField()
    private val endpointPathField = JBTextField()
    private val headersArea = JBTextArea(4, 0)
    private val headersScroll = JBScrollPane(headersArea)
    private val headerNameField = JBTextField().apply { columns = 12 }
    private val headerValueField = JBTextField().apply { columns = 18 }
    private val headerAddButton = javax.swing.JButton("添加")
    private val maxTokensField = JBTextField()
    private val proxyEnabledCheck = JCheckBox("启用代理")
    private val proxyTypeCombo = ComboBox(AgentProxyType.entries.toTypedArray())
    private val proxyHostField = JBTextField().apply { columns = 22 }
    private val proxyPortField = JBTextField().apply { columns = 6 }
    private val defaultCheck = JCheckBox("设为默认供应方")
    private var currentProvider: AgentProviderState? = null

    val component: JComponent
        get() = root

    init {
        configureFixedWidths()
        configureScrolling()
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
                val typeLabel = provider?.providerType?.let(AgentFormUi::providerTypeLabel).orEmpty()
                val templateLabel = provider?.vendorTemplate?.let(AgentFormUi::vendorTemplateLabel).orEmpty()
                val text = if (provider == null) "" else "${provider.name} ($typeLabel / $templateLabel)"
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        providerList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadProvider(providerList.selectedValue)
            }
        }

        val listPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 8, 4)
            add(JBScrollPane(providerList), BorderLayout.CENTER)
            add(buildListButtons(), BorderLayout.SOUTH)
        }

        val detailPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 4, 8, 8)
            add(JBScrollPane(buildFormPanel()).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
            add(buildActionsPanel(), BorderLayout.SOUTH)
        }

        root.add(JBSplitter(false, 0.28f).apply {
            firstComponent = listPanel
            secondComponent = detailPanel
        }, BorderLayout.CENTER)
    }

    private fun buildListButtons(): JComponent {
        val group = DefaultActionGroup().apply {
            add(iconAction("新增供应方", Icons.mcpAddIcon()) { addProvider() })
            add(iconAction("复制供应方", Icons.mcpCopyIcon()) { duplicateProvider() })
            add(iconAction("删除供应方", Icons.mcpDeleteIcon()) { deleteProvider() })
        }
        return ActionManager.getInstance().createActionToolbar("AgentProviderListToolbar", group, true).component
    }

    private fun buildFormPanel(): JComponent {
        providerTypeCombo.renderer = optionRenderer()
        vendorTemplateCombo.renderer = optionRenderer()
        refreshVendorTemplates(AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE, AgentProviderCatalog.TEMPLATE_OPENAI)

        providerTypeCombo.addActionListener {
            val providerType = selectedProviderType()
            refreshVendorTemplates(providerType, null)
            if (baseUrlField.text.isBlank()) {
                baseUrlField.text = AgentProviderCatalog.defaultBaseUrl(providerType, selectedVendorTemplate())
            }
            if (endpointPathField.text.isBlank()) {
                endpointPathField.text = AgentProviderCatalog.defaultEndpointPath(providerType)
            }
        }
        vendorTemplateCombo.addActionListener {
            val providerType = selectedProviderType()
            if (baseUrlField.text.isBlank()) {
                baseUrlField.text = AgentProviderCatalog.defaultBaseUrl(providerType, selectedVendorTemplate())
            }
        }
        proxyEnabledCheck.addActionListener { updateProxyFieldsEnabled() }
        headersArea.toolTipText = "每行一个，支持 Header: Value 或 Header=Value"

        val basicCard = AgentFormUi.sectionCard(
            "基础信息",
            "供应方名称、接入类型和模板信息。",
            AgentFormUi.twoColumnGrid(
                AgentFormUi.fieldTile("供应方名称", nameField, "供应方显示名称。"),
                AgentFormUi.fieldTile("接入类型", providerTypeCombo, "SDK 对应的模型接入类型。"),
                AgentFormUi.fieldTile("厂商模板", vendorTemplateCombo, "同类接口下的厂商适配模板。"),
                AgentFormUi.fieldTile("API Key", apiKeyField, "调用当前供应方所需的密钥。"),
            )
        )
        val endpointCard = AgentFormUi.sectionCard(
            "接口配置",
            "接口地址、路径和默认输出长度。",
            AgentFormUi.verticalStack(
                AgentFormUi.twoColumnGrid(
                    AgentFormUi.fieldTile("接口地址", baseUrlField, "接口基础地址。"),
                    AgentFormUi.fieldTile("接口路径", endpointPathField, "聊天接口路径。"),
                    AgentFormUi.fieldTile("默认最大输出", maxTokensField, "供应方级默认输出 token 上限，可留空；模型设置中的值会优先覆盖。"),
                    AgentFormUi.fieldTile("默认供应方", defaultCheck, "保存后将其作为聊天默认供应方。"),
                ),
                AgentFormUi.fieldTile("快捷 Header", buildHeaderQuickAddPanel(), "快速追加单个 Header。"),
                AgentFormUi.fieldTile("Headers", headersScroll, "每行一个 Header。"),
            )
        )
        val proxyCard = AgentFormUi.sectionCard(
            "代理设置",
            "需要代理访问时在这里配置。",
            AgentFormUi.twoColumnGrid(
                AgentFormUi.fieldTile("启用代理", proxyEnabledCheck, "启用后走自定义代理。"),
                AgentFormUi.fieldTile("代理类型", proxyTypeCombo, "支持 HTTP 和 SOCKS。"),
                AgentFormUi.fieldTile("代理地址", buildProxyAddressPanel(), "填写代理主机和端口。"),
                JPanel().apply { isOpaque = false },
            )
        )

        return AgentFormUi.verticalStack(basicCard, endpointCard, proxyCard)
    }

    private fun buildActionsPanel(): JComponent {
        val group = DefaultActionGroup().apply {
            add(iconAction("保存配置", Icons.mcpSaveIcon()) { saveProvider() })
        }
        return JPanel(BorderLayout()).apply {
            add(ActionManager.getInstance().createActionToolbar("AgentProviderActionsToolbar", group, true).component, BorderLayout.EAST)
        }
    }

    private fun addProvider() {
        val provider = AgentProviderState().apply {
            id = UUID.randomUUID().toString()
            name = "供应方 ${project.pluginState().agentProviders.size + 1}"
            providerType = AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE
            vendorTemplate = AgentProviderCatalog.TEMPLATE_OPENAI
            type = AgentFormUi.legacyTypeFor(providerType)
            baseUrl = AgentProviderCatalog.defaultBaseUrl(providerType, vendorTemplate)
            endpointPath = AgentProviderCatalog.defaultEndpointPath(providerType)
            maxTokens = 0
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
            providerType = current.providerType
            vendorTemplate = current.vendorTemplate
            apiKey = current.apiKey
            baseUrl = current.baseUrl
            endpointPath = current.endpointPath
            customHeaders = current.customHeaders
            maxTokens = current.maxTokens
            models = current.models.toMutableList()
            activeModel = current.activeModel
            defaultParameters = LinkedHashMap(current.defaultParameters)
            recommendedParameters = LinkedHashMap(current.recommendedParameters)
            proxyEnabled = current.proxyEnabled
            proxyType = current.proxyType
            proxyHost = current.proxyHost
            proxyPort = current.proxyPort
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

    private fun refreshProviderList() {
        providerListModel.clear()
        val providers = project.pluginState().agentProviders
        if (providers.isEmpty()) {
            addProvider()
            return
        }
        providers.forEach {
            AgentProviderSupport.normalizeProvider(it)
            providerListModel.addElement(it)
        }
        if (providerListModel.size > 0 && providerList.selectedIndex < 0) {
            providerList.selectedIndex = 0
        }
    }

    private fun loadProvider(provider: AgentProviderState?) {
        currentProvider = provider
        headerNameField.text = ""
        headerValueField.text = ""
        if (provider == null) {
            nameField.text = ""
            selectProviderType(AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE)
            refreshVendorTemplates(AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE, AgentProviderCatalog.TEMPLATE_OPENAI)
            apiKeyField.text = ""
            baseUrlField.text = ""
            endpointPathField.text = ""
            headersArea.text = ""
            maxTokensField.text = ""
            proxyEnabledCheck.isSelected = false
            proxyTypeCombo.selectedItem = AgentProxyType.HTTP
            proxyHostField.text = ""
            proxyPortField.text = ""
            defaultCheck.isSelected = false
            updateProxyFieldsEnabled()
            return
        }
        AgentProviderSupport.normalizeProvider(provider)
        nameField.text = provider.name
        selectProviderType(provider.providerType)
        refreshVendorTemplates(provider.providerType, provider.vendorTemplate)
        apiKeyField.text = provider.apiKey
        baseUrlField.text = provider.baseUrl
        endpointPathField.text = provider.endpointPath
        headersArea.text = provider.customHeaders
        maxTokensField.text = provider.maxTokens.takeIf { it > 0 }?.toString().orEmpty()
        proxyEnabledCheck.isSelected = provider.proxyEnabled
        proxyTypeCombo.selectedItem = AgentProxyType.fromId(provider.proxyType)
        proxyHostField.text = provider.proxyHost
        proxyPortField.text = if (provider.proxyPort > 0) provider.proxyPort.toString() else ""
        defaultCheck.isSelected = project.pluginState().agentActiveProviderId == provider.id
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
        val headerRaw = headersArea.text
        val headerResult = AgentProviderSupport.parseCustomHeaders(headerRaw)
        if (headerResult.invalidLines.isNotEmpty()) {
            showError("Header 格式错误: ${headerResult.invalidLines.first()}")
            return
        }
        val maxTokensText = maxTokensField.text.trim()
        val maxTokens = if (maxTokensText.isBlank()) 0 else maxTokensText.toIntOrNull()
        if (maxTokens == null || maxTokens < 0) {
            showError("默认 Max Tokens 必须是非负整数")
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
        val providerType = selectedProviderType()
        provider.name = name
        provider.providerType = providerType
        provider.vendorTemplate = selectedVendorTemplate()
        provider.type = AgentFormUi.legacyTypeFor(providerType)
        provider.apiKey = String(apiKeyField.password).trim()
        provider.baseUrl = baseUrlField.text.trim()
        provider.endpointPath = endpointPathField.text.trim()
        provider.customHeaders = headerRaw.trim()
        provider.maxTokens = maxTokens
        provider.proxyEnabled = proxyEnabled
        provider.proxyType = (proxyTypeCombo.selectedItem as? AgentProxyType)?.id ?: AgentProxyType.HTTP.id
        provider.proxyHost = proxyHost
        provider.proxyPort = proxyPort ?: 0
        AgentProviderSupport.normalizeProvider(provider)
        if (defaultCheck.isSelected) {
            project.pluginState().agentActiveProviderId = provider.id
        }
        providerList.repaint()
        saveCallback(provider)
        showInfo("供应方已保存")
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

    private fun refreshVendorTemplates(providerType: String, selectedTemplate: String?) {
        val options = AgentFormUi.vendorTemplatesFor(providerType)
        vendorTemplateCombo.model = DefaultComboBoxModel(options.toTypedArray())
        val target = options.firstOrNull { AgentFormUi.templateValue(it) == selectedTemplate }
            ?: options.firstOrNull()
        if (target != null) {
            vendorTemplateCombo.selectedItem = target
        }
    }

    private fun selectedProviderType(): String {
        return (providerTypeCombo.selectedItem as? AgentSelectOption)?.id ?: AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE
    }

    private fun selectedVendorTemplate(): String {
        val option = vendorTemplateCombo.selectedItem as? AgentSelectOption ?: return AgentProviderCatalog.TEMPLATE_OPENAI
        return AgentFormUi.templateValue(option)
    }

    private fun selectProviderType(providerType: String) {
        providerTypeCombo.selectedItem = AgentFormUi.providerTypes.firstOrNull { it.id == providerType }
            ?: AgentFormUi.providerTypes.first()
    }

    private fun optionRenderer(): DefaultListCellRenderer {
        return object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val option = value as? AgentSelectOption
                val component = super.getListCellRendererComponent(list, option?.label.orEmpty(), index, isSelected, cellHasFocus) as JLabel
                component.toolTipText = option?.description
                return component
            }
        }
    }

    private fun iconAction(text: String, icon: javax.swing.Icon, action: () -> Unit): AnAction {
        return object : AnAction({ text }, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                action()
            }
        }
    }

    private fun showError(message: String) {
        project.errorNotify("供应方配置", message)
    }

    private fun showInfo(message: String) {
        project.infoNotify("供应方配置", message)
    }

    private fun updateProxyFieldsEnabled() {
        val enabled = proxyEnabledCheck.isSelected
        proxyTypeCombo.isEnabled = enabled
        proxyHostField.isEnabled = enabled
        proxyPortField.isEnabled = enabled
    }

    private fun configureFixedWidths() {
        AgentFormUi.constrainWidth(nameField, 460)
        AgentFormUi.constrainWidth(providerTypeCombo, 460)
        AgentFormUi.constrainWidth(vendorTemplateCombo, 460)
        AgentFormUi.constrainWidth(apiKeyField, 460)
        AgentFormUi.constrainWidth(baseUrlField, 460)
        AgentFormUi.constrainWidth(endpointPathField, 460)
        AgentFormUi.constrainWidth(headersScroll, 720)
        AgentFormUi.constrainWidth(maxTokensField, 220)
        AgentFormUi.constrainWidth(proxyTypeCombo, 220)
        AgentFormUi.constrainWidth(proxyHostField, 300)
        AgentFormUi.constrainWidth(proxyPortField, 100)
        AgentFormUi.constrainWidth(headerNameField, 150)
        AgentFormUi.constrainWidth(headerValueField, 220)
        headersScroll.preferredSize = Dimension(headersScroll.preferredSize.width, JBUI.scale(132))
        headersScroll.minimumSize = Dimension(0, JBUI.scale(96))
    }

    private fun configureScrolling() {
        headersArea.lineWrap = true
        headersArea.wrapStyleWord = true
        headersScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
}
