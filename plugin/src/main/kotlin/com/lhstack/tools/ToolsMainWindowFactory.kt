package com.lhstack.tools

import com.intellij.ide.ui.LafManagerListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.lhstack.tools.const.Icons
import com.lhstack.tools.exception.PluginException
import com.lhstack.tools.ext.fullMsg
import com.lhstack.tools.ext.notify
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.PluginType
import com.lhstack.tools.plugins.pluginManager

class ToolsMainWindowFactory : ToolWindowFactory {

    override fun init(toolWindow: ToolWindow) {
        this.pluginManager().installs { pluginInfo, plugin, _, _ ->
            ProjectManager.getInstance().openProjects.forEach { openProject ->
                plugin.openProject(openProject) {
                    try {
                        if (plugin.pluginType() != PluginType.JAVA_NON_UI) {
                            openProject.messageBus.syncPublisher(ProjectPluginListener.TOPIC)
                                .openPanel(pluginInfo, plugin)
                        } else {
                            openProject.notify(
                                "插件点击通知",
                                "此插件不是UI插件,不存在面板",
                                NotificationType.WARNING
                            )
                        }
                    } catch (e: Throwable) {
                        throw PluginException(pluginInfo, "打开项目回调", e.fullMsg())
                    }
                }
            }
        }
        //插件重新安装处理
        this.pluginManager().add(toolWindow.project.locationHash)
        toolWindow.setIcon(Icons.pluginWindowIcon())
        val messageBus = ApplicationManager.getApplication().messageBus
        messageBus.connect().subscribe(LafManagerListener.TOPIC, LafManagerListener {
            toolWindow.setIcon(Icons.pluginWindowIcon())
        })
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = toolWindow.contentManager.factory
        toolWindow.contentManager.addContent(factory.createContent(ToolsMainView(project), "", true))
    }
}