package com.lhstack.tools.agent

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBSplitter
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.infoNotify
import com.lhstack.tools.plugins.pluginState
import java.awt.Component
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent

class AgentSkillConfigPanel(
    private val project: Project,
    private val onChanged: (() -> Unit)? = null,
) {
    private val root = JPanel(BorderLayout())
    private val skillListModel = DefaultListModel<AgentSkillState>()
    private val skillList = JBList(skillListModel)
    private val resourceListModel = DefaultListModel<AgentSkillResourceState>()
    private val resourceList = JBList(resourceListModel)

    private val nameField = JBTextField()
    private val descriptionField = JBTextField()
    private val sourceTypeField = JBTextField().apply { isEditable = false }
    private val sourcePathField = JBTextField().apply { isEditable = false }
    private val enabledByDefaultCheck = JCheckBox("新会话默认启用")
    private val skillContentArea = JBTextArea(10, 0)
    private val skillContentScroll = JBScrollPane(skillContentArea)
    private val resourcePathField = JBTextField()
    private val resourceContentArea = JBTextArea(8, 0)
    private val resourceContentScroll = JBScrollPane(resourceContentArea)
    private var currentSkill: AgentSkillState? = null
    private var updatingResourceEditor = false

    val component: JComponent
        get() = root

    init {
        configureUi()
        buildUi()
        refreshSkillList()
    }

    fun dispose() {}

    private fun configureUi() {
        skillContentArea.lineWrap = true
        skillContentArea.wrapStyleWord = true
        resourceContentArea.lineWrap = true
        resourceContentArea.wrapStyleWord = true
        skillContentScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        resourceContentScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        bindResourceEditor()
    }

    private fun buildUi() {
        skillList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        skillList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val skill = value as? AgentSkillState
                val label = if (skill == null) "" else {
                    val source = AgentSkillSourceType.fromId(skill.sourceType).displayName
                    val defaultTag = if (skill.enabledByDefault) " | 默认" else ""
                    "${skill.name} ($source$defaultTag)"
                }
                return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
            }
        }
        skillList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadSkill(skillList.selectedValue)
            }
        }

        resourceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resourceList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val resource = value as? AgentSkillResourceState
                return super.getListCellRendererComponent(
                    list,
                    resource?.path ?: "",
                    index,
                    isSelected,
                    cellHasFocus
                )
            }
        }
        resourceList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadResource(resourceList.selectedValue)
            }
        }

        val listPanel = AgentFormUi.sectionCard(
            "技能列表",
            "管理本地导入和手动创建的 skills。",
            JPanel(BorderLayout(0, 10)).apply {
                isOpaque = false
                add(JBScrollPane(skillList), BorderLayout.CENTER)
                add(buildListButtons(), BorderLayout.SOUTH)
            }
        )

        val detailPanel = AgentFormUi.sectionCard(
            "技能详情",
            "编辑技能内容和资源文件。",
            JPanel(BorderLayout(0, 10)).apply {
                isOpaque = false
                add(JBScrollPane(buildDetailPanel()).apply {
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                }, BorderLayout.CENTER)
                add(buildActionPanel(), BorderLayout.SOUTH)
            }
        )

        root.border = JBUI.Borders.empty(8)
        root.add(JBSplitter(false, 0.28f).apply {
            firstComponent = listPanel
            secondComponent = detailPanel
        }, BorderLayout.CENTER)
    }

    private fun buildListButtons(): JComponent {
        val group = DefaultActionGroup().apply {
            add(iconAction("新增技能", Icons.skillsManageIcon()) { addSkill() })
            add(iconAction("导入目录", Icons.installIcon()) { importSkills() })
            add(iconAction("删除技能", Icons.mcpDeleteIcon()) { deleteSkill() })
        }
        return ActionManager.getInstance().createActionToolbar("AgentSkillListToolbar", group, true).component
    }

    private fun buildDetailPanel(): JComponent {
        val basicCard = AgentFormUi.sectionCard(
            "基础信息",
            "名称、描述和来源信息。",
            AgentFormUi.twoColumnGrid(
                AgentFormUi.fieldTile("技能名称", nameField, "显示名称，同时用于生成 SDK skill 的名称。"),
                AgentFormUi.fieldTile("描述", descriptionField, "简要说明这个技能何时使用。"),
                AgentFormUi.fieldTile("来源", sourceTypeField, "手动创建或本地目录导入。"),
                AgentFormUi.fieldTile("默认启用", enabledByDefaultCheck, "新会话是否默认挂载该技能。"),
            )
        )
        val sourceCard = AgentFormUi.sectionCard(
            "来源路径",
            "本地导入的技能会记录源目录，手动技能留空。",
            AgentFormUi.verticalStack(
                AgentFormUi.fieldTile("源路径", sourcePathField, "用于后续查看来源。"),
            )
        )
        val contentCard = AgentFormUi.sectionCard(
            "技能内容",
            "这里的内容会直接映射到 SDK 的 skillContent。",
            AgentFormUi.verticalStack(
                AgentFormUi.fieldTile("Content", skillContentScroll, "支持多行 markdown 或说明文本。"),
            )
        )
        val resourceCard = AgentFormUi.sectionCard(
            "资源管理",
            "管理 references/examples/scripts 等资源文件。",
            JPanel(BorderLayout(0, 10)).apply {
                isOpaque = false
                add(
                    JBSplitter(false, 0.34f).apply {
                        firstComponent = JPanel(BorderLayout(0, 8)).apply {
                            isOpaque = false
                            add(JBScrollPane(resourceList), BorderLayout.CENTER)
                            add(buildResourceButtons(), BorderLayout.SOUTH)
                        }
                        secondComponent = JPanel(BorderLayout(0, 8)).apply {
                            isOpaque = false
                            add(singleLineFieldTile("资源路径", resourcePathField, "例如 references/api.md。"), BorderLayout.NORTH)
                            add(AgentFormUi.fieldTile("资源内容", resourceContentScroll, "资源的文本内容。"), BorderLayout.CENTER)
                        }
                    },
                    BorderLayout.CENTER
                )
            }
        )
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                AgentFormUi.verticalStack(basicCard, sourceCard, contentCard, resourceCard),
                BorderLayout.NORTH
            )
        }
    }

    private fun buildResourceButtons(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JButton("新增资源").apply {
                addActionListener { addResource() }
            })
            add(JButton("删除").apply {
                addActionListener { deleteResource() }
            })
            add(JButton("清空编辑").apply {
                addActionListener { clearResourceEditor() }
            })
        }
    }

    private fun buildActionPanel(): JComponent {
        val group = DefaultActionGroup().apply {
            add(iconAction("保存技能", Icons.mcpSaveIcon()) { saveSkill() })
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(ActionManager.getInstance().createActionToolbar("AgentSkillActionsToolbar", group, true).component, BorderLayout.EAST)
        }
    }

    private fun addSkill() {
        val skill = AgentSkillSupport.createManualSkill()
        project.pluginState().agentSkills.add(skill)
        skillListModel.addElement(skill)
        skillList.setSelectedValue(skill, true)
        onChanged?.invoke()
    }

    private fun importSkills() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false).apply {
            title = "选择 skills 目录"
            isForcedToUseIdeaFileChooser = true
        }
        val directory = FileChooser.chooseFile(descriptor, project, null) ?: return
        val importResult = runCatching {
            AgentSkillImportSupport.importFromPath(directory.path, project.pluginState().agentSkills)
        }
            .getOrElse { error ->
                project.errorNotify("Skills", error.message ?: "导入失败")
                return
            }
        if (importResult.importedSkills.isEmpty()) {
            project.infoNotify(
                "Skills",
                (importResult.skippedSkills + importResult.warnings).joinToString("\n").ifBlank { "没有可导入的技能" }
            )
            return
        }
        importResult.importedSkills.forEach { imported ->
            project.pluginState().agentSkills.add(imported)
            skillListModel.addElement(imported)
        }
        val notices = buildList {
            addAll(importResult.skippedSkills)
            addAll(importResult.warnings)
        }
        if (notices.isNotEmpty()) {
            project.infoNotify("Skills", notices.joinToString("\n"))
        }
        if (importResult.importedSkills.isNotEmpty()) {
            skillList.setSelectedIndex(skillListModel.size - 1)
            onChanged?.invoke()
        }
    }

    private fun deleteSkill() {
        val skill = currentSkill ?: return
        if (Messages.showYesNoDialog(project, "确认删除技能 `${skill.name}`？", "删除 Skills", null) != Messages.YES) {
            return
        }
        project.pluginState().agentSkills.removeIf { it.id == skill.id }
        project.pluginState().agentSessions.forEach { session ->
            session.enabledSkillIds.removeIf { it == skill.id }
        }
        skillListModel.removeElement(skill)
        currentSkill = null
        clearForm()
        onChanged?.invoke()
    }

    private fun loadSkill(skill: AgentSkillState?) {
        currentSkill = skill
        if (skill == null) {
            clearForm()
            return
        }
        nameField.text = skill.name
        descriptionField.text = skill.description
        sourceTypeField.text = AgentSkillSourceType.fromId(skill.sourceType).displayName
        sourcePathField.text = skill.sourcePath
        enabledByDefaultCheck.isSelected = skill.enabledByDefault
        skillContentArea.text = skill.skillContent
        resourceListModel.removeAllElements()
        skill.resources.forEach { resourceListModel.addElement(it) }
        if (resourceListModel.size > 0) {
            resourceList.selectedIndex = 0
        } else {
            clearResourceEditor()
        }
    }

    private fun saveSkill() {
        val skill = currentSkill ?: return
        val name = nameField.text.trim()
        val description = descriptionField.text.trim()
        val content = skillContentArea.text.replace("\r\n", "\n").trim()
        if (name.isBlank()) {
            project.errorNotify("Skills", "请填写技能名称")
            return
        }
        if (description.isBlank()) {
            project.errorNotify("Skills", "请填写技能描述")
            return
        }
        if (content.isBlank()) {
            project.errorNotify("Skills", "请填写技能内容")
            return
        }
        val duplicate = project.pluginState().agentSkills.firstOrNull { it.id != skill.id && it.name == name }
        if (duplicate != null) {
            project.errorNotify("Skills", "技能名称已存在: $name")
            return
        }
        skill.name = name
        skill.description = description
        skill.skillContent = content
        skill.enabledByDefault = enabledByDefaultCheck.isSelected
        skill.resources = (0 until resourceListModel.size)
            .map { resourceListModel.getElementAt(it) }
            .toMutableList()
        AgentSkillSupport.normalizeSkills(project.pluginState().agentSkills).let { normalized ->
            project.pluginState().agentSkills.clear()
            project.pluginState().agentSkills.addAll(normalized)
        }
        skillList.repaint()
        onChanged?.invoke()
        project.infoNotify("Skills", "技能已保存")
    }

    private fun addResource() {
        val candidatePath = uniqueResourcePath("new-resource.txt")
        val resource = AgentSkillResourceState().apply {
            path = candidatePath
            content = ""
        }
        resourceListModel.addElement(resource)
        resourceList.setSelectedIndex(resourceListModel.size - 1)
        loadResource(resource)
        resourcePathField.requestFocusInWindow()
        resourcePathField.selectAll()
    }

    private fun deleteResource() {
        val selectedIndex = resourceList.selectedIndex
        if (selectedIndex < 0) {
            return
        }
        resourceListModel.remove(selectedIndex)
        if (resourceListModel.size > 0) {
            resourceList.selectedIndex = selectedIndex.coerceAtMost(resourceListModel.size - 1)
        } else {
            clearResourceEditor()
        }
    }

    private fun loadResource(resource: AgentSkillResourceState?) {
        updatingResourceEditor = true
        resourcePathField.text = resource?.path.orEmpty()
        resourceContentArea.text = resource?.content.orEmpty()
        updatingResourceEditor = false
    }

    private fun clearResourceEditor() {
        updatingResourceEditor = true
        resourcePathField.text = ""
        resourceContentArea.text = ""
        updatingResourceEditor = false
    }

    private fun uniqueResourcePath(baseName: String): String {
        var index = 1
        var candidate = baseName
        val existing = (0 until resourceListModel.size).map { resourceListModel.getElementAt(it).path }.toSet()
        while (candidate in existing) {
            candidate = "new-resource-$index.txt"
            index++
        }
        return candidate
    }

    private fun bindResourceEditor() {
        val syncListener = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                syncSelectedResourceFromEditor()
            }
        }
        resourcePathField.document.addDocumentListener(syncListener)
        resourceContentArea.document.addDocumentListener(syncListener)
    }

    private fun syncSelectedResourceFromEditor() {
        if (updatingResourceEditor) {
            return
        }
        val selectedIndex = resourceList.selectedIndex
        if (selectedIndex < 0) {
            return
        }
        val resource = resourceListModel.getElementAt(selectedIndex)
        resource.path = resourcePathField.text.replace("\r\n", "").replace('\\', '/')
        resource.content = resourceContentArea.text.replace("\r\n", "\n")
        resourceList.repaint()
    }

    private fun singleLineFieldTile(label: String, field: JComponent, hint: String? = null): JComponent {
        return AgentFormUi.fieldTile(label, field, hint).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun clearForm() {
        nameField.text = ""
        descriptionField.text = ""
        sourceTypeField.text = ""
        sourcePathField.text = ""
        enabledByDefaultCheck.isSelected = false
        skillContentArea.text = ""
        resourceListModel.removeAllElements()
        clearResourceEditor()
    }

    private fun refreshSkillList() {
        skillListModel.removeAllElements()
        AgentSkillSupport.normalizeSkills(project.pluginState().agentSkills).forEach { skillListModel.addElement(it) }
        if (skillListModel.size > 0) {
            skillList.selectedIndex = 0
        } else {
            clearForm()
        }
    }

    private fun iconAction(text: String, icon: javax.swing.Icon, action: () -> Unit): AnAction {
        return object : AnAction({ text }, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                action()
            }
        }
    }
}
