package com.lhstack.tools

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.lhstack.tools.ext.notify
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.pluginManager

class ToolsMainWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        this.pluginManager().installs { pluginInfo, plugin, _, _ ->
            ProjectManager.getInstance().openProjects.forEach { openProject ->
                plugin.openProject(openProject) {
                    if (plugin.isUIPlugin) {
                        openProject.messageBus.syncPublisher(ProjectPluginListener.TOPIC)
                            .openPanel(pluginInfo, plugin)
                    } else {
                        openProject.notify(
                            "插件点击通知",
                            "此插件不是UI插件,不存在面板",
                            NotificationType.WARNING
                        )
                    }
                }
            }
        }
        //插件重新安装处理
        this.pluginManager().add(project.locationHash)
        val factory = toolWindow.contentManager.factory
        toolWindow.contentManager.addContent(factory.createContent(ToolsMainView(project), "", true))
    }
}