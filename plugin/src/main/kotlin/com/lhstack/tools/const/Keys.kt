package com.lhstack.tools.const

import com.intellij.openapi.util.Key
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo

class Keys {
    companion object {
        val PLUGIN_INFO_KEY = Key.create<PluginInfo>("PLUGIN_INFO_KEY")

        val PLUGIN_KEY = Key.create<IPlugin>("PLUGIN_KEY")
    }
}