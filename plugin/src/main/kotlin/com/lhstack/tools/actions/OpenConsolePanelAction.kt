package com.lhstack.tools.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.activeConsolePanel
import com.lhstack.tools.ext.deActiveConsolePanel
import com.lhstack.tools.plugins.PluginState

class OpenConsolePanelAction : ToggleAction({ "日志控制台" }, Icons.openConsolePanel()) {


    override fun isSelected(e: AnActionEvent): Boolean = PluginState.getInstance().state.consoleLogEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        PluginState.getInstance().state.consoleLogEnabled = state
        if (!state) {
            e.project?.deActiveConsolePanel()
        } else {
            e.project?.activeConsolePanel()
        }
    }
}