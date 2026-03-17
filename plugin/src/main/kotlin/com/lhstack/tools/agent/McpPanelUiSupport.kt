package com.lhstack.tools.agent

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

object McpPanelUiSupport {
    private const val DEFAULT_LIST_HEIGHT = 160
    private const val DEFAULT_MIN_LIST_HEIGHT = 128

    fun fixedListScroll(component: JComponent, height: Int = DEFAULT_LIST_HEIGHT): JBScrollPane {
        val scaledHeight = JBUI.scale(height)
        val scaledMinHeight = JBUI.scale(DEFAULT_MIN_LIST_HEIGHT)
        return JBScrollPane(component).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(0, scaledHeight)
            minimumSize = Dimension(0, scaledMinHeight)
            maximumSize = Dimension(Int.MAX_VALUE, scaledHeight)
        }
    }
}
