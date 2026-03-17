package com.lhstack.tools.agent

import com.lhstack.tools.plugins.PluginState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentMcpFunctionToolsTest {

    @Test
    fun `add update delete and enable mcp server`() {
        val state = PluginState.State()

        val addResult = AgentMcpFunctionTools.addServer(
            state = state,
            input = AgentMcpAddServerInput(
                name = "demo",
                transport = "stdio",
                stdioCommand = "npx",
                stdioArgs = listOf("-y", "@demo/server"),
                stdioEnv = mapOf("A" to "1")
            )
        )

        assertTrue(addResult.ok)
        assertEquals(1, state.agentMcpServers.size)
        val server = state.agentMcpServers.single()
        assertTrue(server.id.isNotBlank())
        assertEquals("demo", server.name)
        assertEquals("stdio", server.transport)
        assertEquals("npx", server.stdioCommand)
        assertEquals(listOf("-y", "@demo/server"), server.stdioArgs)
        assertEquals(mapOf("A" to "1"), server.stdioEnv)

        val updateResult = AgentMcpFunctionTools.updateServer(
            state = state,
            input = AgentMcpUpdateServerInput(
                serverId = server.id,
                transport = "streamable-http",
                url = "https://example.com/mcp",
                headers = mapOf("Authorization" to "Bearer demo")
            )
        )

        assertTrue(updateResult.ok)
        assertEquals("streamable-http", server.transport)
        assertEquals("https://example.com/mcp", server.url)
        assertEquals("Bearer demo", server.headers["Authorization"])

        val setEnabledResult = AgentMcpFunctionTools.setEnabled(state, server.id, false)
        assertTrue(setEnabledResult.ok)
        assertFalse(server.enabled)

        val invalidated = mutableListOf<String>()
        val deleteResult = AgentMcpFunctionTools.deleteServer(state, server.id) { invalidated.add(it) }
        assertTrue(deleteResult.ok)
        assertTrue(state.agentMcpServers.isEmpty())
        assertEquals(listOf(server.id), invalidated)
    }

    @Test
    fun `test server delegates to tester`() {
        val state = PluginState.State().apply {
            agentMcpServers.add(McpServerState().apply {
                id = "server-1"
                name = "Demo"
                transport = "stdio"
                stdioCommand = "npx"
            })
        }

        val result = AgentMcpFunctionTools.testServer(state, "server-1") { server ->
            mapOf("reachable" to true, "name" to server.name)
        }

        assertTrue(result.ok)
        val data = assertNotNull(result.data)
        assertEquals(true, data["reachable"])
        assertEquals("Demo", data["name"])
    }
}
