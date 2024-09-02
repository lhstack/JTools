package com.lhstack.tools.actions.plugin

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.lhstack.tools.actions.DynamicIconAction
import com.lhstack.tools.const.Icons
import com.lhstack.tools.exception.PluginException
import com.lhstack.tools.ext.chooseJarFile
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.fullMsg
import com.lhstack.tools.ext.notify
import com.lhstack.tools.listener.PluginListener
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.pluginManager

class InstallPluginAction : DynamicIconAction({ "安装插件" }, {Icons.installIcon()}) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        project?.chooseJarFile("选择要安装的插件") {
            this.pluginManager().install(it.presentableUrl) { plugin, pluginInfo, error ->
                if (error != null) {
                    project.errorNotify("插件安装", error)
                } else {
                    plugin?.let { p ->
                        try {
                            //安装成功,需要通知所有项目的打开事件
                            ProjectManager.getInstance().openProjects.forEach { openProject ->
                                p.openProject(openProject) {
                                    if (plugin.isUIPlugin) {
                                        openProject.messageBus.syncPublisher(ProjectPluginListener.TOPIC)
                                            .openPanel(pluginInfo!!, plugin)
                                    } else {
                                        openProject.notify(
                                            "插件点击通知",
                                            "此插件不是UI插件,不存在面板",
                                            NotificationType.WARNING
                                        )
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            throw PluginException(pluginInfo!!, "打开项目回调", e.fullMsg())
                        }
                        ApplicationManager.getApplication().messageBus.syncPublisher(PluginListener.TOPIC)
                            .install(p, pluginInfo!!)
                    }
                }
            }

        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}