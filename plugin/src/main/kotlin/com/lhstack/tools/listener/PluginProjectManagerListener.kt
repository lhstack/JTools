package com.lhstack.tools.listener

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.lhstack.tools.ext.catch
import com.lhstack.tools.plugins.pluginManager

class PluginProjectManagerListener : ProjectManagerListener {


    override fun projectClosed(project: Project) {

    }

    override fun projectClosing(project: Project) {
        this.pluginManager().remove(project.locationHash) {
            this.pluginManager().plugins { _, plugin ->
                plugin.catch("关闭项目回调") { this.closeProject(project) }
            }
        }

    }

    override fun projectClosingBeforeSave(project: Project) {

    }
}