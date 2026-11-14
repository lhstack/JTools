package com.lhstack.tools.debugger

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Test
import kotlin.test.*

class DebuggerPluginTest {
    private fun project(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader, arrayOf(Project::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getLocationHash" -> "debugger-test-project"
            else -> error("测试未提供 Project.${method.name}")
        }
    } as Project

    @Test
    fun `catalog without session lists debugger but execution requires current call session`() {
        val plugin = DebuggerPlugin()
        try {
            val function = plugin.functionCallings(null, null, project()).single()
            assertEquals("sandbox_debug", function.name())
            assertTrue(function.parameters().contains("command"))
            assertFailsWith<IllegalStateException> { function.call("""{"command":"help"}""") }
            assertFailsWith<IllegalArgumentException> { function.call("""{"command":"help"}""", "session", "") }
            assertContains(function.call("""{"command":"help"}""", "session A", "a"), "start")
            assertContains(function.call("""{"command":"help"}""", "session B", "b", "provider", "model"), "start")
        } finally {
            plugin.unInstall()
        }
    }

    @Test
    fun `enumeration context cannot supply stale session identity to execution`() {
        val plugin = DebuggerPlugin()
        try {
            val function = plugin.functionCallings("old session", "old-id", project()).single()
            assertFailsWith<IllegalStateException> { function.call("""{"command":"help"}""") }
            assertFailsWith<IllegalArgumentException> { function.call("""{"command":"help"}""", "", "new-id") }
            assertContains(function.call("""{"command":"help"}""", "new session", "new-id"), "start")
        } finally {
            plugin.unInstall()
        }
    }
    @Test
    fun `plugin and tab icons have independent contract sizes`() {
        val plugin = DebuggerPlugin()
        try {
            val icon = plugin.pluginIcon()
            val tab = plugin.pluginTabIcon()
            assertEquals(48, icon.iconWidth)
            assertEquals(48, icon.iconHeight)
            assertEquals(14, tab.iconWidth)
            assertEquals(14, tab.iconHeight)
        } finally {
            plugin.unInstall()
        }
    }
}
