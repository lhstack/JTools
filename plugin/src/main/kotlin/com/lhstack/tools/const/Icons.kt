package com.lhstack.tools.const

import com.intellij.openapi.util.IconLoader
import com.lhstack.tools.ToolsMainWindowFactory
import com.lhstack.tools.ext.findIcon

class Icons {
    companion object {
        fun pluginIcon() = findIcon("icons/plugin.svg")

        fun installIcon() = findIcon("icons/install.svg")

        fun unInstallIcon() = findIcon("icons/uninstall.svg")

        fun toolIcon() = findIcon("icons/tool.svg")

        fun settingIcon() = findIcon("icons/setting.svg")

        fun addIcon() = findIcon("icons/add.svg")

        fun addHoverIcon() = findIcon("icons/add_hover.svg")

        fun developerIcon() = findIcon("icons/developer.svg")

        fun stopHoverIcon() = findIcon("icons/stop_hover.svg")

        fun stopIcon() = findIcon("icons/stop.svg")

        fun helpIcon() = findIcon("icons/help.svg")

        fun notificationIcon() = findIcon("icons/notification.svg")

        fun pluginWindowIcon() = findIcon("icons/pluginIcon.svg")

        fun jsIcon() = findIcon("icons/js.svg")

        fun closeAllIcon() = findIcon("icons/close_all.svg")

        fun newTabIcon() = findIcon("icons/new_tab.svg")

        fun closeOtherIcon() = findIcon("icons/close_other.svg")

        fun exportIcon() = findIcon("icons/export.svg")

        fun libraryIcon() = findIcon("icons/library.svg")

        fun openConsolePanel() = findIcon("icons/console.svg")

    }
}