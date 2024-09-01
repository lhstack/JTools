package com.lhstack.tools.exception

import com.lhstack.tools.plugins.PluginInfo

class PluginException(val pluginInfo: PluginInfo, val title: String, val msg: String) : RuntimeException(msg) {
}