package com.lhstack.tools

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.lhstack.tools.actions.ContentPageAction
import com.lhstack.tools.actions.DeveloperPageAction
import com.lhstack.tools.actions.PluginPageAction
import com.lhstack.tools.actions.SettingAction

class ToolsMainView(private val project: Project) : SimpleToolWindowPanel(false) {
    private val toolBarActionGroup = DefaultActionGroup()

    init {
        val pluginPageAction = PluginPageAction(this, project)
        val contentPageAction = ContentPageAction(this, project) {
            if (it == "plugin") {
                setContent(pluginPageAction.getPanel())
            }
        }
        toolBarActionGroup.add(contentPageAction)
        toolBarActionGroup.add(pluginPageAction)
        toolBarActionGroup.add(DeveloperPageAction(this,project))
        toolBarActionGroup.add(SettingAction(this,project))
        val actionToolbar =
            ActionManager.getInstance().createActionToolbar("JTools@ToolBar", toolBarActionGroup, false)
        toolbar = actionToolbar.component
        actionToolbar.targetComponent = this
        setContent(contentPageAction.getPanel())
    }
}