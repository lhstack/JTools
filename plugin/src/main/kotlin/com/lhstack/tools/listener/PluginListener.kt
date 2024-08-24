package com.lhstack.tools.listener

import com.intellij.util.messages.Topic
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo

interface PluginListener {
    companion object {
        val TOPIC = Topic.create("ToolsPluginTopic", PluginListener::class.java)
    }

    fun install(plugin: IPlugin, pluginInfo: PluginInfo)

    fun uninstall(plugin: IPlugin, pluginInfo: PluginInfo)
}