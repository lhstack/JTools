package com.lhstack.tools.agent

import org.cef.handler.CefKeyboardHandler
import org.cef.misc.EventFlags
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentBrowserShortcutSupportTest {

    @Test
    fun `ctrl enter in editable browser field is send shortcut`() {
        assertTrue(AgentBrowserShortcutSupport.isSendShortcut(keyEvent(EventFlags.EVENTFLAG_CONTROL_DOWN)))
    }

    @Test
    fun `command enter in editable browser field is send shortcut`() {
        assertTrue(AgentBrowserShortcutSupport.isSendShortcut(keyEvent(EventFlags.EVENTFLAG_COMMAND_DOWN)))
    }

    @Test
    fun `plain enter remains available for newline`() {
        assertFalse(AgentBrowserShortcutSupport.isSendShortcut(keyEvent(EventFlags.EVENTFLAG_NONE)))
    }

    @Test
    fun `shortcut outside editable browser field is ignored`() {
        assertFalse(
            AgentBrowserShortcutSupport.isSendShortcut(
                keyEvent(EventFlags.EVENTFLAG_CONTROL_DOWN, focusOnEditableField = false),
            )
        )
    }

    @Test
    fun `non enter shortcut is ignored`() {
        assertFalse(
            AgentBrowserShortcutSupport.isSendShortcut(
                keyEvent(EventFlags.EVENTFLAG_CONTROL_DOWN, keyCode = KeyEvent.VK_A),
            )
        )
    }

    private fun keyEvent(
        modifiers: Int,
        keyCode: Int = KeyEvent.VK_ENTER,
        focusOnEditableField: Boolean = true,
    ): CefKeyboardHandler.CefKeyEvent {
        // CefKeyboardHandler.CefKeyEvent constructor is package-private,
        // so we use reflection to create instances for unit testing.
        val constructor = CefKeyboardHandler.CefKeyEvent::class.java.getDeclaredConstructor(
            CefKeyboardHandler.CefKeyEvent.EventType::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Char::class.javaPrimitiveType,
            Char::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN,
            modifiers,
            keyCode,
            keyCode,
            false,
            KeyEvent.CHAR_UNDEFINED,
            KeyEvent.CHAR_UNDEFINED,
            focusOnEditableField,
        )
    }
}
