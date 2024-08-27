package com.lhstack.tools.listener;

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.lhstack.tools.plugins.pluginManager
import com.lhstack.tools.plugins.pluginState
import java.io.File

class PluginAppLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "插件安装中...", false) {
            override fun run(indicator: ProgressIndicator) {
                this.pluginManager().installs { pluginInfo, _, index, total ->
                    indicator.text = "安装插件: ${pluginInfo.name}成功"
                    indicator.fraction = index.toDouble() / total
                }
                this.pluginManager().clear()
            }
        })
    }
}
