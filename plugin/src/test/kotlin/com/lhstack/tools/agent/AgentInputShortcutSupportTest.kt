package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.assertEquals

class AgentInputShortcutSupportTest {

    @Test
    fun `windows style shortcut uses ctrl plus enter`() {
        val stroke = AgentInputShortcutSupport.sendKeyStroke(InputEvent.CTRL_DOWN_MASK)

        assertEquals(KeyEvent.VK_ENTER, stroke.keyCode)
        kotlin.test.assertTrue(stroke.modifiers and InputEvent.CTRL_DOWN_MASK != 0)
        assertEquals("Ctrl+Enter", AgentInputShortcutSupport.sendShortcutLabel(InputEvent.CTRL_DOWN_MASK))
        assertEquals("Ctrl+Enter 发送, Enter 换行", AgentInputShortcutSupport.inputHint(InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `mac style shortcut uses cmd plus enter`() {
        val stroke = AgentInputShortcutSupport.sendKeyStroke(InputEvent.META_DOWN_MASK)

        assertEquals(KeyEvent.VK_ENTER, stroke.keyCode)
        kotlin.test.assertTrue(stroke.modifiers and InputEvent.META_DOWN_MASK != 0)
        assertEquals("Cmd+Enter", AgentInputShortcutSupport.sendShortcutLabel(InputEvent.META_DOWN_MASK))
        assertEquals("Cmd+Enter 发送, Enter 换行", AgentInputShortcutSupport.inputHint(InputEvent.META_DOWN_MASK))
    }
}
