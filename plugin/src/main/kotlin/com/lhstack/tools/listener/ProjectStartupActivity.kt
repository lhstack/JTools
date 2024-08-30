package com.lhstack.tools.listener;

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.ext.notify
import com.lhstack.tools.plugins.pluginManager

class ProjectStartupActivity : StartupActivity, DumbAware {

    override fun runActivity(project: Project) {
        this.pluginManager().plugins { pluginInfo, plugin ->
            try {
                plugin.openProject(project) {
                    if (plugin.isUIPlugin) {
                        project.messageBus.syncPublisher(ProjectPluginListener.TOPIC).openPanel(pluginInfo, plugin)
                    } else {
                        project.notify("插件点击通知", "此插件不是UI插件,不存在面板", NotificationType.WARNING)
                    }
                }
            } catch (e: Throwable) {
                e.message?.let { project.errorNotify("项目启动插件回调", it) }
            }
        }
        this.pluginManager().add(project.locationHash)
    }
}
