package com.lhstack.tools

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.lhstack.tools.actions.*

class ToolsMainView(private val project: Project) : SimpleToolWindowPanel(false) {
    private val toolBarActionGroup = DefaultActionGroup()

    companion object {
        var actionSupplier: ((SimpleToolWindowPanel, Project) -> AnAction)? = null
    }

    init {
        val pluginPageAction = PluginPageAction(this, project)
        val contentPageAction = ContentPageAction(this, project) {
            if (it == "plugin") {
                setContent(pluginPageAction.getPanel())
            }
        }
        val agentPageAction = AgentPageAction(this, project)
        toolBarActionGroup.add(contentPageAction)
        toolBarActionGroup.add(pluginPageAction)
        toolBarActionGroup.add(agentPageAction)
        actionSupplier?.invoke(this, project)?.apply {
            toolBarActionGroup.add(this)
        }
        toolBarActionGroup.add(OpenConsolePanelAction())
        toolBarActionGroup.add(SettingAction(this, project))
        val actionToolbar =
            ActionManager.getInstance().createActionToolbar("JTools@ToolBar", toolBarActionGroup, false)
        toolbar = actionToolbar.component
        actionToolbar.targetComponent = this
        setContent(contentPageAction.getPanel())
    }
}
