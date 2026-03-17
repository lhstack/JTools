package com.lhstack.tools.agent

import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

object AgentInputShortcutSupport {

    fun sendShortcutMask(): Int = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx

    fun sendKeyStroke(mask: Int = sendShortcutMask()): KeyStroke {
        return KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask)
    }

    fun sendShortcutLabel(mask: Int = sendShortcutMask()): String {
        return if (mask and InputEvent.META_DOWN_MASK != 0) {
            "Cmd+Enter"
        } else {
            "Ctrl+Enter"
        }
    }

    fun inputHint(mask: Int = sendShortcutMask()): String {
        return "${sendShortcutLabel(mask)} 发送, Enter 换行"
    }
}
