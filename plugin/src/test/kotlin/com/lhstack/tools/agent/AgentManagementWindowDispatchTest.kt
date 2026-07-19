package com.lhstack.tools.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentManagementWindowDispatchTest {
    @Test
    fun `browser command completes before management JCEF window is created`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/lhstack/tools/agent/AgentChatPanel.kt"),
        )
        val commandBranch = source.substringAfter("private fun handleBrowserCommand")
            .substringBefore("private fun handleBrowserQueueCommand")
        val dispatcher = source.substringAfter("private fun openManagementWindowAfterBrowserCommand")
            .substringBefore("private fun refreshQueuePanel")

        assertTrue(commandBranch.contains("openManagementWindowAfterBrowserCommand"))
        assertFalse(commandBranch.contains("\"window.open\" -> managementWindows.open"))
        assertTrue(dispatcher.contains("ApplicationManager.getApplication().invokeLater"))
        assertTrue(dispatcher.contains("if (!project.isDisposed && !Disposer.isDisposed(this)) managementWindows.open(page)"))
    }
    @Test
    fun `management browser creation waits until dialog content is displayable`() {
        val browserSource = Files.readString(
            Path.of("src/main/kotlin/com/lhstack/tools/agent/AgentChatBrowser.kt"),
        )
        val managerSource = Files.readString(
            Path.of("src/main/kotlin/com/lhstack/tools/agent/AgentManagementWindowManager.kt"),
        )

        assertFalse(browserSource.contains("setCreateImmediately(true)"))
        assertTrue(managerSource.contains("add(browser.component, BorderLayout.CENTER)"))
        assertTrue(managerSource.contains("JBUI.Borders.customLine(JBColor.border(), 1)"))
    }

}
