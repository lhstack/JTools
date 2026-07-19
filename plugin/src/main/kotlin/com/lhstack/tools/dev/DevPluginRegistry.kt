package com.lhstack.tools.dev

import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo
import java.util.concurrent.atomic.AtomicReference

object DevPluginRegistry {
    private val pluginRef = AtomicReference<IPlugin?>()
    private val pluginInfoRef = AtomicReference<PluginInfo?>()

    fun set(plugin: IPlugin, pluginInfo: PluginInfo) {
        pluginRef.set(plugin)
        pluginInfoRef.set(pluginInfo)
    }

    fun clear() {
        pluginRef.set(null)
        pluginInfoRef.set(null)
    }

    fun plugin(): IPlugin? = pluginRef.get()

    fun pluginInfo(): PluginInfo? = pluginInfoRef.get()
}
