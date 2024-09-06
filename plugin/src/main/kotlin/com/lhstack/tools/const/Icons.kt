package com.lhstack.tools.const

import com.intellij.openapi.util.IconLoader
import com.lhstack.tools.ToolsMainWindowFactory
import com.lhstack.tools.ext.findIcon

class Icons {
    companion object {
        fun pluginIcon() = findIcon("icons/plugin")

        fun installIcon() = findIcon("icons/install")

        fun unInstallIcon() = findIcon("icons/uninstall")

        fun toolIcon() = findIcon("icons/tool")

        fun settingIcon() = findIcon("icons/setting")

        fun addIcon() = findIcon("icons/add", "svg")

        fun developerIcon() = findIcon("icons/developer")

        fun stopHoverIcon() = IconLoader.findIcon("icons/stop_hover.svg", ToolsMainWindowFactory::class.java)

        fun stopIcon() = findIcon("icons/stop")

        fun helpIcon() = findIcon("icons/help")

        fun notificationIcon() = findIcon("icons/notification")

        fun pluginWindowIcon() = findIcon("icons/pluginIcon")

        fun jsIcon() = findIcon("icons/js")

    }
}