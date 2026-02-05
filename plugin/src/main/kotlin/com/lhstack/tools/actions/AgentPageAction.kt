package com.lhstack.tools.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.lhstack.tools.agent.AgentChatPanel
import com.lhstack.tools.const.Icons
import javax.swing.JComponent

class AgentPageAction(windowPanel: SimpleToolWindowPanel, project: Project) :
    AbstractPageAction({ "智能体" }, Icons.helpIcon(), windowPanel) {

    private val panel = AgentChatPanel(project)

    override fun getPanel(): JComponent {
        return panel
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        super.setSelected(e, state)
        if (state) {
            panel.refreshStatus()
        }
    }
}
