package com.lhstack.tools.agent

import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.LanguageTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.findIcon
import com.lhstack.tools.ext.infoNotify
import com.lhstack.tools.plugins.pluginState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

class AgentSystemPromptConfigPanel(
    private val project: Project,
    private val initialPromptId: String?,
    private val onChanged: (() -> Unit)? = null,
) {
    private val root = JPanel(BorderLayout())
    private val promptListModel = DefaultListModel<AgentSystemPromptState>()
    private val promptList = JBList(promptListModel)
    private val nameField = JBTextField()
    private var contentEditor: EditorEx? = null
    private var contentEditable = true
    private val contentField = createPromptContentEditor()
    private var currentPromptId: String? = null
    private val actionToolbars = mutableListOf<ActionToolbar>()
    private val addPromptAction = iconAction("新增提示词", toolbarIcon("icons/prompt_toolbar_add.svg")) { addPrompt() }
    private val duplicatePromptAction = iconAction(
        "复制提示词",
        toolbarIcon("icons/prompt_toolbar_copy.svg"),
        enabledProvider = { selectedPrompt() != null }
    ) { duplicatePrompt() }
    private val deletePromptAction = iconAction(
        "删除提示词",
        toolbarIcon("icons/prompt_toolbar_delete.svg"),
        enabledProvider = {
            selectedPrompt()?.id?.let { it != AgentSystemPromptSupport.DEFAULT_PROMPT_ID } == true
        }
    ) { deletePrompt() }
    private val savePromptAction = iconAction(
        "保存提示词",
        toolbarIcon("icons/prompt_toolbar_save.svg"),
        enabledProvider = {
            selectedPrompt()?.id?.let { it != AgentSystemPromptSupport.DEFAULT_PROMPT_ID } == true
        }
    ) { savePrompt() }

    val component: JComponent
        get() = root

    init {
        configureUi()
        buildUi()
        refreshPromptList(initialPromptId)
    }

    fun dispose() {}

    private fun configureUi() {
        promptList.visibleRowCount = 14
        nameField.toolTipText = "会话选择器中的显示名称。"
        contentField.border = JBUI.Borders.customLine(JBColor.border(), 1)
        contentField.minimumSize = Dimension(0, JBUI.scale(320))
        contentField.preferredSize = Dimension(JBUI.scale(640), JBUI.scale(420))
        contentField.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    private fun buildUi() {
        promptList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        promptList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val prompt = value as? AgentSystemPromptState
                val defaultTag = if (prompt?.id == AgentSystemPromptSupport.DEFAULT_PROMPT_ID) " · 默认" else ""
                return super.getListCellRendererComponent(
                    list,
                    prompt?.name.orEmpty() + defaultTag,
                    index,
                    isSelected,
                    cellHasFocus
                )
            }
        }
        promptList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadPrompt(promptList.selectedValue?.id)
            }
        }

        val listPanel = AgentFormUi.sectionCard(
            "提示词",
            "默认提示词不可删除，自定义提示词可用于会话切换。",
            JPanel(BorderLayout(0, 10)).apply {
                isOpaque = false
                add(JBScrollPane(promptList), BorderLayout.CENTER)
                add(buildListButtons(), BorderLayout.SOUTH)
            }
        )
        val detailPanel = AgentFormUi.sectionCard("提示词编辑", "上方为名称，下方内容支持 Markdown。", buildDetailPanel())
        root.border = JBUI.Borders.empty(8)
        root.add(JBSplitter(false, 0.24f).apply {
            firstComponent = listPanel
            secondComponent = detailPanel
        }, BorderLayout.CENTER)
    }

    private fun buildDetailPanel(): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            isOpaque = false
            add(buildNameSection(), BorderLayout.NORTH)
            add(buildContentSection(), BorderLayout.CENTER)
            add(buildActionPanel(), BorderLayout.SOUTH)
        }
    }

    private fun buildNameSection(): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(sectionHeader("提示词名称", "会话选择器中的显示名称。"), BorderLayout.NORTH)
            add(nameField, BorderLayout.CENTER)
        }
    }

    private fun buildContentSection(): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(sectionHeader("Markdown 内容", "保存后会作为当前会话的 system message。"), BorderLayout.NORTH)
            add(contentField, BorderLayout.CENTER)
        }
    }

    private fun sectionHeader(title: String, hint: String): JComponent {
        return JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(JLabel(title).apply {
                font = font.deriveFont((font.style or Font.BOLD).toFloat())
            }, BorderLayout.NORTH)
            add(JLabel(hint).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = font.deriveFont(font.size2D - 1f)
            }, BorderLayout.CENTER)
        }
    }

    private fun createPromptContentEditor(): LanguageTextField {
        val markdownLanguage = Language.findLanguageByID("Markdown") ?: Language.ANY
        return object : LanguageTextField(markdownLanguage, project, "", false) {
            override fun createEditor(): EditorEx {
                val editor = super.createEditor()
                contentEditor = editor
                editor.isViewer = !contentEditable
                editor.setBorder(null)
                editor.scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                editor.scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                val settings = editor.settings
                settings.additionalLinesCount = 1
                settings.additionalColumnsCount = 1
                settings.isLineNumbersShown = false
                settings.isLineMarkerAreaShown = false
                settings.isIndentGuidesShown = false
                settings.isFoldingOutlineShown = false
                settings.isRightMarginShown = false
                settings.isUseSoftWraps = true
                return editor
            }
        }
    }

    private fun buildListButtons(): JComponent {
        val group = DefaultActionGroup().apply {
            add(addPromptAction)
            add(duplicatePromptAction)
            add(deletePromptAction)
        }
        return toolbar("AgentPromptListToolbar", group)
    }

    private fun buildActionPanel(): JComponent {
        val group = DefaultActionGroup().apply {
            add(savePromptAction)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolbar("AgentPromptActionsToolbar", group), BorderLayout.EAST)
        }
    }

    private fun addPrompt() {
        val prompt = AgentSystemPromptState().apply {
            id = java.util.UUID.randomUUID().toString()
            name = "提示词 ${AgentSystemPromptSupport.customPrompts(project.pluginState().agentSystemPrompts).size + 1}"
            content = AgentSystemPromptSupport.resolvePromptContent(project.pluginState().agentSystemPrompts, "")
        }
        project.pluginState().agentSystemPrompts.add(prompt)
        onChanged?.invoke()
        refreshPromptList(prompt.id)
    }

    private fun duplicatePrompt() {
        val current = selectedPrompt() ?: return
        val duplicate = AgentSystemPromptState().apply {
            id = java.util.UUID.randomUUID().toString()
            name = "${current.name} 副本"
            content = current.content
        }
        project.pluginState().agentSystemPrompts.add(duplicate)
        onChanged?.invoke()
        refreshPromptList(duplicate.id)
    }

    private fun deletePrompt() {
        val prompt = selectedPrompt() ?: return
        if (prompt.id == AgentSystemPromptSupport.DEFAULT_PROMPT_ID) {
            project.errorNotify("系统提示词", "默认提示词不可删除")
            return
        }
        if (Messages.showYesNoDialog(project, "确认删除提示词 `${prompt.name}`？", "删除系统提示词", null) != Messages.YES) {
            return
        }
        project.pluginState().agentSystemPrompts.removeIf { it.id == prompt.id }
        project.pluginState().agentSessions.forEach { session ->
            if (session.systemPromptId == prompt.id) {
                session.systemPromptId = ""
            }
        }
        currentPromptId = null
        onChanged?.invoke()
        refreshPromptList()
    }

    private fun savePrompt() {
        val prompt = selectedPrompt() ?: return
        if (prompt.id == AgentSystemPromptSupport.DEFAULT_PROMPT_ID) {
            return
        }
        val name = nameField.text.trim()
        val content = contentField.text.replace("\r\n", "\n").trim()
        if (name.isBlank()) {
            project.errorNotify("系统提示词", "请填写提示词名称")
            return
        }
        if (content.isBlank()) {
            project.errorNotify("系统提示词", "请填写提示词内容")
            return
        }
        val duplicate = project.pluginState().agentSystemPrompts.firstOrNull { it.id != prompt.id && it.name == name }
        if (duplicate != null) {
            project.errorNotify("系统提示词", "提示词名称已存在: $name")
            return
        }
        prompt.name = name
        prompt.content = content
        onChanged?.invoke()
        refreshPromptList(prompt.id)
        project.infoNotify("系统提示词", "提示词已保存")
    }

    private fun loadPrompt(promptId: String?) {
        currentPromptId = promptId
        val prompt = selectedPrompt()
        if (prompt == null) {
            clearForm()
            return
        }
        nameField.text = prompt.name
        contentField.text = prompt.content
        applyFormState(prompt)
        refreshActionState()
    }

    private fun clearForm() {
        nameField.text = ""
        contentField.text = ""
        applyFormState(null)
        refreshActionState()
    }

    private fun refreshPromptList(selectedId: String? = currentPromptId) {
        val normalized = AgentSystemPromptSupport.normalizePrompts(project.pluginState().agentSystemPrompts)
        project.pluginState().agentSystemPrompts.clear()
        project.pluginState().agentSystemPrompts.addAll(normalized)
        promptListModel.removeAllElements()
        normalized.forEach { promptListModel.addElement(it) }
        if (promptListModel.isEmpty()) {
            currentPromptId = null
            promptList.clearSelection()
            clearForm()
            return
        }
        val resolvedIndex = normalized.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
        promptList.selectedIndex = resolvedIndex
        loadPrompt(normalized[resolvedIndex].id)
    }

    private fun selectedPrompt(): AgentSystemPromptState? {
        val selectedId = currentPromptId ?: return null
        return project.pluginState().agentSystemPrompts.firstOrNull { it.id == selectedId }
    }

    private fun applyFormState(prompt: AgentSystemPromptState?) {
        val hasPrompt = prompt != null
        val editable = hasPrompt && prompt?.id != AgentSystemPromptSupport.DEFAULT_PROMPT_ID
        contentEditable = editable
        nameField.isEnabled = hasPrompt
        nameField.isEditable = editable
        contentField.isEnabled = hasPrompt
        contentEditor?.isViewer = !editable
        val readOnlyHint = if (!editable && prompt?.id == AgentSystemPromptSupport.DEFAULT_PROMPT_ID) {
            "默认提示词不可编辑"
        } else {
            "会话选择器中的显示名称。"
        }
        nameField.toolTipText = if (hasPrompt) readOnlyHint else null
        contentField.toolTipText = when {
            !hasPrompt -> null
            editable -> "保存后会作为当前会话的 system message。"
            else -> "默认提示词不可编辑"
        }
    }

    private fun refreshActionState() {
        actionToolbars.forEach { it.updateActionsImmediately() }
    }

    private fun toolbar(id: String, group: DefaultActionGroup): JComponent {
        val toolbar = ActionManager.getInstance().createActionToolbar(id, group, true)
        toolbar.targetComponent = root
        actionToolbars.add(toolbar)
        return toolbar.component
    }

    private fun toolbarIcon(path: String): javax.swing.Icon {
        return AgentToolbarIconSupport.normalize(findIcon(path))
    }

    private fun iconAction(
        text: String,
        icon: javax.swing.Icon,
        enabledProvider: (() -> Boolean)? = null,
        action: () -> Unit,
    ): AnAction {
        return object : AnAction({ text }, icon) {
            override fun actionPerformed(e: AnActionEvent) {
                action()
            }

            override fun update(e: AnActionEvent) {
                enabledProvider?.let { e.presentation.isEnabled = it() }
            }
        }
    }
}
