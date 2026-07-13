package com.lhstack.tools.agent

import com.google.gson.Gson
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

internal class AgentManagementWindowManager(
    private val project: Project,
    private val gson: Gson,
    private val onChanged: () -> Unit,
) : Disposable {
    private val windows = mutableMapOf<String, ManagementDialog>()

    init {
        project.messageBus.connect(this).subscribe(LafManagerListener.TOPIC, LafManagerListener {
            windows.values.forEach { it.refreshTheme() }
        })
    }

    fun open(page: String) {
        require(page in TITLES) { "不支持的管理页面: $page" }
        windows[page]?.takeIf { it.isShowing }?.let {
            it.toFront()
            return
        }
        ManagementDialog(page).also {
            windows[page] = it
            it.show()
        }
    }

    override fun dispose() {
        windows.values.toList().forEach { it.close(DialogWrapper.CANCEL_EXIT_CODE) }
        windows.clear()
    }

    private inner class ManagementDialog(private val page: String) : DialogWrapper(project, false) {
        @Volatile
        private var browserPopupOpen = false
        private val browser = AgentChatBrowser(gson, ::handleCommand, page, onEscapeKey = ::onBrowserEscape)

        init {
            title = TITLES.getValue(page)
            setModal(false)
            setResizable(true)
            init()
            window?.background = com.intellij.util.ui.UIUtil.getPanelBackground()
        }

        override fun createCenterPanel(): JComponent = browser.component.apply {
            preferredSize = Dimension(JBUI.scale(1100), JBUI.scale(720))
            minimumSize = Dimension(JBUI.scale(720), JBUI.scale(480))
        }

        override fun createActions(): Array<Action> = emptyArray()

        override fun doCancelAction() {
            if (browserPopupOpen) return
            super.doCancelAction()
        }

        private fun onBrowserEscape(): Boolean {
            if (browserPopupOpen) return false
            ApplicationManager.getApplication().invokeLater {
                if (isShowing && !browserPopupOpen) close(CANCEL_EXIT_CODE)
            }
            return true
        }

        override fun dispose() {
            windows.remove(page, this)
            Disposer.dispose(browser)
            super.dispose()
        }

        fun refreshTheme() = browser.replaceState(themeState())

        private fun handleCommand(command: AgentBrowserCommand): Any? {
            when (command.type) {
                "ui.ready" -> {
                    browser.replaceState(themeState())
                    return Unit
                }
                "ui.popupState" -> {
                    browserPopupOpen = command.payload.get("open")?.asBoolean == true
                    return Unit
                }
            }
            return AgentBrowserManagement.handle(project, command.type, command.payload) { onChanged() }
        }
    }

    private fun themeState(): Map<String, Any> {
        val background = com.intellij.util.ui.UIUtil.getPanelBackground()
        fun color(value: java.awt.Color) = "#${com.intellij.ui.ColorUtil.toHex(value)}"
        return mapOf(
            "dark" to com.intellij.ui.ColorUtil.isDark(background),
            "theme" to mapOf(
                "background" to color(background),
                "panel" to color(com.intellij.util.ui.UIUtil.getPanelBackground()),
                "input" to color(com.intellij.util.ui.UIUtil.getTextFieldBackground()),
                "text" to color(com.intellij.util.ui.UIUtil.getLabelForeground()),
                "muted" to color(com.intellij.util.ui.UIUtil.getContextHelpForeground()),
                "border" to color(com.intellij.ui.JBColor.border()),
                "accent" to color(com.intellij.ui.JBColor(0x3574F0, 0x548AF7)),
                "fontFamily" to com.intellij.util.ui.UIUtil.getLabelFont().family,
            ),
        )
    }

    private companion object {
        val TITLES = mapOf(
            "sessions" to "会话管理",
            "logs" to "模型日志",
            "catalog" to "供应商与模型",
            "prompts" to "提示词管理",
            "agents" to "Agent 管理",
            "skills" to "Skills 管理",
            "settings" to "全局设置",
        )
    }
}
