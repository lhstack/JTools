package com.lhstack.tools.const

import com.lhstack.tools.ext.findIcon

class Icons {
    companion object {
        fun pluginIcon() = findIcon("icons/plugin_dark")

        fun installIcon() = findIcon("icons/install")

        fun unInstallIcon() = findIcon("icons/uninstall")

        fun toolIcon() = findIcon("icons/tool")

        fun settingIcon() = findIcon("icons/setting")

        fun addIcon() = findIcon("icons/add", "svg")

        fun developerIcon() = findIcon("icons/developer")

        fun stopHoverIcon() = findIcon("icons/stop_hover")

        fun stopIcon() = findIcon("icons/stop")

        fun helpIcon() = findIcon("icons/help")

        fun notificationIcon() = findIcon("icons/notification")

    }
}