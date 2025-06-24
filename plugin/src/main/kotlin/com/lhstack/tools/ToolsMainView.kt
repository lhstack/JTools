package com.lhstack.tools

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.lhstack.tools.actions.*

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
        try{
            val constructor = Class.forName("com.lhstack.tools.actions.DeveloperPageAction.DeveloperPageAction")
                .getConstructor(SimpleToolWindowPanel::class.java, Project::class.java)
            toolBarActionGroup.add(constructor.newInstance(this, project) as AbstractPageAction)
        }catch (ignore: Throwable){

        }
        toolBarActionGroup.add(OpenConsolePanelAction())
        toolBarActionGroup.add(SettingAction(this,project))
        val actionToolbar =
            ActionManager.getInstance().createActionToolbar("JTools@ToolBar", toolBarActionGroup, false)
        toolbar = actionToolbar.component
        actionToolbar.targetComponent = this
        setContent(contentPageAction.getPanel())
    }
}