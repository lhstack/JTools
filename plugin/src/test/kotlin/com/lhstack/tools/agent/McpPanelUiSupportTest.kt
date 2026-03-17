package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import javax.swing.JList
import javax.swing.ScrollPaneConstants
import kotlin.test.assertEquals

class McpPanelUiSupportTest {

    @Test
    fun `fixed list scroll keeps mcp lists at stable height`() {
        val scrollPane = McpPanelUiSupport.fixedListScroll(JList(arrayOf("a", "b", "c")))

        assertEquals(160, scrollPane.preferredSize.height)
        assertEquals(128, scrollPane.minimumSize.height)
        assertEquals(160, scrollPane.maximumSize.height)
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scrollPane.horizontalScrollBarPolicy)
        assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, scrollPane.verticalScrollBarPolicy)
    }
}
