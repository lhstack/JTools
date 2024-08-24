package com.lhstack.tools.listener

import com.intellij.util.messages.Topic
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo

interface ProjectPluginListener {
    fun uninstall(plugin: IPlugin, pluginInfo: PluginInfo)
    fun openPanel(pluginInfo: PluginInfo, plugin: IPlugin)

    companion object {
        val TOPIC = Topic.create("ProjectToolsPluginTopic", ProjectPluginListener::class.java)
    }
}