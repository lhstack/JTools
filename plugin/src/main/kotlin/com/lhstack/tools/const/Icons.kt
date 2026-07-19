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

        fun agentSessionNewIcon() = findIcon("icons/agent_session_new.svg")

        fun agentSessionClearIcon() = findIcon("icons/agent_session_clear.svg")

        fun agentSessionManageIcon() = findIcon("icons/agent_session_manage.svg")

        fun agentModelIcon() = findIcon("icons/agent_model.svg")

        fun agentModelLogIcon() = findIcon("icons/agent_model_log.svg")

        fun agentPromptIcon() = findIcon("icons/agent_prompt.svg")

        fun agentManageIcon() = findIcon("icons/agent_manage.svg")

        fun agentSkillsIcon() = findIcon("icons/agent_skills.svg")

        fun agentGlobalConfigIcon() = findIcon("icons/agent_global_config.svg")

        fun agentAttachmentIcon() = findIcon("icons/agent_attachment.svg")

        fun closeAllIcon() = findIcon("icons/close_all.svg")

        fun newTabIcon() = findIcon("icons/new_tab.svg")

        fun closeOtherIcon() = findIcon("icons/close_other.svg")

        fun exportIcon() = findIcon("icons/export.svg")

        fun libraryIcon() = findIcon("icons/library.svg")

        fun openConsolePanel() = findIcon("icons/console.svg")

        fun movecopy() = findIcon("icons/movecopy.svg")
        fun moveright() = findIcon("icons/moveright.svg")
        fun moveleft() = findIcon("icons/moveleft.svg")

    }
}