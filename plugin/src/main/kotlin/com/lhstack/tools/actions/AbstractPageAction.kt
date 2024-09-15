package com.lhstack.tools.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.ui.SimpleToolWindowPanel
import java.util.function.Supplier
import javax.swing.Icon
import javax.swing.JComponent

abstract class AbstractPageAction(
    dynamicText: Supplier<String>,
    dynamicIcon: Icon,
    val windowPanel: SimpleToolWindowPanel
) : ToggleAction(dynamicText, dynamicIcon) {

    abstract fun getPanel(): JComponent


    override fun isSelected(e: AnActionEvent): Boolean {
        return getPanel() == windowPanel.content
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (getPanel() != windowPanel.content) {
            windowPanel.setContent(getPanel())
        }
    }

    fun goToPage() {
        if (getPanel() != windowPanel.content) {
            windowPanel.setContent(getPanel())
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}