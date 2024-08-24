package com.lhstack.tools.components

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

class HoverAttachPanel : JPanel(), UserDataHolder {

    private val userDataHolder = UserDataHolderBase()

    companion object {
        val hoverColor = JBColor(Color.decode("#ECECEC"), Color.decode("#333333"))
    }

    init {
        this.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                setBackground(hoverColor)
                setCursor(Cursor(12))
            }

            override fun mouseExited(e: MouseEvent?) {
                setBackground(null as Color?)
                setCursor(Cursor(0))
            }
        })
    }

    override fun <T : Any?> getUserData(key: Key<T>): T? {
        return userDataHolder.getUserData(key)
    }

    override fun <T : Any?> putUserData(key: Key<T>, value: T?) {
        userDataHolder.putUserData(key, value)
    }

}