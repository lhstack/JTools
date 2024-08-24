package com.lhstack.tools.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.util.Icons

class SettingAction() : AnAction(
    { "设置" },
    Icons.SHOW_SETTINGS_ICON
) {
    override fun actionPerformed(e: AnActionEvent) {
        Messages.showInfoMessage("设置页","提示")
    }
}