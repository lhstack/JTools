package com.lhstack.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.lhstack.tools.plugins.pluginManager

class ToolsMainWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        this.pluginManager().installs { _, _, _, _ ->
            //处理插件卸载重新安装的逻辑,不会走打开app和打开项目的回调
        }
        //插件重新安装处理
        this.pluginManager().add(project.locationHash)
        val factory = toolWindow.contentManager.factory
        toolWindow.contentManager.addContent(factory.createContent(ToolsMainView(project), "", true))
    }
}