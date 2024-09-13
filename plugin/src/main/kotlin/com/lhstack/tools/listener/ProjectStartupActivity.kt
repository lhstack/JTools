package com.lhstack.tools.listener;

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.lhstack.tools.exception.PluginException
import com.lhstack.tools.ext.fullMsg
import com.lhstack.tools.ext.notify
import com.lhstack.tools.plugins.PluginType
import com.lhstack.tools.plugins.pluginManager

class ProjectStartupActivity : StartupActivity, DumbAware {

    override fun runActivity(project: Project) {
        ApplicationManager.getApplication().runReadAction {
            this.pluginManager().plugins { pluginInfo, plugin ->
                try {

                    plugin.openProject(project) {
                        if (plugin.pluginType() != PluginType.JAVA_NON_UI) {
                            project.messageBus.syncPublisher(ProjectPluginListener.TOPIC).openPanel(pluginInfo, plugin)
                        } else {
                            project.notify("插件点击通知", "此插件不是UI插件,不存在面板", NotificationType.WARNING)
                        }
                    }
                } catch (e: Throwable) {
                    throw PluginException(pluginInfo, "打开项目回调", e.fullMsg())
                }
            }
        }
        this.pluginManager().add(project.locationHash)
    }
}
