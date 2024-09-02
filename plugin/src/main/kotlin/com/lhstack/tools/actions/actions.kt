package com.lhstack.tools.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import java.util.function.Supplier
import javax.swing.Icon

abstract class DynamicIconAction(private val dynamicText: Supplier<String>, private val dynamicIcon: () -> Icon) :
    AnAction(dynamicText, dynamicIcon.invoke()) {

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = dynamicIcon.invoke()
    }
}

abstract class DynamicToggleIconAction(private val dynamicText: Supplier<String>, private val dynamicIcon: () -> Icon):ToggleAction(dynamicText,dynamicIcon.invoke()){
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = dynamicIcon.invoke()
    }
}