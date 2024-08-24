package com.lhstack.tools.listener

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.plugins.pluginManager

class PluginProjectManagerListener : ProjectManagerListener {


    override fun projectClosed(project: Project) {

    }

    override fun projectClosing(project: Project) {
        this.pluginManager().remove(project.locationHash) {
            this.pluginManager().plugins { _, plugin ->
                try {
                    plugin.closeProject(project)
                } catch (e: Throwable) {
                    e.message?.let { project.errorNotify("项目关闭插件回调", it) }
                }
            }
        }

    }

    override fun projectClosingBeforeSave(project: Project) {

    }
}