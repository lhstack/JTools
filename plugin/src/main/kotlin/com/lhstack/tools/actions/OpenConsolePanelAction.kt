package com.lhstack.tools.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.activeConsolePanel

class OpenConsolePanelAction:AnAction({"日志控制台"}, Icons.openConsolePanel()) {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.activeConsolePanel()
    }
}