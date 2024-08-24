package com.lhstack.tools.components;

import com.intellij.openapi.ui.VerticalFlowLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class EmptyPanel(button: JButton, text: String) : JPanel() {
    init {
        layout = VerticalFlowLayout(1, true, false)
        this.add(JLabel(text, JLabel.CENTER))
        val jPanel = JPanel()
        jPanel.layout = FlowLayout(1)
        jPanel.add(button)
        this.add(jPanel)
    }
}