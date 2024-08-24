package com.lhstack.tools.listener;

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.plugins.pluginManager

class ProjectStartupActivity : StartupActivity, DumbAware {

    override fun runActivity(project: Project) {
        this.pluginManager().plugins { _, plugin ->
            try {
                plugin.openProject(project)
            } catch (e: Throwable) {
                e.message?.let { project.errorNotify("项目启动插件回调", it) }
            }
        }
        this.pluginManager().add(project.locationHash)
    }
}
