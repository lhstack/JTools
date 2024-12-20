package com.lhstack.tools;

import com.intellij.build.BuildTextConsoleView
import com.intellij.execution.impl.ConsoleViewUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.lhstack.tools.const.Const
import com.lhstack.tools.const.Icons
import com.lhstack.tools.ext.getConsoleLog

class JLoggerWindowFactory : ToolWindowFactory {

    override fun init(toolWindow: ToolWindow) {
        val project = toolWindow.project
        val consoleView = BuildTextConsoleView(project, true, listOf())
        //初始化
        consoleView.component
        val editor = consoleView.editor
        editor?.let {
            val editorEx = it as EditorEx
            editorEx.settings.isUseSoftWraps = true
        }
        project.putUserData(Const.LOG_CONSOLE_KEY,consoleView)
        toolWindow.setIcon(AllIcons.Toolwindows.ToolWindowRun)
    }

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val contentManager = toolWindow.contentManager
        val factory = contentManager.factory
        val content = factory.createContent(project.getConsoleLog(), Const.TOOLS_WINDOW_ID, false)
        content.icon = Icons.pluginWindowIcon()
        contentManager.addContent(content)
    }
}
