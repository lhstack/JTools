package com.lhstack.tools.actions.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.chooseJarFile
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.listener.PluginListener
import com.lhstack.tools.plugins.pluginManager

class InstallPluginAction : AnAction({ "安装插件" }, Icons.INSTALL_ICON) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        project?.chooseJarFile {
            this.pluginManager().install(it.presentableUrl) { plugin, pluginInfo, error ->
                if (error != null) {
                    project.errorNotify("插件安装", error)
                } else {
                    plugin?.let { p ->
                        try {
                            p.openProject(project)
                        } catch (e: Throwable) {
                            e.message?.let { it1 -> project.errorNotify("项目启动插件回调", it1) }
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