package com.lhstack.tools.agent

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.lhstack.tools.db.entity.ModelEntity
import com.lhstack.tools.db.entity.ProviderEntity
import com.lhstack.tools.db.entity.PromptTemplateEntity
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.ResourceConfigService
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.infoNotify
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*

private val CONFIG_GSON = GsonBuilder().setPrettyPrinting().create()

private fun jsonObjectText(value: String?): String = value?.takeIf { it.isNotBlank() }?.let {
    runCatching { CONFIG_GSON.toJson(JsonParser.parseString(it)) }.getOrNull()
} ?: "{}"

private fun parseJsonObjectText(value: String, field: String): String {
    val text = value.trim().ifBlank { "{}" }
    val element = runCatching { JsonParser.parseString(text) }
        .getOrElse { throw IllegalArgumentException("$field 必须是合法 JSON") }
    if (!element.isJsonObject) throw IllegalArgumentException("$field 必须是 JSON object")
    return CONFIG_GSON.toJson(element)
}

private fun configTextArea(rows: Int = 6): JBTextArea = JBTextArea(rows, 0).apply {
    lineWrap = true
    wrapStyleWord = false
    border = JBUI.Borders.empty(4)
}

private fun jsonEditor(area: JBTextArea, rows: Int = 6): JComponent = JBScrollPane(area).apply {
    val height = JBUI.scale(rows * 22 + 28)
    preferredSize = Dimension(0, height)
    minimumSize = Dimension(0, height)
    maximumSize = Dimension(Int.MAX_VALUE, height)
    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
}

private fun formGrid(vararg rows: List<JComponent>): JPanel = JPanel(GridBagLayout()).apply {
    isOpaque = false
    rows.forEachIndexed { rowIndex, row ->
        row.forEachIndexed { columnIndex, component ->
            val constraints = GridBagConstraints().apply {
                gridx = columnIndex
                gridy = rowIndex
                gridwidth = if (row.size == 1) 3 else 1
                weightx = 1.0 / row.size
                weighty = 0.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
                insets = Insets(JBUI.scale(3), JBUI.scale(4), JBUI.scale(3), JBUI.scale(4))
            }
            add(component, constraints)
        }
    }
    add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
        gridx = 0
        gridy = rows.size
        gridwidth = 3
        weightx = 1.0
        weighty = 1.0
        fill = GridBagConstraints.VERTICAL
    })
}

private fun configSection(title: String, content: JComponent): JComponent =
    JPanel(BorderLayout(0, 6)).apply {
        border = JBUI.Borders.empty(8)
        add(JLabel(title), BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

private fun configField(label: String, component: JComponent): JComponent =
    JPanel(BorderLayout(0, 4)).apply {
        border = JBUI.Borders.empty(4)
        add(JLabel(label), BorderLayout.NORTH)
        add(component, BorderLayout.CENTER)
    }

/** awake ModelsView 的 Swing 版本。Provider/Model 页面只暴露 awake 用户页面字段。 */
class AwakeProviderModelConfigPanel(
    private val project: Project,
) {
    private val root = JPanel(BorderLayout())
    private val providers = DefaultListModel<ProviderEntity>()
    private val providerList = JBList(providers)
    private val models = DefaultListModel<ModelEntity>()
    private val modelList = JBList(models).apply { visibleRowCount = 4 }
    private val remoteModels = DefaultListModel<String>()
    private val remoteModelList = JBList(remoteModels)
    private var currentProvider: ProviderEntity? = null
    private var currentModel: ModelEntity? = null
    private val loadingRemoteModels = AtomicBoolean(false)

    private val providerName = JBTextField()
    private val providerKind = JComboBox(arrayOf("OpenAI", "Anthropic"))
    private val providerApiKey = JPasswordField()
    private val providerBaseUrl = JBTextField()
    private val providerApi = JComboBox(arrayOf("继承默认 API", "Chat Completions", "Responses"))
    private val providerProxyUrl = JBTextField()
    private val openaiProviderType = JComboBox(arrayOf("官方 OpenAI", "OpenAI 兼容接口"))
    private val anthropicVersion = JBTextField()
    private val providerEnabled = JCheckBox("启用")

    private val modelProviderLabel = JBTextField()
    private val modelAlias = JBTextField()
    private val modelIdentifier = JBTextField()
    private val modelDisplayName = JBTextField()
    private val modelApi = JComboBox(arrayOf("继承供应商默认 API", "Chat Completions", "Responses"))
    private val modelEnabled = JCheckBox("启用")
    private val modelContextWindow = JSpinner(SpinnerNumberModel(32000L, 1L, Long.MAX_VALUE, 1L))
    private val modalityChecks = linkedMapOf(
        "text" to JCheckBox("文本"),
        "image" to JCheckBox("图片"),
        "audio" to JCheckBox("音频"),
        "video" to JCheckBox("视频"),
        "file" to JCheckBox("文件"),
    )
    private val modelParamsPanel = AwakeModelParamsPanel()

    val component: JComponent get() = root

    init {
        providerList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component =
                super.getListCellRendererComponent(list, (value as? ProviderEntity)?.name.orEmpty(), index, selected, focus)
        }
        modelList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component =
                super.getListCellRendererComponent(list, (value as? ModelEntity)?.alias.orEmpty(), index, selected, focus)
        }
        remoteModelList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component =
                super.getListCellRendererComponent(list, value?.toString().orEmpty(), index, selected, focus)
        }
        providerList.addListSelectionListener { if (!it.valueIsAdjusting) loadProvider(providerList.selectedValue) }
        modelList.addListSelectionListener {
            if (!it.valueIsAdjusting && modelList.selectedValue != null) {
                remoteModelList.clearSelection()
                loadModel(modelList.selectedValue)
            }
        }
        remoteModelList.addListSelectionListener {
            if (!it.valueIsAdjusting && remoteModelList.selectedValue != null) {
                modelList.clearSelection()
                loadRemoteModel(remoteModelList.selectedValue)
            }
        }
        providerKind.addActionListener { rebuildProviderFields(); rebuildModelParams() }
        providerApi.addActionListener { rebuildModelParams() }
        openaiProviderType.addActionListener { rebuildModelParams() }
        modelApi.addActionListener { rebuildModelParams() }
        buildUi()
        refresh()
    }

    fun dispose() = Unit

    private fun buildUi() {
        val providerPane = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(providerList), BorderLayout.CENTER)
            add(buttonRow("新增", "删除", { newProvider() }, { deleteProvider() }), BorderLayout.SOUTH)
        }
        val detail = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            val remoteModelsButton = JButton("获取远端模型").apply { addActionListener { loadRemoteModels() } }
            val detailContent = JPanel(GridBagLayout()).apply {
                val sections = listOf(
                    collapsibleSection("供应商配置", providerForm(), expanded = true),
                    collapsibleSection("模型管理", modelManagementForm(), expanded = true, trailing = remoteModelsButton),
                    collapsibleSection("模型参数", modelParametersForm(), expanded = true),
                )
                sections.forEachIndexed { index, section ->
                    add(section, GridBagConstraints().apply {
                        gridx = 0
                        gridy = index
                        weightx = 1.0
                        weighty = 0.0
                        fill = GridBagConstraints.HORIZONTAL
                        anchor = GridBagConstraints.NORTHWEST
                    })
                }
                add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
                    gridx = 0
                    gridy = sections.size
                    weightx = 1.0
                    weighty = 1.0
                    fill = GridBagConstraints.BOTH
                })
            }
            add(JBScrollPane(detailContent).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            }, BorderLayout.CENTER)
            add(buttonRow("保存供应商", "保存模型", { saveProvider() }, { saveModel() }), BorderLayout.SOUTH)
        }
        root.add(JBSplitter(false, 0.24f).apply {
            firstComponent = providerPane
            secondComponent = detail
        }, BorderLayout.CENTER)
    }

    private fun collapsibleSection(title: String, content: JComponent, expanded: Boolean, trailing: JComponent? = null): JComponent =
        JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(6, 2)
            content.isVisible = expanded
            val header = JLabel(collapseTitle(title, expanded)).apply {
                border = JBUI.Borders.empty(4, 2)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
            }
            header.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    content.isVisible = !content.isVisible
                    header.text = collapseTitle(title, content.isVisible)
                    revalidate()
                    repaint()
                }
            })
            val headerRow = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(header, BorderLayout.WEST)
                if (trailing != null) add(trailing, BorderLayout.EAST)
            }
            add(headerRow, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }

    private fun collapseTitle(title: String, expanded: Boolean): String =
        if (expanded) "▾  $title" else "▸  $title"

    private fun modelParametersForm(): JComponent = JPanel(GridBagLayout()).apply {
        add(modelForm(), GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            weighty = 0.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        })
        add(modelParamsPanel.component, GridBagConstraints().apply {
            gridx = 0
            gridy = 1
            weightx = 1.0
            weighty = 0.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        })
    }

    private fun modelManagementForm(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        val minListSize = Dimension(0, JBUI.scale(110))
        val localPanel = JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("已添加模型"), BorderLayout.NORTH)
            add(JBScrollPane(modelList).apply { minimumSize = minListSize }, BorderLayout.CENTER)
            add(buttonRow("新增", "删除", { newModel() }, { deleteModel() }), BorderLayout.SOUTH)
        }
        val remotePanel = JPanel(BorderLayout(0, 4)).apply {
            add(JLabel("远端模型"), BorderLayout.NORTH)
            add(JBScrollPane(remoteModelList).apply { minimumSize = minListSize }, BorderLayout.CENTER)
        }
        add(OnePixelSplitter(false, 0.5f).apply {
            firstComponent = localPanel
            secondComponent = remotePanel
            preferredSize = Dimension(0, JBUI.scale(260))
        }, BorderLayout.CENTER)
    }

    private fun providerForm(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(formGrid(
            listOf(configField("名称", providerName), configField("类型", providerKind), configField("状态", providerEnabled)),
            listOf(configField("API 密钥", providerApiKey), configField("接口地址", providerBaseUrl), configField("默认 API", providerApi)),
            listOf(configField("代理地址", providerProxyUrl), configField("OpenAI 类型", openaiProviderType)),
        ))
    }

    private fun modelForm(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(formGrid(
            listOf(configField("供应商", modelProviderLabel), configField("别名", modelAlias), configField("模型 ID", modelIdentifier)),
            listOf(configField("显示名称", modelDisplayName), configField("API", modelApi), configField("上下文窗口", modelContextWindow)),
            listOf(configField("能力", modalityPanel()), configField("状态", modelEnabled)),
        ))
    }

    private fun modalityPanel(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        modalityChecks.values.forEach { add(it) }
    }

    private fun buttonRow(leftText: String, rightText: String, left: () -> Unit, right: () -> Unit) = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
        add(JButton(leftText).apply { addActionListener { left() } })
        add(JButton(rightText).apply { addActionListener { right() } })
    }

    private fun refresh() {
        providers.clear()
        CatalogService.listProvidersWithModels().forEach { group -> providers.addElement(group.provider) }
        if (providers.size == 0) newProvider() else providerList.selectedIndex = 0
    }

    private fun loadProvider(provider: ProviderEntity?) {
        currentProvider = provider ?: return
        providerName.text = provider.name
        providerKind.selectedItem = if (provider.kind == "anthropic") "Anthropic" else "OpenAI"
        providerApiKey.text = provider.apiKey.orEmpty()
        providerBaseUrl.text = provider.baseUrl.orEmpty()
        providerApi.selectedItem = when (provider.api.orEmpty()) {
            "responses" -> "Responses"
            "completions" -> "Chat Completions"
            else -> "继承默认 API"
        }
        anthropicVersion.text = provider.anthropicVersion.orEmpty()
        providerEnabled.isSelected = provider.enabled != 0
        val config = runCatching { CONFIG_GSON.fromJson(provider.providerConfig, JsonObject::class.java) }.getOrNull()
        val providerType = config?.get("openai_provider_type")?.asString
        providerProxyUrl.text = config?.get("proxy_url")?.asString.orEmpty()
        openaiProviderType.selectedItem = if (providerType == "compatible") "OpenAI 兼容接口" else "官方 OpenAI"
        rebuildProviderFields()
        models.clear()
        remoteModels.clear()
        provider.id?.let { CatalogService.listModelsByProvider(it).forEach(models::addElement) }
        if (models.size > 0) modelList.selectedIndex = 0 else clearModel()
    }

    private fun rebuildProviderFields() {
        val anthropic = providerKind.selectedItem == "Anthropic"
        providerApi.isEnabled = !anthropic
        openaiProviderType.isEnabled = !anthropic
        anthropicVersion.isEnabled = anthropic
        if (anthropic) providerApi.selectedItem = ""
        root.revalidate()
        root.repaint()
    }

    private fun loadModel(model: ModelEntity?) {
        currentModel = model ?: return
        modelProviderLabel.text = currentProvider?.name.orEmpty()
        modelAlias.text = model.alias
        modelIdentifier.text = model.modelId
        modelDisplayName.text = model.displayName.orEmpty()
        modelApi.selectedItem = when (model.api.orEmpty()) {
            "responses" -> "Responses"
            "completions" -> "Chat Completions"
            else -> "继承供应商默认 API"
        }
        modelEnabled.isSelected = model.enabled != 0
        modelContextWindow.value = model.contextWindow ?: 32000L
        val modalities = runCatching { JsonParser.parseString(model.modalities).asJsonArray.map { it.asString }.toSet() }.getOrDefault(setOf("text"))
        modalityChecks.forEach { (key, check) -> check.isSelected = key in modalities }
        modelParamsPanel.load(
            providerKind = currentProvider?.kind.orEmpty(),
            apiType = effectiveApi(model.api, currentProvider?.api),
            openaiCompatible = isOpenAiCompatible(currentProvider),
            modelParams = model.modelParams,
            executionParams = model.executionParams,
            additionalParams = model.additionalParams,
        )
    }

    private fun clearModel() {
        currentModel = null
        modelProviderLabel.text = currentProvider?.name.orEmpty()
        modelAlias.text = ""
        modelIdentifier.text = ""
        modelDisplayName.text = ""
        modelApi.selectedItem = "继承供应商默认 API"
        modelEnabled.isSelected = true
        modelContextWindow.value = 32000L
        modalityChecks.forEach { (key, check) -> check.isSelected = key == "text" }
        modelParamsPanel.load(currentProvider?.kind.orEmpty(), effectiveApi("", currentProvider?.api), isOpenAiCompatible(currentProvider), null, null, null)
    }

    private fun loadRemoteModels() {
        val provider = currentProvider
            ?: return project.errorNotify("模型列表", "请先选择供应商")
        provider.id
            ?: return project.errorNotify("模型列表", "请先保存供应商后再获取远端模型")
        if (!loadingRemoteModels.compareAndSet(false, true)) return
        remoteModels.clear()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val remoteModels = AwakeRemoteModelService.listModels(provider)
                ApplicationManager.getApplication().invokeLater({
                    loadingRemoteModels.set(false)
                    remoteModels.distinct().forEach(this.remoteModels::addElement)
                }, ModalityState.any())
            } catch (error: Throwable) {
                ApplicationManager.getApplication().invokeLater({
                    loadingRemoteModels.set(false)
                    project.errorNotify("模型列表", error.message ?: "获取远端模型失败")
                }, ModalityState.any())
            }
        }
    }

    private fun loadRemoteModel(model: String?) {
        val provider = currentProvider ?: return
        val modelId = model?.trim().orEmpty()
        if (modelId.isBlank()) return
        currentModel = ModelEntity().apply {
            providerId = provider.id ?: 0
            alias = modelId
            this.modelId = modelId
            displayName = modelId
            enabled = 1
        }
        loadModel(currentModel)
    }

    private fun rebuildModelParams() {
        if (currentModel == null) return
        modelParamsPanel.reload(
            providerKind = currentProviderKind(),
            apiType = effectiveApi(comboApiValue(modelApi.selectedItem), comboApiValue(providerApi.selectedItem)),
            openaiCompatible = isOpenAiCompatible(currentProvider),
        )
    }

    private fun currentProviderKind(): String =
        if (providerKind.selectedItem == "Anthropic") "anthropic" else "openai"

    /** 把界面显示值或数据库原始值统一归一到 API 类型。 */
    private fun comboApiValue(value: Any?): String = when (value?.toString()) {
        "Responses", "responses" -> "responses"
        "Chat Completions", "completions" -> "completions"
        else -> ""
    }

    private fun effectiveApi(modelApi: String?, providerApi: String?): String =
        comboApiValue(modelApi).ifBlank { comboApiValue(providerApi).ifBlank { "completions" } }

    private fun isOpenAiCompatible(provider: ProviderEntity?): Boolean {
        if (provider?.kind != "openai") return false
        if (provider === currentProvider && openaiProviderType.selectedItem == "OpenAI 兼容接口") return true
        return runCatching {
            CONFIG_GSON.fromJson(provider.providerConfig, JsonObject::class.java)
                ?.get("openai_provider_type")?.asString == "compatible"
        }.getOrDefault(false)
    }

    private fun newProvider() {
        currentProvider = ProviderEntity().apply {
            name = "OpenAI"
            kind = "openai"
            baseUrl = "https://api.openai.com/v1"
            api = "responses"
            providerConfig = "{\"openai_provider_type\":\"official\"}"
            enabled = 1
        }
        loadProvider(currentProvider)
    }

    private fun deleteProvider() {
        currentProvider?.id?.let { CatalogService.deleteProvider(it) }
        refresh()
    }

    private fun newModel() {
        val provider = currentProvider ?: return
        currentModel = ModelEntity().apply {
            providerId = provider.id ?: 0
            alias = "new-model"
            modelId = "new-model"
            enabled = 1
        }
        loadModel(currentModel)
    }

    private fun deleteModel() {
        currentModel?.id?.let { CatalogService.deleteModel(it) }
        currentProvider?.let(::loadProvider)
    }

    private fun saveProvider() {
        val provider = currentProvider ?: return
        try {
            provider.name = providerName.text.trim()
            provider.kind = if (providerKind.selectedItem == "Anthropic") "anthropic" else "openai"
            provider.apiKey = String(providerApiKey.password).trim().ifBlank { null }
            provider.baseUrl = providerBaseUrl.text.trim().ifBlank { null }
            provider.api = when (providerApi.selectedItem) {
                "Responses" -> "responses"
                "Chat Completions" -> "completions"
                else -> null
            }
            provider.anthropicVersion = anthropicVersion.text.trim().ifBlank { null }
            val config = linkedMapOf<String, String>()
            if (provider.kind == "openai") {
                config["openai_provider_type"] = if (openaiProviderType.selectedItem == "OpenAI 兼容接口") "compatible" else "official"
            }
            providerProxyUrl.text.trim().takeIf { it.isNotEmpty() }?.let { config["proxy_url"] = it }
            provider.providerConfig = CONFIG_GSON.toJson(config)
            provider.enabled = if (providerEnabled.isSelected) 1 else 0
            require(provider.name.isNotBlank()) { "名称不能为空" }
            CatalogService.saveProvider(provider)
            refresh()
            providerList.setSelectedValue(provider, true)
            project.infoNotify("模型配置", "供应商已保存")
        } catch (e: Throwable) {
            project.errorNotify("模型配置", e.message ?: "保存供应商失败")
        }
    }

    private fun saveModel() {
        val model = currentModel ?: return
        try {
            val provider = currentProvider ?: throw IllegalStateException("供应商不存在")
            model.providerId = provider.id ?: model.providerId
            model.alias = modelAlias.text.trim()
            model.modelId = modelIdentifier.text.trim()
            model.displayName = modelDisplayName.text.trim().ifBlank { null }
            model.api = when (modelApi.selectedItem) {
                "Responses" -> "responses"
                "Chat Completions" -> "completions"
                else -> null
            }
            model.contextWindow = (modelContextWindow.value as Number).toLong()
            model.modalities = CONFIG_GSON.toJson(modalityChecks.filterValues { it.isSelected }.keys.ifEmpty { setOf("text") })
            model.modelParams = modelParamsPanel.modelParamsJson()
            model.executionParams = modelParamsPanel.executionParamsJson()
            model.additionalParams = modelParamsPanel.additionalParamsJson()
            model.enabled = if (modelEnabled.isSelected) 1 else 0
            require(model.alias.isNotBlank()) { "别名不能为空" }
            require(model.modelId.isNotBlank()) { "模型 ID 不能为空" }
            CatalogService.saveModel(model)
            loadProvider(provider)
            modelList.setSelectedValue(model, true)
            project.infoNotify("模型配置", "模型已保存")
        } catch (e: Throwable) {
            project.errorNotify("模型配置", e.message ?: "保存模型失败")
        }
    }
}

/** awake PromptsView 的 Swing 版本。 */
class AwakePromptConfigPanel(private val project: Project, private val onChanged: (() -> Unit)? = null) {
    private val root = JPanel(BorderLayout()); private val listModel = DefaultListModel<PromptTemplateEntity>(); private val list = JBList(listModel)
    private val promptName = JBTextField(); private val preamble = configTextArea(18); private var current: PromptTemplateEntity? = null
    val component: JComponent get() = root
    init {
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                l: JList<*>?, v: Any?, i: Int, s: Boolean, f: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(
                l, (v as? PromptTemplateEntity)?.name.orEmpty(), i, s, f,
            )
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) load(list.selectedValue) }
        root.border = JBUI.Borders.empty(8)
        root.add(JBSplitter(false, .28f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(JBScrollPane(list), BorderLayout.CENTER)
                add(buttonRow(), BorderLayout.SOUTH)
            }
            secondComponent = JPanel(BorderLayout(0, 8)).apply {
                border = JBUI.Borders.empty(0, 8, 0, 0)
                add(JPanel(BorderLayout(0, 8)).apply {
                    add(JPanel(BorderLayout(0, 4)).apply {
                        add(JLabel("名称"), BorderLayout.NORTH)
                        add(promptName, BorderLayout.CENTER)
                    }, BorderLayout.NORTH)
                    add(JPanel(BorderLayout(0, 4)).apply {
                        add(JLabel("提示词内容"), BorderLayout.NORTH)
                        add(JBScrollPane(preamble).apply {
                            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                        }, BorderLayout.CENTER)
                    }, BorderLayout.CENTER)
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(JButton("保存").apply { addActionListener { save() } })
                    add(JButton("删除").apply { addActionListener { delete() } })
                }, BorderLayout.SOUTH)
            }
        }, BorderLayout.CENTER)
        refresh()
    }
    fun dispose()=Unit
    private fun buttonRow()=JPanel(FlowLayout(FlowLayout.RIGHT)).apply{add(JButton("新增").apply{addActionListener{newPrompt()}})}
    private fun refresh(){listModel.clear();CatalogService.listPromptTemplates().forEach(listModel::addElement);if(listModel.size>0)list.selectedIndex=0 else newPrompt()}
    private fun load(v:PromptTemplateEntity?){current=v;promptName.text=v?.name.orEmpty();preamble.text=v?.preamble.orEmpty()}
    private fun newPrompt(){current=PromptTemplateEntity().apply{name="新提示词";preamble=""};load(current)}
    private fun save(){val v=current?:return;try{require(promptName.text.trim().isNotBlank()){ "name 不能为空"};v.name=promptName.text.trim();v.preamble=preamble.text;current=CatalogService.savePromptTemplate(v);refresh();project.infoNotify("提示词", "提示词已保存");onChanged?.invoke()}catch(e:Throwable){project.errorNotify("提示词",e.message?:"保存失败")}}
    private fun delete(){current?.id?.let{CatalogService.deletePromptTemplate(it)};refresh();onChanged?.invoke()}
}
