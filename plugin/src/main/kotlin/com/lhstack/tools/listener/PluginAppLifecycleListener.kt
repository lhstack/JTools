package com.lhstack.tools.listener;

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.lhstack.tools.ext.catch
import com.lhstack.tools.plugins.pluginManager

class PluginAppLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "插件安装中...", false) {
            override fun run(indicator: ProgressIndicator) {
                this.pluginManager().installs { pluginInfo, _, index, total ->
                    indicator.text = "安装插件: ${pluginInfo.name}成功"
                    indicator.fraction = index.toDouble() / total
                }
                this.pluginManager().catch("删除插件残留卸载文件") {
                    this.clearUnloadingResidue()
                }
            }
        })
    }


    override fun appClosing() {
        this.pluginManager().plugins{_, plugin->
            plugin.catch("app关闭回调") { appClose() }
        }
    }
}
