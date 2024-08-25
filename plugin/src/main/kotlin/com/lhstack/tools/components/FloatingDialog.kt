package com.lhstack.tools.components

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.JComponent

class FloatingDialog(project: Project, title: String, private val component: JComponent) : DialogWrapper(project) {

    init {
        super.setModal(false)
        this.setSize(800, 600)
        this.title = title
        this.init()
    }

    override fun createCenterPanel(): JComponent {
        return component
    }
}