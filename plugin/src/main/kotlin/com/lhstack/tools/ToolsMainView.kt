package com.lhstack.tools

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.lhstack.tools.actions.*
import org.apache.commons.lang3.StringUtils
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
            val productCode = ApplicationInfo.getInstance().build.productCode
            if(StringUtils.containsAnyIgnoreCase(productCode,"IU","IC")){
                toolBarActionGroup.add(DeveloperPageAction(this, project))
            }
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