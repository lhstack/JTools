package com.lhstack.tools.components

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.WindowConstants

class FloatingDialog(project: Project, title: String, private val component: JComponent) : JFrame(title) {

    val disposable = Disposer.newDisposable("FloatingDialog${title}")

    init {
        this.setSize(800, 600)
        this.title = title
        this.add(JPanel(BorderLayout()).apply {
            add(component, BorderLayout.CENTER)
        })
        this.defaultCloseOperation = DISPOSE_ON_CLOSE
        this.setLocationRelativeTo(null)
        this.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                Disposer.dispose(disposable)
            }
        })
        Disposer.register(project){
            dispose()
        }
        this.isVisible = false
    }

}