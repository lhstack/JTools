package com.lhstack.tools.agent

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.treeStructure.Tree
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lhstack.tools.db.service.ResourceConfigService
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.AgentRecord
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.entity.ProviderEntity
import com.lhstack.tools.db.entity.ModelEntity
import com.lhstack.tools.db.entity.PromptTemplateEntity
import com.lhstack.tools.db.config.AgentRuntimeConfig
import com.lhstack.tools.db.config.AgentCapabilityConfig
import com.lhstack.tools.db.config.AgentEnabledItemsConfig
import com.lhstack.tools.db.config.AgentViewResourcesConfig
import com.lhstack.tools.db.config.AgentViewResourceRef
import com.lhstack.tools.db.config.AgentPersonaConfig
import com.lhstack.tools.db.config.AgentDistillConfig
import com.lhstack.tools.db.config.AgentDistillLogKind
import com.lhstack.tools.db.service.ProviderModels
import com.lhstack.tools.agent.model.tools.BuiltinTools
import com.intellij.openapi.ui.ComboBox
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.infoNotify
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.tree.TreeSelectionModel
import javax.swing.tree.TreePath
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultMutableTreeNode

private val RESOURCE_GSON = GsonBuilder().setPrettyPrinting().create()
private fun resourceJson(value: String, field: String, array: Boolean = false): String {
    val element = runCatching { JsonParser.parseString(value.trim().ifBlank { if (array) "[]" else "{}" }) }
        .getOrElse { throw IllegalArgumentException("$field 必须是合法 JSON") }
    require(if (array) element.isJsonArray else element.isJsonObject) { "$field 类型错误" }
    return RESOURCE_GSON.toJson(element)
}

private fun resourceJsonEditor(area: JBTextArea, rows: Int): JComponent = JBScrollPane(area).apply {
    val height = JBUI.scale(rows * 22 + 28)
    preferredSize = java.awt.Dimension(0, height)
    minimumSize = java.awt.Dimension(0, height)
    maximumSize = java.awt.Dimension(Int.MAX_VALUE, height)
    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
}

private fun resourceFormGrid(vararg rows: List<JComponent>): JPanel = JPanel(GridBagLayout()).apply {
    isOpaque = false
    rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, component ->
            add(component, GridBagConstraints().apply {
                gridx = x
                gridy = y
                gridwidth = if (row.size == 1) 2 else 1
                weightx = if (row.size == 1) 1.0 else 0.5
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
                insets = Insets(JBUI.scale(3), JBUI.scale(4), JBUI.scale(3), JBUI.scale(4))
            })
        }
    }
}

/** awake AgentsView 的 DTO 对齐 Swing 页面。复杂嵌套字段按原 JSON key 编辑。 */
/**
 * awake AgentsView 的 Swing 复刻：左侧 Agent 列表，右侧四个折叠分区（模型 / 资源 / 角色 / 蒸馏），
 * 全部使用真实控件编辑，字段与 awake AgentsView 一一对应。
 */
class AwakeAgentConfigPanel(private val project: Project, private val onChanged: (() -> Unit)? = null) {

    private val root = JPanel(BorderLayout())
    private val listModel = DefaultListModel<AgentRecord>()
    private val list = JBList(listModel)
    private var current: AgentRecord? = null
    private var loading = false

    private var providerGroups: List<ProviderModels> = emptyList()
    private var prompts: List<PromptTemplateEntity> = emptyList()
       private var skills: List<ResourceConfigService.SkillDirectoryRecord> = emptyList()

    // 模型分区
    private val nameField = JBTextField()
    private val providerCombo = ComboBox<ProviderEntity>()
    private val modelCombo = ComboBox<ModelEntity>()
    private val promptCombo = ComboBox<PromptOption>()
    private val outputModeCombo = ComboBox(arrayOf("text", "json", "markdown", "structured"))
    private val maxRuntimeSecsField = JBTextField()
    private val tagsField = JBTextField()
    private val enabledCheck = JCheckBox("启用")
    private val descriptionArea = JBTextArea(2, 0)
    private val extraPromptArea = JBTextArea(3, 0)
    private val historyEnabledCombo = ComboBox(arrayOf("禁用", "启用"))
    private val maxHistoryField = JBTextField()
    private val toolCallRetentionRoundsField = JBTextField()

    // 资源分区
    private val toolChecks = linkedMapOf<String, JCheckBox>()
    private val toolsIncludeNew = JCheckBox("包含新增")
    private val pluginFunctionChecks = linkedMapOf<String, JCheckBox>()
    private val pluginFunctionsIncludeNew = JCheckBox("包含新增")
    private var pluginFunctionGroups: List<PluginFunctionToolSupport.Group> = emptyList()
    private val expandedPluginFunctionGroups = mutableSetOf<String>()
    private val skillChecks = linkedMapOf<String, JCheckBox>()
    private val skillsIncludeNew = JCheckBox("包含新增")
    private val skillPromptEnabled = JCheckBox("注入技能提示")
    private val viewResourceControls = linkedMapOf<String, ViewResourceControl>()

    // 角色分区
    private val personaControls = linkedMapOf<String, PersonaControl>()

    // 蒸馏分区
    private val distillEnabled = JCheckBox("启用")
    private val distillAgentCombo = ComboBox<AgentOption>()
    private val distillMinMessagesField = JBTextField()
    private val distillTypeDropdown = CheckBoxMultiSelectDropdown(DISTILL_TYPES)
    private val distillExtraPromptArea = JBTextArea(3, 0)

    val component: JComponent get() = root

    init {
        list.cellRenderer = simpleListRenderer { (it as? AgentRecord)?.name.orEmpty() }
        list.addListSelectionListener { if (!it.valueIsAdjusting) load(list.selectedValue) }
        providerCombo.renderer = simpleListRenderer { (it as? ProviderEntity)?.name.orEmpty() }
        modelCombo.renderer = simpleListRenderer { (it as? ModelEntity)?.alias.orEmpty() }
        promptCombo.renderer = simpleListRenderer { (it as? PromptOption)?.label.orEmpty() }
        distillAgentCombo.renderer = simpleListRenderer { (it as? AgentOption)?.label.orEmpty() }
        providerCombo.addActionListener { if (!loading) onProviderChanged() }
        loadCatalog()
        buildLayout()
        refresh()
    }

    fun dispose() = Unit

    // -------- 布局 --------

    private fun buildLayout() {
        root.border = JBUI.Borders.empty(8)
        val detail = JPanel(BorderLayout(0, 8)).apply {
            add(JBScrollPane(formSections()).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(detailButtons(), BorderLayout.SOUTH)
        }
        root.add(JBSplitter(false, .22f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(JBScrollPane(list), BorderLayout.CENTER)
                add(listButtons(), BorderLayout.SOUTH)
            }
            secondComponent = detail
        }, BorderLayout.CENTER)
    }

    private fun listButtons() = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
        add(JButton("新增").apply { addActionListener { newAgent() } })
    }

    private fun detailButtons() = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
        add(JButton("保存").apply { addActionListener { save() } })
        add(JButton("删除").apply { addActionListener { delete() } })
    }

    private fun formSections(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(agentSection("模型", modelSection()))
        add(agentSection("资源", resourceSection()))
        add(agentSection("角色", personaSection()))
        add(agentSection("蒸馏", distillSection()))
    }

    private fun agentSection(title: String, content: JComponent): JComponent = JPanel(BorderLayout(0, 4)).apply {
        alignmentX = 0f
        border = JBUI.Borders.empty(6, 2)
        val header = JLabel(sectionTitle(title, true)).apply {
            border = JBUI.Borders.empty(4, 2)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }
        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                content.isVisible = !content.isVisible
                header.text = sectionTitle(title, content.isVisible)
                revalidate(); repaint()
            }
        })
        add(header, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    private fun sectionTitle(title: String, expanded: Boolean): String = if (expanded) "▾  $title" else "▸  $title"

    // -------- 模型分区 --------

    private fun modelSection(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(resourceFormGrid(
            listOf(labeled("名称", nameField), labeled("供应商", providerCombo)),
            listOf(labeled("模型", modelCombo), labeled("提示词", promptCombo)),
            listOf(labeled("输出模式", outputModeCombo), labeled("超时(秒)", maxRuntimeSecsField)),
            listOf(labeled("标签(逗号分隔)", tagsField), labeled("状态", enabledCheck)),
            listOf(labeled("描述", resourceJsonEditor(descriptionArea, 2))),
            listOf(labeled("扩展提示(≤512)", resourceJsonEditor(extraPromptArea, 3))),
        ))
        add(JLabel("运行时参数").apply { border = JBUI.Borders.empty(6, 4, 2, 4); font = font.deriveFont(java.awt.Font.BOLD) })
        add(resourceFormGrid(
            listOf(labeled("携带历史", historyEnabledCombo), labeled("最大历史对话轮数", maxHistoryField)),
            listOf(labeled("保留最新工具调用轮次", toolCallRetentionRoundsField)),
        ))
    }

    private fun onProviderChanged() {
        val provider = providerCombo.selectedItem as? ProviderEntity
        reloadModelCombo(provider?.id, null)
    }

    private fun reloadModelCombo(providerId: Long?, selectModelId: Long?) {
        loading = true
        modelCombo.removeAllItems()
        providerGroups.firstOrNull { it.provider.id == providerId }?.models?.forEach { modelCombo.addItem(it) }
        val target = (0 until modelCombo.itemCount).map { modelCombo.getItemAt(it) }.firstOrNull { it.id == selectModelId }
        if (target != null) modelCombo.selectedItem = target else if (modelCombo.itemCount > 0) modelCombo.selectedIndex = 0
        loading = false
    }

    // -------- 资源分区 --------

    private fun resourceSection(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(checkListRow("内置工具", toolChecks, toolsIncludeNew, null))
        add(pluginFunctionsSection())
        add(checkListRow("技能", skillChecks, skillsIncludeNew, skillPromptEnabled))
        add(JLabel("多模态资源代理").apply { border = JBUI.Borders.empty(6, 4, 2, 4); font = font.deriveFont(java.awt.Font.BOLD) })
        add(viewResourceGrid())
    }

    private fun checkListRow(
        title: String,
        checks: Map<String, JCheckBox>,
        includeNew: JCheckBox,
        extra: JCheckBox?,
    ): JComponent =
        JPanel(BorderLayout(0, 4)).apply {
            alignmentX = 0f
            border = JBUI.Borders.empty(4)
            val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(JLabel(title))
                add(includeNew)
                if (extra != null) add(extra)
            }
            add(head, BorderLayout.NORTH)
            val body = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(2))).apply {
                isOpaque = false
                checks.values.forEach(::add)
            }
            add(JBScrollPane(body).apply {
                val height = JBUI.scale(if (checks.isEmpty()) 40 else 72)
                preferredSize = java.awt.Dimension(0, height)
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, height)
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            }, BorderLayout.CENTER)
        }

    private fun pluginFunctionsSection(): JComponent = JPanel(BorderLayout(0, 4)).apply {
        alignmentX = 0f
        border = JBUI.Borders.empty(4)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(JLabel("插件函数"))
            add(pluginFunctionsIncludeNew)
        }, BorderLayout.NORTH)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            if (pluginFunctionGroups.isEmpty()) {
                add(JLabel("暂无插件函数").apply { foreground = UIUtil.getContextHelpForeground() })
            } else {
                pluginFunctionGroups.forEach { group -> add(pluginFunctionGroupPanel(group)) }
            }
        }, BorderLayout.CENTER)
    }

    private fun pluginFunctionGroupPanel(group: PluginFunctionToolSupport.Group): JComponent {
        val expanded = group.pluginKey in expandedPluginFunctionGroups
        val selectedCount = JLabel()
        val body = ScrollableWidthPanel().apply {
            isOpaque = false
            layout = GridBagLayout()
            group.functions.forEachIndexed { index, entry ->
                pluginFunctionChecks[entry.key]?.let { check ->
                    check.addActionListener { updatePluginFunctionGroupCount(group, selectedCount) }
                    add(check, GridBagConstraints().apply {
                        gridx = index % 2
                        gridy = index / 2
                        weightx = 0.5
                        fill = GridBagConstraints.HORIZONTAL
                        anchor = GridBagConstraints.WEST
                        insets = Insets(JBUI.scale(2), JBUI.scale(4), JBUI.scale(2), JBUI.scale(10))
                    })
                }
            }
        }
        val scroll = JBScrollPane(body).apply {
            val height = JBUI.scale(if (group.functions.size <= 4) 74 else 132)
            preferredSize = java.awt.Dimension(0, height)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, height)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            isVisible = expanded
        }
        lateinit var panel: JPanel
        val toggle = JButton(if (expanded) "收起" else "展开").apply {
            isFocusable = false
            addActionListener {
                if (scroll.isVisible) {
                    expandedPluginFunctionGroups.remove(group.pluginKey)
                } else {
                    expandedPluginFunctionGroups.add(group.pluginKey)
                }
                scroll.isVisible = !scroll.isVisible
                text = if (scroll.isVisible) "收起" else "展开"
                panel.revalidate()
                panel.repaint()
            }
        }
        val selectAll = JButton("全选").apply {
            isFocusable = false
            addActionListener {
                group.functions.forEach { entry -> pluginFunctionChecks[entry.key]?.isSelected = true }
                updatePluginFunctionGroupCount(group, selectedCount)
            }
        }
        val clearAll = JButton("全不选").apply {
            isFocusable = false
            addActionListener {
                group.functions.forEach { entry -> pluginFunctionChecks[entry.key]?.isSelected = false }
                updatePluginFunctionGroupCount(group, selectedCount)
            }
        }
        panel = JPanel(BorderLayout(0, 4)).apply {
            alignmentX = 0f
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 6, 0)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JLabel(group.pluginName).apply { font = font.deriveFont(java.awt.Font.BOLD) })
                add(selectedCount)
                add(clearAll)
                add(selectAll)
                add(toggle)
            }, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
        updatePluginFunctionGroupCount(group, selectedCount)
        return panel
    }


    private fun updatePluginFunctionGroupCount(group: PluginFunctionToolSupport.Group, label: JLabel) {
        val selected = group.functions.count { entry -> pluginFunctionChecks[entry.key]?.isSelected == true }
        label.text = "已选 $selected/${group.functions.size}"
        label.foreground = UIUtil.getContextHelpForeground()
    }


    private fun viewResourceGrid(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        VIEW_RESOURCE_KINDS.forEach { (kind, label) ->
            val enabled = JCheckBox(label)
            val agentCombo = ComboBox<AgentOption>().apply { renderer = simpleListRenderer { (it as? AgentOption)?.label.orEmpty() } }
            viewResourceControls[kind] = ViewResourceControl(enabled, agentCombo)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                alignmentX = 0f
                border = JBUI.Borders.empty(2, 4)
                add(enabled, BorderLayout.WEST)
                add(agentCombo, BorderLayout.CENTER)
            })
        }
    }

    // -------- 角色分区 --------

    private fun personaSection(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        PERSONA_ROWS.forEach { (key, label) ->
            val area = JBTextArea(3, 0)
            val maxChars = JBTextField()
            personaControls[key] = PersonaControl(area, maxChars)
            add(JPanel(BorderLayout(0, 4)).apply {
                alignmentX = 0f
                border = JBUI.Borders.empty(4)
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(JLabel(label))
                    add(JLabel("最大字符"))
                    maxChars.columns = 6
                    add(maxChars)
                }, BorderLayout.NORTH)
                add(resourceJsonEditor(area, 3), BorderLayout.CENTER)
            })
        }
    }

    // -------- 蒸馏分区 --------

    private fun distillSection(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(resourceFormGrid(
            listOf(labeled("启用", distillEnabled), labeled("蒸馏 Agent", distillAgentCombo)),
            listOf(labeled("最小消息数", distillMinMessagesField)),
        ))
        add(labeled("蒸馏消息类型", distillTypeDropdown))
        add(labeled("蒸馏额外提示", resourceJsonEditor(distillExtraPromptArea, 3)))
    }

    // -------- 数据加载 --------

    private fun loadCatalog() {
        providerGroups = CatalogService.listProvidersWithModels()
        prompts = CatalogService.listPromptTemplates()
        skills = ResourceConfigService.listSkills()
        pluginFunctionGroups = PluginFunctionToolSupport.groups(project)
        pluginFunctionGroups.mapTo(mutableSetOf()) { it.pluginKey }.let { keys ->
            expandedPluginFunctionGroups.retainAll(keys)
            keys.forEach(expandedPluginFunctionGroups::add)
        }

        providerCombo.removeAllItems()
        providerGroups.forEach { providerCombo.addItem(it.provider) }
        promptCombo.removeAllItems()
        promptCombo.addItem(PromptOption(null, "无"))
        prompts.forEach { promptCombo.addItem(PromptOption(it.id, it.name)) }

        rebuildChecks(toolChecks, BuiltinTools.BUILTIN_TOOLS.map { it.name to it.name })
        rebuildPluginFunctionChecks()
        rebuildChecks(skillChecks, skills.map { it.name to it.name })
    }

    private fun rebuildChecks(target: LinkedHashMap<String, JCheckBox>, entries: List<Pair<String, String>>) {
        target.clear()
        entries.forEach { (key, label) -> target[key] = JCheckBox(label) }
    }

    private fun rebuildPluginFunctionChecks() {
        pluginFunctionChecks.clear()
        pluginFunctionGroups.flatMap { it.functions }.forEach { entry ->
            pluginFunctionChecks[entry.key] = JCheckBox(entry.functionName).apply {
                toolTipText = entry.description
            }
        }
    }

    private fun reloadAgentCombos(excludeId: Long?, selectedDistillId: Long?, selectedViewAgents: Map<String, Long?>) {
        val agents = AgentService.listAgents().filter { it.id != null }
        val allOptions = mutableListOf(AgentOption(null, "无"))
        agents.forEach { allOptions.add(AgentOption(it.id, it.name)) }
        fillAgentCombo(distillAgentCombo, allOptions, selectedDistillId)
        viewResourceControls.forEach { (kind, control) ->
            val options = mutableListOf(AgentOption(null, "无"))
            agents
                .filter { supportsViewResourceKind(it, kind) }
                .forEach { options.add(AgentOption(it.id, it.name)) }
            fillAgentCombo(control.agentCombo, options, selectedViewAgents[kind])
        }
    }

    /** 多模态资源 Agent 只允许选择绑定模型声明了对应能力的 Agent。 */
    private fun supportsViewResourceKind(agent: AgentRecord, kind: String): Boolean {
        if (kind == "file") return true
        val modelId = agent.modelId ?: return false
        val model = CatalogService.modelById(modelId) ?: return false
        val modalities = runCatching {
            JsonParser.parseString(model.modalities)
                .asJsonArray
                .mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
                .toSet()
        }.getOrDefault(emptySet())
        return kind in modalities
    }

    private fun fillAgentCombo(combo: ComboBox<AgentOption>, options: List<AgentOption>, selectedId: Long?) {
        combo.removeAllItems()
        options.forEach { combo.addItem(it) }
        combo.selectedItem = options.firstOrNull { it.id == selectedId } ?: options.first()
    }

    private fun refresh(selectId: Long? = current?.id) {
        listModel.clear()
        val agents = AgentService.listAgents()
        agents.forEach(listModel::addElement)
        if (listModel.size == 0) {
            newAgent()
            return
        }
        val selectedIndex = agents.indexOfFirst { it.id == selectId }.takeIf { it >= 0 } ?: 0
        list.selectedIndex = selectedIndex
        load(listModel.getElementAt(selectedIndex))
    }

    // -------- 表单读写 --------

    private fun load(record: AgentRecord?) {
        current = record ?: return
        loading = true
        nameField.text = record.name
        providerCombo.selectedItem = providerGroups.firstOrNull { it.provider.id == record.providerId }?.provider
        reloadModelCombo(record.providerId, record.modelId)
        promptCombo.selectedItem = (0 until promptCombo.itemCount).map { promptCombo.getItemAt(it) }.firstOrNull { it.id == record.promptId }
        outputModeCombo.selectedItem = record.outputMode
        maxRuntimeSecsField.text = record.maxRuntimeSecs?.toString().orEmpty()
        tagsField.text = record.tags.joinToString(", ")
        enabledCheck.isSelected = record.enabled
        descriptionArea.text = record.description.orEmpty()
        extraPromptArea.text = record.extraPrompt.orEmpty()

        val runtime = record.runtimeParams
        historyEnabledCombo.selectedItem = if (runtime.includeHistory) "启用" else "禁用"
        maxHistoryField.text = runtime.maxHistoryMessages?.toString().orEmpty()
        toolCallRetentionRoundsField.text = runtime.toolCallRetentionRounds?.toString().orEmpty()

        val ext = record.extConfig
        applyChecks(toolChecks, ext.tools.enabled); toolsIncludeNew.isSelected = ext.tools.includeNew
        applyChecks(pluginFunctionChecks, ext.pluginFunctions.enabled); pluginFunctionsIncludeNew.isSelected = ext.pluginFunctions.includeNew
        applyChecks(skillChecks, ext.skills.enabled); skillsIncludeNew.isSelected = ext.skills.includeNew
        skillPromptEnabled.isSelected = ext.skillPromptEnabled

        val selectedViewAgents = mapOf(
            "image" to ext.viewResources.image.agentId,
            "audio" to ext.viewResources.audio.agentId,
            "video" to ext.viewResources.video.agentId,
            "file" to ext.viewResources.file.agentId,
        )
        reloadAgentCombos(record.id, record.distillConfig.agentId, selectedViewAgents)
        viewResourceControls["image"]?.enabled?.isSelected = ext.viewResources.image.enabled
        viewResourceControls["audio"]?.enabled?.isSelected = ext.viewResources.audio.enabled
        viewResourceControls["video"]?.enabled?.isSelected = ext.viewResources.video.enabled
        viewResourceControls["file"]?.enabled?.isSelected = ext.viewResources.file.enabled

        val persona = ext.persona
        personaControls["memory"]?.apply { area.text = persona.memory; maxChars.text = persona.memoryMaxChars.toString() }
        personaControls["behavior_habits"]?.apply { area.text = persona.behaviorHabits; maxChars.text = persona.behaviorHabitsMaxChars.toString() }
        personaControls["soul"]?.apply { area.text = persona.soul; maxChars.text = persona.soulMaxChars.toString() }
        personaControls["profile"]?.apply { area.text = persona.profile; maxChars.text = persona.profileMaxChars.toString() }
        personaControls["guardrails"]?.apply { area.text = persona.guardrails; maxChars.text = persona.guardrailsMaxChars.toString() }

        val distill = record.distillConfig
        distillEnabled.isSelected = distill.enabled
        distillMinMessagesField.text = distill.minMessages.toString()
        distillTypeDropdown.setSelectedValues(distill.messageTypes)
        distillExtraPromptArea.text = distill.extraPrompt.orEmpty()
        loading = false
    }

    private fun applyChecks(checks: Map<String, JCheckBox>, enabled: List<String>) {
        val set = enabled.toSet()
        checks.forEach { (key, check) -> check.isSelected = key in set }
    }

    private fun selectedKeys(checks: Map<String, JCheckBox>): List<String> =
        checks.filterValues { it.isSelected }.keys.toList()

    private fun disabledKeys(checks: Map<String, JCheckBox>): List<String> =
        checks.filterValues { !it.isSelected }.keys.toList()

    private fun newAgent() {
        current = AgentRecord(
            null, "New Agent", null, true, null, null, null, null,
            AgentRuntimeConfig(), defaultCapabilityConfig(), AgentDistillConfig(), "text", null, emptyList(), null, null,
        )
        load(current)
    }

    /** 新增 Agent 默认选中当前全部工具、插件函数、技能；includeNew 保持默认关闭。 */
    private fun defaultCapabilityConfig(): AgentCapabilityConfig = AgentCapabilityConfig(
        tools = AgentEnabledItemsConfig(toolChecks.keys.toList(), false),
        pluginFunctions = AgentEnabledItemsConfig(pluginFunctionChecks.keys.toList(), false),
        skills = AgentEnabledItemsConfig(skillChecks.keys.toList(), false),
    )

    private fun save() {
        val old = current ?: return
        try {
            val agent = old.copy(
                name = nameField.text.trim(),
                description = descriptionArea.text.trim().ifBlank { null },
                enabled = enabledCheck.isSelected,
                providerId = (providerCombo.selectedItem as? ProviderEntity)?.id,
                modelId = (modelCombo.selectedItem as? ModelEntity)?.id,
                promptId = (promptCombo.selectedItem as? PromptOption)?.id,
                extraPrompt = extraPromptArea.text.trim().ifBlank { null },
                outputMode = outputModeCombo.selectedItem?.toString()?.trim().orEmpty().ifBlank { "text" },
                maxRuntimeSecs = maxRuntimeSecsField.text.trim().toLongOrNull(),
                tags = tagsField.text.split(",").mapNotNull { it.trim().takeIf(String::isNotEmpty) },
                runtimeParams = buildRuntimeConfig(),
                extConfig = buildCapabilityConfig(),
                distillConfig = buildDistillConfig(),
            )
            require(agent.name.isNotBlank()) { "name 不能为空" }
            val expectedTools = selectedKeys(toolChecks).toSet()
            val expectedPluginFunctions = selectedKeys(pluginFunctionChecks).toSet()
            val expectedSkills = selectedKeys(skillChecks).toSet()
            val savedId = AgentService.upsertAgent(agent)
            val savedAgent = AgentService.agentById(savedId) ?: throw IllegalStateException("Agent 保存后读取失败: $savedId")
            verifySavedResources(savedAgent, expectedTools, expectedPluginFunctions, expectedSkills)
            current = savedAgent
            refresh(savedId)
            project.infoNotify("Agent", "Agent 已保存，插件函数 ${expectedPluginFunctions.size} 个")
            onChanged?.invoke()
        } catch (e: Throwable) {
            project.errorNotify("Agent", e.message ?: "保存失败")
        }
    }

    private fun verifySavedResources(
        savedAgent: AgentRecord,
        expectedTools: Set<String>,
        expectedPluginFunctions: Set<String>,
        expectedSkills: Set<String>,
    ) {
        require(savedAgent.extConfig.tools.enabled.toSet() == expectedTools) { "内置工具保存后校验失败" }
        require(savedAgent.extConfig.pluginFunctions.enabled.toSet() == expectedPluginFunctions) { "插件函数保存后校验失败" }
        require(savedAgent.extConfig.skills.enabled.toSet() == expectedSkills) { "技能保存后校验失败" }
        require(savedAgent.extConfig.tools.disabled.toSet() == disabledKeys(toolChecks).toSet()) { "内置工具排除项保存后校验失败" }
        require(savedAgent.extConfig.pluginFunctions.disabled.toSet() == disabledKeys(pluginFunctionChecks).toSet()) { "插件函数排除项保存后校验失败" }
        require(savedAgent.extConfig.skills.disabled.toSet() == disabledKeys(skillChecks).toSet()) { "技能排除项保存后校验失败" }
    }

    private fun buildRuntimeConfig(): AgentRuntimeConfig = AgentRuntimeConfig(
        includeHistory = historyEnabledCombo.selectedItem == "启用",
        maxHistoryMessages = maxHistoryField.text.trim().toIntOrNull(),
        toolCallRetentionRounds = toolCallRetentionRoundsField.text.trim().toIntOrNull(),
    )

    private fun buildCapabilityConfig(): AgentCapabilityConfig = AgentCapabilityConfig(
        tools = AgentEnabledItemsConfig(selectedKeys(toolChecks), toolsIncludeNew.isSelected, disabledKeys(toolChecks)),
        pluginFunctions = AgentEnabledItemsConfig(selectedKeys(pluginFunctionChecks), pluginFunctionsIncludeNew.isSelected, disabledKeys(pluginFunctionChecks)),
        skills = AgentEnabledItemsConfig(selectedKeys(skillChecks), skillsIncludeNew.isSelected, disabledKeys(skillChecks)),
        skillPromptEnabled = skillPromptEnabled.isSelected,
        viewResources = AgentViewResourcesConfig(
            image = viewResourceRef("image"),
            audio = viewResourceRef("audio"),
            video = viewResourceRef("video"),
            file = viewResourceRef("file"),
        ),
        persona = buildPersonaConfig(),
    )

    private fun viewResourceRef(kind: String): AgentViewResourceRef {
        val control = viewResourceControls[kind] ?: return AgentViewResourceRef()
        return AgentViewResourceRef(control.enabled.isSelected, (control.agentCombo.selectedItem as? AgentOption)?.id)
    }

    private fun buildPersonaConfig(): AgentPersonaConfig = AgentPersonaConfig(
        memory = personaControls["memory"]?.area?.text.orEmpty(),
        memoryMaxChars = personaMaxChars("memory"),
        behaviorHabits = personaControls["behavior_habits"]?.area?.text.orEmpty(),
        behaviorHabitsMaxChars = personaMaxChars("behavior_habits"),
        soul = personaControls["soul"]?.area?.text.orEmpty(),
        soulMaxChars = personaMaxChars("soul"),
        profile = personaControls["profile"]?.area?.text.orEmpty(),
        profileMaxChars = personaMaxChars("profile"),
        guardrails = personaControls["guardrails"]?.area?.text.orEmpty(),
        guardrailsMaxChars = personaMaxChars("guardrails"),
    )

    private fun personaMaxChars(key: String): Int =
        personaControls[key]?.maxChars?.text?.trim()?.toIntOrNull()?.coerceAtLeast(0)
            ?: AgentPersonaConfig.DEFAULT_PERSONA_MAX_CHARS

    private fun buildDistillConfig(): AgentDistillConfig = AgentDistillConfig(
        enabled = distillEnabled.isSelected,
        agentId = (distillAgentCombo.selectedItem as? AgentOption)?.id,
        minMessages = distillMinMessagesField.text.trim().toIntOrNull()?.coerceAtLeast(1) ?: AgentDistillConfig.DEFAULT_MIN_MESSAGES,
        messageTypes = distillTypeDropdown.selectedValues,
        extraPrompt = distillExtraPromptArea.text.trim().ifBlank { null },
        lastDistilledModelLogId = current?.distillConfig?.lastDistilledModelLogId,
    )

    private fun delete() {
        current?.id?.let { AgentService.deleteAgent(it) }
        refresh()
        onChanged?.invoke()
    }

    // -------- 辅助 --------

    private fun labeled(label: String, component: JComponent): JComponent =
        JPanel(BorderLayout(0, 4)).apply { border = JBUI.Borders.empty(4); add(JLabel(label), BorderLayout.NORTH); add(component, BorderLayout.CENTER) }

    private fun simpleListRenderer(text: (Any?) -> String) = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(l: JList<*>?, v: Any?, i: Int, s: Boolean, f: Boolean): java.awt.Component =
            super.getListCellRendererComponent(l, text(v), i, s, f)
    }

    private data class PromptOption(val id: Long?, val label: String)
    private data class AgentOption(val id: Long?, val label: String)
    private class ViewResourceControl(val enabled: JCheckBox, val agentCombo: ComboBox<AgentOption>)
    private class PersonaControl(val area: JBTextArea, val maxChars: JBTextField)

    private companion object {
        val VIEW_RESOURCE_KINDS = listOf("image" to "图片", "audio" to "音频", "video" to "视频", "file" to "文件")
        val PERSONA_ROWS = listOf(
            "memory" to "专业记忆", "behavior_habits" to "工作方法", "soul" to "角色设定",
            "profile" to "能力画像", "guardrails" to "边界约束",
        )
        val DISTILL_TYPES = listOf(
            "direct_run" to "直接运行", "chat_turn" to "对话轮次", "workflow_node" to "工作流节点",
            "scheduled_run" to "定时运行", "multimodal_analysis" to "多模态分析",
            "agent_distillation" to "Agent 蒸馏", "environment_distillation" to "环境蒸馏",
        )
    }
}



private class ScrollableWidthPanel : JPanel(), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(96)
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/** 带复选框的多选下拉框，保存值保持为字符串列表。 */
private class CheckBoxMultiSelectDropdown(
    private val options: List<Pair<String, String>>,
) : JPanel(BorderLayout()) {

    private val checkBoxes = linkedMapOf<String, JCheckBox>()
    private val button = JButton()
    private val popup = JPopupMenu()

    val selectedValues: List<String>
        get() = checkBoxes.filterValues { it.isSelected }.keys.toList()

    init {
        isOpaque = false
        options.forEach { (value, label) ->
            checkBoxes[value] = JCheckBox(label).apply {
                addActionListener { refreshButtonText() }
            }
        }
        button.horizontalAlignment = SwingConstants.LEFT
        button.addActionListener { showPopup() }
        add(button, BorderLayout.CENTER)
        rebuildPopup()
        refreshButtonText()
    }

    fun setSelectedValues(values: List<String>) {
        val selected = values.toSet()
        checkBoxes.forEach { (value, checkBox) -> checkBox.isSelected = value in selected }
        refreshButtonText()
    }

    private fun showPopup() {
        rebuildPopup()
        popup.show(button, 0, button.height)
    }

    private fun rebuildPopup() {
        popup.removeAll()
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
            checkBoxes.values.forEach { add(it) }
        }
        popup.add(panel)
        popup.pack()
    }

    private fun refreshButtonText() {
        val labels = checkBoxes.filterValues { it.isSelected }.keys.mapNotNull { value ->
            options.firstOrNull { it.first == value }?.second
        }
        button.text = labels.ifEmpty { listOf("请选择") }.joinToString("、")
        button.toolTipText = labels.joinToString("、")
    }
}

/** Skills 管理。主界面按技能行展开，展开后就是技能目录文件编辑器。 */
class AwakeSkillsConfigPanel(private val project: Project, private val onChanged: (() -> Unit)? = null) {
    private val root = JPanel(BorderLayout())
    private val skillListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private var expandedSkillName: String? = null
    private var expandedEditor: SkillEditorPanel? = null

    val component: JComponent get() = root

    init {
        root.border = JBUI.Borders.empty(8)
        root.add(JLabel("技能目录：${ResourceConfigService.skillsRootDir().path}"), BorderLayout.NORTH)
        root.add(JBScrollPane(skillListPanel), BorderLayout.CENTER)
        root.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(JButton("新增技能").apply { addActionListener { newSkill() } })
            add(JButton("刷新").apply { addActionListener { refresh(expandedSkillName) } })
        }, BorderLayout.SOUTH)
        refresh()
    }

    fun dispose() = Unit

    private fun refresh(selectName: String? = expandedSkillName) {
        expandedEditor?.saveIfDirty()
        skillListPanel.removeAll()
        ResourceConfigService.listSkills().forEach { record ->
            skillListPanel.add(skillRow(record))
            skillListPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
            if (record.name == selectName) {
                expandedSkillName = record.name
                expandedEditor = SkillEditorPanel(project, record.name, ::refreshAfterFileChange)
                skillListPanel.add(expandedEditor!!.component)
            }
        }
        skillListPanel.add(Box.createVerticalGlue())
        skillListPanel.revalidate()
        skillListPanel.repaint()
    }

    private fun skillRow(record: ResourceConfigService.SkillDirectoryRecord): JComponent {
        val display = skillDisplay(record)
        val expanded = expandedSkillName == record.name
        val row = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = true
            background = if (expanded) com.intellij.util.ui.UIUtil.getPanelBackground() else com.intellij.util.ui.UIUtil.getListBackground()
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1),
                JBUI.Borders.empty(10, 12),
            )
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(48))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val nameLabel = JLabel(display.name).apply {
            icon = AllIcons.Nodes.Plugin
            iconTextGap = JBUI.scale(8)
            font = font.deriveFont(Font.BOLD)
            toolTipText = record.name
        }
        val descLabel = JLabel(shortText(display.description, 20).ifBlank { "无描述" }).apply {
            horizontalAlignment = SwingConstants.RIGHT
            foreground = com.intellij.ui.JBColor.GRAY
            toolTipText = display.description.takeIf { it.isNotBlank() }
        }
        val expandLabel = JLabel(if (expanded) "▾" else "▸").apply {
            foreground = com.intellij.ui.JBColor.GRAY
            border = JBUI.Borders.emptyLeft(8)
        }
        row.add(nameLabel, BorderLayout.WEST)
        row.add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(descLabel, BorderLayout.CENTER)
            add(expandLabel, BorderLayout.EAST)
        }, BorderLayout.EAST)
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showSkillMenu(row, record, e.x, e.y)
                } else {
                    toggleSkill(record.name)
                }
            }
        })
        return row
    }

    private fun toggleSkill(name: String) {
        expandedSkillName = if (expandedSkillName == name) null else name
        refresh(expandedSkillName)
    }

    private fun showSkillMenu(anchor: JComponent, record: ResourceConfigService.SkillDirectoryRecord, x: Int, y: Int) {
        JPopupMenu().apply {
            add(JMenuItem("重命名目录").apply { addActionListener { renameSkill(record) } })
            add(JMenuItem("删除").apply { addActionListener { deleteSkill(record) } })
        }.show(anchor, x, y)
    }

    private fun newSkill() {
        val name = Messages.showInputDialog(project, "输入技能目录名称", "新增技能", Messages.getQuestionIcon())
            ?.trim()
            ?.replace('\\', '/')
            ?: return
        try {
            validateSkillName(name)
            val rootDir = ResourceConfigService.skillsRootDir()
            val skillDir = rootDir.resolve(name).canonicalFile
            require(skillDir.toPath().startsWith(rootDir.toPath())) { "技能名称无效" }
            require(!skillDir.exists()) { "技能已存在: $name" }
            skillDir.mkdirs()
            skillDir.resolve("references").mkdirs()
            skillDir.resolve("SKILL.md").writeText(
                "---\nname: $name\ndescription: \n---\n\n# $name\n\n## Instructions\n",
                Charsets.UTF_8,
            )
            project.infoNotify("Skills", "技能已创建")
            expandedSkillName = name
            refresh(name)
            onChanged?.invoke()
        } catch (e: Throwable) {
            project.errorNotify("Skills", e.message ?: "创建失败")
        }
    }

    private fun renameSkill(record: ResourceConfigService.SkillDirectoryRecord) {
        val name = Messages.showInputDialog(project, "输入新的技能目录名称", "重命名技能", Messages.getQuestionIcon(), record.name, null)
            ?.trim()
            ?.replace('\\', '/')
            ?: return
        try {
            validateSkillName(name)
            val rootDir = ResourceConfigService.skillsRootDir()
            val source = rootDir.resolve(record.name).canonicalFile
            val target = rootDir.resolve(name).canonicalFile
            require(target.toPath().startsWith(rootDir.toPath())) { "技能名称无效" }
            require(!target.exists()) { "技能已存在: $name" }
            require(source.renameTo(target)) { "重命名失败" }
            expandedSkillName = name
            refresh(name)
            onChanged?.invoke()
        } catch (e: Throwable) {
            project.errorNotify("Skills", e.message ?: "重命名失败")
        }
    }

    private fun deleteSkill(record: ResourceConfigService.SkillDirectoryRecord) {
        try {
            ResourceConfigService.deleteSkill(record.name)
            if (expandedSkillName == record.name) expandedSkillName = null
            refresh(expandedSkillName)
            onChanged?.invoke()
            project.infoNotify("Skills", "技能已删除")
        } catch (e: Throwable) {
            project.errorNotify("Skills", e.message ?: "删除失败")
        }
    }

    private fun refreshAfterFileChange() {
        onChanged?.invoke()
    }

    private fun skillDisplay(record: ResourceConfigService.SkillDirectoryRecord): SkillDisplay {
        val skillFile = File(record.path).resolve("SKILL.md")
        val content = runCatching { skillFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val meta = simpleSkillFrontMatter(content)
        val name = meta["name"]?.takeIf { it.isNotBlank() }
            ?: firstMarkdownTitle(content)
            ?: record.name
        val description = meta["description"]?.takeIf { it.isNotBlank() }
            ?: record.description.orEmpty()
        return SkillDisplay(name, description)
    }

    private fun simpleSkillFrontMatter(content: String): Map<String, String> {
        if (!content.startsWith("---")) return emptyMap()
        val end = content.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap()
        return content.substring(3, end).lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim().trim('"', '\'')
            }
            .toMap()
    }

    private fun firstMarkdownTitle(content: String): String? =
        content.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.takeIf { it.isNotBlank() }

    private fun shortText(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars) + "..."

    private fun validateSkillName(name: String) {
        require(name.isNotBlank()) { "技能名称不能为空" }
        require(!name.startsWith("/") && !name.split('/').contains("..")) { "技能名称无效" }
    }

    private data class SkillDisplay(val name: String, val description: String)

    private class SkillEditorPanel(
        private val project: Project,
        private val skillName: String,
        private val onChanged: () -> Unit,
    ) {
        private val skillRoot = ResourceConfigService.skillsRootDir().resolve(skillName).canonicalFile
        private val tree = Tree()
        private val pathLabel = JLabel(skillName)
        private val statusLabel = JLabel()
        private val editor = JBTextArea(20, 0)
        private var currentFile: File? = null
        private var currentFileText = false

        val component: JComponent = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 16, 8, 0)
            add(buildContent(), BorderLayout.CENTER)
        }

        init {
            editor.lineWrap = false
            configureTree()
            reloadTree()
        }

        fun saveIfDirty() = Unit

        private fun buildContent(): JComponent = JBSplitter(false, .30f).apply {
            isOpaque = false
            firstComponent = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBScrollPane(tree).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                    viewport.background = UIUtil.TRANSPARENT_COLOR
                }, BorderLayout.CENTER)
                preferredSize = Dimension(JBUI.scale(260), JBUI.scale(360))
            }
            secondComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                add(JPanel(BorderLayout()).apply {
                    add(pathLabel, BorderLayout.CENTER)
                    add(statusLabel, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(JBScrollPane(editor), BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(JButton("保存文件").apply { addActionListener { saveCurrentFile() } })
                }, BorderLayout.SOUTH)
                preferredSize = Dimension(JBUI.scale(620), JBUI.scale(360))
            }
        }

        private fun configureTree() {
            tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            tree.isRootVisible = false
            tree.showsRootHandles = true
            tree.isOpaque = false
            tree.background = UIUtil.TRANSPARENT_COLOR
            tree.cellRenderer = object : DefaultTreeCellRenderer() {
                init {
                    backgroundNonSelectionColor = UIUtil.TRANSPARENT_COLOR
                    backgroundSelectionColor = UIUtil.TRANSPARENT_COLOR
                    borderSelectionColor = UIUtil.TRANSPARENT_COLOR
                }

                override fun getTreeCellRendererComponent(
                    tree: JTree?,
                    value: Any?,
                    selected: Boolean,
                    expanded: Boolean,
                    leaf: Boolean,
                    row: Int,
                    hasFocus: Boolean,
                ): java.awt.Component {
                    val c = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                    val node = (value as? DefaultMutableTreeNode)?.userObject as? SkillTreeFile
                    if (node != null) {
                        icon = if (node.file.isDirectory) {
                            AllIcons.Nodes.Folder
                        } else {
                            FileTypeManager.getInstance().getFileTypeByFileName(node.file.name).icon
                        }
                    }
                    (c as? JComponent)?.isOpaque = false
                    return c
                }
            }
            tree.addTreeSelectionListener {
                selectedNode()?.let { loadFile(it.file) }
            }
            tree.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = maybeShowMenu(e)
                override fun mouseReleased(e: MouseEvent) = maybeShowMenu(e)
            })
        }

        private fun maybeShowMenu(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            val path = tree.getPathForLocation(e.x, e.y) ?: return
            tree.selectionPath = path
            val node = selectedNode() ?: return
            JPopupMenu().apply {
                if (node.file.isDirectory) {
                    add(JMenuItem("新建文件").apply { addActionListener { createFile(node.file) } })
                    add(JMenuItem("新建目录").apply { addActionListener { createDirectory(node.file) } })
                    addSeparator()
                }
                add(JMenuItem("删除").apply { addActionListener { deleteTreeFile(node.file) } })
            }.show(tree, e.x, e.y)
        }

        private fun selectedNode(): SkillTreeFile? =
            (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? SkillTreeFile

        private fun reloadTree(selectFile: File? = currentFile) {
            val expandedPaths = expandedDirectoryPaths()
            tree.model = DefaultTreeModel(buildTreeNode(skillRoot, skillRoot.name))
            restoreExpandedDirectoryPaths(expandedPaths)
            val target = selectFile?.takeIf { it.exists() } ?: skillRoot.resolve("SKILL.md")
            selectFileInTree(target)
            if (target.exists()) loadFile(target)
        }

        private fun expandedDirectoryPaths(): Set<String> {
            val expanded = linkedSetOf<String>()
            for (row in 0 until tree.rowCount) {
                if (!tree.isExpanded(row)) continue
                val path = tree.getPathForRow(row) ?: continue
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
                val item = node.userObject as? SkillTreeFile ?: continue
                if (item.file.isDirectory) {
                    expanded.add(relativeTreePath(item.file))
                }
            }
            return expanded
        }

        private fun restoreExpandedDirectoryPaths(expandedPaths: Set<String>) {
            if (expandedPaths.isEmpty()) return
            val rootNode = tree.model.root as? DefaultMutableTreeNode ?: return
            expandedPaths.forEach { relative ->
                findDirectoryNode(rootNode, relative)?.let { tree.expandPath(TreePath(it.path)) }
            }
        }

        private fun findDirectoryNode(node: DefaultMutableTreeNode, relative: String): DefaultMutableTreeNode? {
            val item = node.userObject as? SkillTreeFile
            if (item?.file?.isDirectory == true && relativeTreePath(item.file) == relative) return node
            val children = node.children()
            while (children.hasMoreElements()) {
                val found = findDirectoryNode(children.nextElement() as DefaultMutableTreeNode, relative)
                if (found != null) return found
            }
            return null
        }

        private fun relativeTreePath(file: File): String =
            if (file == skillRoot) "" else skillRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')

        private fun buildTreeNode(file: File, label: String): DefaultMutableTreeNode {
            val node = DefaultMutableTreeNode(SkillTreeFile(file, label))
            if (file.isDirectory) {
                file.listFiles().orEmpty()
                    .filterNot { it.name.startsWith(".") }
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    .forEach { child -> node.add(buildTreeNode(child, child.name)) }
            }
            return node
        }

        private fun selectFileInTree(file: File) {
            val rootNode = tree.model.root as? DefaultMutableTreeNode ?: return
            findNode(rootNode, file)?.let { node ->
                val path = TreePath(node.path)
                tree.selectionPath = path
                tree.scrollPathToVisible(path)
            }
        }

        private fun findNode(node: DefaultMutableTreeNode, file: File): DefaultMutableTreeNode? {
            val item = node.userObject as? SkillTreeFile
            if (item?.file == file) return node
            val children = node.children()
            while (children.hasMoreElements()) {
                val found = findNode(children.nextElement() as DefaultMutableTreeNode, file)
                if (found != null) return found
            }
            return null
        }

        private fun createFile(parent: File) {
            val name = Messages.showInputDialog(project, "输入文件名", "新建文件", Messages.getQuestionIcon())?.trim() ?: return
            try {
                validateChildName(name)
                val file = parent.resolve(name).canonicalFile
                require(file.toPath().startsWith(skillRoot.toPath())) { "文件名无效" }
                require(!file.exists()) { "文件已存在" }
                file.parentFile.mkdirs()
                file.writeText("", Charsets.UTF_8)
                reloadTree(file)
                onChanged()
            } catch (e: Throwable) {
                project.errorNotify("Skills", e.message ?: "创建文件失败")
            }
        }

        private fun createDirectory(parent: File) {
            val name = Messages.showInputDialog(project, "输入目录名", "新建目录", Messages.getQuestionIcon())?.trim() ?: return
            try {
                validateChildName(name)
                val dir = parent.resolve(name).canonicalFile
                require(dir.toPath().startsWith(skillRoot.toPath())) { "目录名无效" }
                require(!dir.exists()) { "目录已存在" }
                dir.mkdirs()
                reloadTree(dir)
                onChanged()
            } catch (e: Throwable) {
                project.errorNotify("Skills", e.message ?: "创建目录失败")
            }
        }

        private fun validateChildName(name: String) {
            require(name.isNotBlank()) { "名称不能为空" }
            require(!name.startsWith("/") && !name.split('/').contains("..")) { "名称无效" }
        }

        private fun deleteTreeFile(file: File) {
            if (file == skillRoot) {
                project.errorNotify("Skills", "不能在文件树里删除技能根目录，请在技能列表删除技能")
                return
            }
            val confirm = Messages.showYesNoDialog(
                project,
                "确定删除 ${file.name}？",
                "删除文件",
                Messages.getQuestionIcon(),
            )
            if (confirm != Messages.YES) return
            try {
                val canonical = file.canonicalFile
                require(canonical.toPath().startsWith(skillRoot.toPath())) { "路径无效" }
                val parent = canonical.parentFile?.takeIf { it.exists() } ?: skillRoot
                val deleted = if (canonical.isDirectory) canonical.deleteRecursively() else canonical.delete()
                require(deleted) { "删除失败" }
                if (currentFile == canonical || currentFile?.toPath()?.startsWith(canonical.toPath()) == true) {
                    currentFile = null
                    currentFileText = false
                    editor.text = ""
                    editor.isEditable = false
                    pathLabel.text = skillName
                    statusLabel.text = ""
                }
                reloadTree(parent)
                onChanged()
            } catch (e: Throwable) {
                project.errorNotify("Skills", e.message ?: "删除失败")
            }
        }

        private fun loadFile(file: File) {
            currentFile = file.takeIf { it.isFile }
            val relative = if (file == skillRoot) skillName else skillRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            pathLabel.text = relative
            if (file.isDirectory) {
                currentFileText = false
                editor.text = "目录不可编辑"
                editor.isEditable = false
                statusLabel.text = "目录"
                return
            }
            val bytes = file.readBytes()
            val text = decodeText(bytes)
            if (text == null) {
                currentFileText = false
                editor.text = "该文件不是文本内容，无法在这里编辑。"
                editor.isEditable = false
                statusLabel.text = "不可编辑"
                return
            }
            currentFileText = true
            editor.text = text
            editor.caretPosition = 0
            editor.isEditable = true
            statusLabel.text = "文本"
        }

        private fun saveCurrentFile() {
            val file = currentFile ?: return
            if (!currentFileText) {
                project.errorNotify("Skills", "当前文件不是文本内容，不能保存")
                return
            }
            try {
                file.writeText(editor.text.replace("\r\n", "\n"), Charsets.UTF_8)
                project.infoNotify("Skills", "文件已保存")
                onChanged()
            } catch (e: Throwable) {
                project.errorNotify("Skills", e.message ?: "保存失败")
            }
        }

        private fun decodeText(bytes: ByteArray): String? {
            if (bytes.any { it.toInt() == 0 }) return null
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: Throwable) {
                null
            }
        }

        private class SkillTreeFile(val file: File, private val label: String) {
            override fun toString(): String = label
        }
    }
}

/** 全局配置项元数据。文案对齐 awake config_entry_meta 的 label/description/value_type。 */
private data class GlobalConfigField(
    val key: String,
    val label: String,
    val description: String,
    val valueType: String,
    val default: String,
)

private data class GlobalConfigGroup(val title: String, val fields: List<GlobalConfigField>)

/** 对齐 awake SYSTEM_CONFIG_KEYS + configGroupDefinitions。active_environment 不做：idea-tools 无多环境模块。 */
private val GLOBAL_CONFIG_GROUPS = listOf(
    GlobalConfigGroup("模型执行", listOf(
        GlobalConfigField("model.max_tool_call_rounds", "默认工具调用轮次", "模型请求自动执行工具调用循环时允许的默认最大轮次。默认 30。", "number", "30"),
        GlobalConfigField("model.max_retries", "默认模型重试次数", "模型 API 请求失败后的默认重试次数。0 表示不重试。", "number", "0"),
    )),
    GlobalConfigGroup("模型 HTTP", listOf(
        GlobalConfigField("model.dns_servers", "模型请求 DNS", "模型 API 请求使用的自定义 DNS 服务器，多个用英文逗号分隔。支持 223.5.5.5 或 223.5.5.5:53；留空时不启用自定义 DNS。", "text", ""),
        GlobalConfigField("model.http_request_timeout_secs", "模型 HTTP 请求超时", "模型 HTTP 客户端单次请求的总超时时间，单位秒。0 表示不设置总请求超时。", "number", "0"),
        GlobalConfigField("model.http_max_retries_per_request", "模型 HTTP 单请求重试次数", "reqwest 客户端每个底层 HTTP 请求错误的最大重试次数。默认 2，0 表示关闭客户端级重试。", "number", "2"),
        GlobalConfigField("model.http_pool_idle_timeout_secs", "模型连接池空闲保留秒数", "模型 HTTP 客户端连接池中空闲连接的保留时长，单位秒。默认 120。", "number", "120"),
        GlobalConfigField("model.http_pool_max_idle_per_host", "模型连接池每 Host 最大空闲连接数", "模型 HTTP 客户端连接池为每个 Host 保留的最大空闲连接数。默认 32。", "number", "32"),
    )),
    GlobalConfigGroup("蒸馏任务", listOf(
        GlobalConfigField("distillation.max_threads", "蒸馏最大线程数", "全局同时执行的环境蒸馏任务数量上限。环境自己的蒸馏配置决定最小会话数和最小消息数。", "number", "1"),
        GlobalConfigField("agent.distillation.max_threads", "Agent 蒸馏最大线程数", "全局同时执行的 Agent 蒸馏任务数量上限。每轮候选数量仍由 agent.distillation.max_agents_per_scan 控制。", "number", "1"),
    )),
    GlobalConfigGroup("渠道配置", listOf(
        GlobalConfigField("channel.retry_count", "渠道重试次数", "渠道通知发送失败后的即时重试次数。默认 1，0 表示不重试。", "number", "1"),
    )),
    GlobalConfigGroup("Web Fetch 工具", listOf(
        GlobalConfigField("web_fetch.proxy_enabled", "启用工具代理", "控制 web_fetch 工具是否使用工具代理地址。关闭时即使填写了代理地址也不会生效。", "boolean", "false"),
        GlobalConfigField("web_fetch.proxy", "工具代理地址", "web_fetch 工具使用的代理地址，仅在启用工具代理时生效。示例：http://127.0.0.1:7890、socks5://127.0.0.1:1080。只影响 web_fetch，不影响模型 API 请求。", "url", ""),
        GlobalConfigField("web_fetch.timeout_secs", "Web Fetch 超时", "web_fetch 工具默认请求超时时间，单位秒。", "number", "30"),
        GlobalConfigField("web_fetch.max_response_bytes", "Web Fetch 最大响应", "web_fetch 工具读取响应体的最大字节数，避免拉取过大的页面或文件。", "number", "1000000"),
    )),
)

/** awake ConfigView 的 Swing 版本。全局配置项写回 global_config 表。 */
class AwakeGlobalConfigPanel(private val project: Project, private val onChanged: (() -> Unit)? = null) {
    private val root = JPanel(BorderLayout())
    private val fieldControls = linkedMapOf<String, JComponent>()

    val component: JComponent get() = root

    init {
        root.border = JBUI.Borders.empty(8)
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            GLOBAL_CONFIG_GROUPS.forEach { group -> add(section(group.title, configGrid(group.fields))) }
            add(Box.createVerticalGlue())
        }
        root.add(JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)
        root.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(JButton("保存配置").apply { addActionListener { save() } })
        }, BorderLayout.SOUTH)
        loadValues()
    }

    fun dispose() = Unit

    private fun section(title: String, content: JComponent): JComponent = JPanel(BorderLayout(0, 6)).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        isOpaque = false
        border = JBUI.Borders.empty(8, 2)
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(JLabel(title).apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    private fun configGrid(fields: List<GlobalConfigField>): JComponent = JPanel(GridBagLayout()).apply {
        isOpaque = false
        fields.forEachIndexed { index, field ->
            add(configCopy(field), GridBagConstraints().apply {
                gridx = 0; gridy = index; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(JBUI.scale(4), JBUI.scale(2), JBUI.scale(4), JBUI.scale(8))
            })
            add(configControl(field), GridBagConstraints().apply {
                gridx = 1; gridy = index; weightx = 0.0; anchor = GridBagConstraints.EAST
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(2))
            })
        }
    }

    private fun configCopy(field: GlobalConfigField): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(JLabel(field.label).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        add(JLabel(field.key).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            foreground = com.intellij.ui.JBColor.GRAY
            font = font.deriveFont(font.size2D - 1f)
        })
        add(JLabel("<html><body style='width:520px'>${field.description}</body></html>").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            foreground = com.intellij.ui.JBColor.GRAY
        })
    }

    private fun configControl(field: GlobalConfigField): JComponent {
        val editor: JComponent = when (field.valueType) {
            "boolean" -> JCheckBox()
            else -> JBTextField().apply { preferredSize = java.awt.Dimension(JBUI.scale(280), preferredSize.height) }
        }
        fieldControls[field.key] = editor
        return editor
    }

    private fun loadValues() {
        GLOBAL_CONFIG_GROUPS.flatMap { it.fields }.forEach { field ->
            val value = com.lhstack.tools.db.service.SettingService.setting(field.key) ?: field.default
            when (val editor = fieldControls[field.key]) {
                is JCheckBox -> editor.isSelected = value == "true"
                is JTextField -> editor.text = value
            }
        }
    }

    private fun save() {
        try {
            GLOBAL_CONFIG_GROUPS.flatMap { it.fields }.forEach { field ->
                val value = when (val editor = fieldControls[field.key]) {
                    is JCheckBox -> if (editor.isSelected) "true" else "false"
                    is JTextField -> editor.text.trim()
                    else -> ""
                }
                if (field.valueType == "number" && value.isNotBlank() && value.toLongOrNull() == null) {
                    throw IllegalArgumentException("${field.label} 必须是整数")
                }
                com.lhstack.tools.db.service.SettingService.setSetting(field.key, value)
            }
            project.infoNotify("全局配置", "配置已保存")
            onChanged?.invoke()
        } catch (e: Throwable) {
            project.errorNotify("全局配置", e.message ?: "保存失败")
        }
    }
}
