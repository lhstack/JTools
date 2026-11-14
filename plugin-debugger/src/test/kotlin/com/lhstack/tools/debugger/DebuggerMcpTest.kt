package com.lhstack.tools.debugger

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import tools.jackson.databind.json.JsonMapper
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

class DebuggerMcpTest {
    @TempDir lateinit var root: Path

    @Test
    fun `streamable HTTP and legacy SSE expose the same tool and command help`() = service { service, token ->
        for (sse in listOf(false, true)) {
            client(service.port, token, sse).use { client ->
                client.initialize()
                assertEquals(listOf("sandbox_debug"), client.listTools().tools().map { it.name() })
                val result = client.callTool(McpSchema.CallToolRequest("sandbox_debug", mapOf("command" to "help")))
                assertFalse(result.isError() == true, result.toString())
                listOf("methods", "invoke", "discover", "JetBrains").forEach { assertContains(result.toString(), it) }
                val failure = client.callTool(McpSchema.CallToolRequest("sandbox_debug", mapOf("command" to "status", "arguments" to mapOf("sandbox_id" to "missing"))))
                assertTrue(failure.isError() == true)
                assertContains(failure.toString(), "沙箱不存在")
            }
        }
    }

    @Test
    fun `authentication origin and transport session ownership are enforced`() = service { service, token ->
        val http = HttpClient.newHttpClient()
        val endpoint = URI("http://127.0.0.1:${service.port}/mcp")
        fun post(body: String, credential: String?, origin: String? = null, session: String? = null): HttpResponse<String> {
            val request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
            credential?.let { request.header("Authorization", "Bearer $it") }
            origin?.let { request.header("Origin", it) }
            session?.let { request.header("Mcp-Session-Id", it) }
            return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        }
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        assertEquals(401, post(init, null).statusCode())
        assertEquals(403, post(init, token, "https://example.com").statusCode())
        val response = post(init, token)
        assertEquals(200, response.statusCode(), response.body())
        val session = response.headers().firstValue("Mcp-Session-Id").orElseThrow()
        val other = service.newClient()
        assertEquals(403, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", other, session = session).statusCode())
    }

    @Test
    fun `client launched bundled stdio adapter forwards real MCP calls and exits on EOF`() = service { service, token ->
        val java = Path.of(System.getProperty("debugger.test.jdk"), "bin/java").toString()
        val jar = System.getProperty("debugger.test.jar")
        val transport = StdioClientTransport(ServerParameters.builder(java)
            .args("-jar", jar, "--url", "http://127.0.0.1:${service.port}/mcp")
            .env(mapOf("JTOOLS_DEBUGGER_TOKEN" to token)).build(), JacksonMcpJsonMapper(JsonMapper.builder().build()))
        transport.setStdErrorHandler { System.err.println("BRIDGE: $it") }
        McpClient.sync(transport).initializationTimeout(Duration.ofSeconds(30)).requestTimeout(Duration.ofSeconds(30)).build().use { client ->
            client.initialize()
            assertEquals("sandbox_debug", client.listTools().tools().single().name())
            val result = client.callTool(McpSchema.CallToolRequest("sandbox_debug", mapOf("command" to "help")))
            assertFalse(result.isError() == true, result.toString())
            assertContains(result.toString(), "methods")
            assertContains(result.toString(), "invoke")
        }
    }

    @Test
    fun `SSE transport session cannot be borrowed by another client token`() = service { server, token ->
        val http = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/sse"))
            .header("Authorization", "Bearer $token").header("Accept", "text/event-stream").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(200, response.statusCode())
        response.body().bufferedReader().use { reader ->
            var endpoint: String? = null
            while (endpoint == null) {
                val line = reader.readLine() ?: error("SSE endpoint was not announced")
                if (line.startsWith("data:")) endpoint = line.removePrefix("data:").trim()
            }
            val attack = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$endpoint"))
                .header("Authorization", "Bearer ${server.newClient()}").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")).build()
            assertEquals(403, http.send(attack, HttpResponse.BodyHandlers.ofString()).statusCode())
        }
    }

    @Test
    fun `shared lifecycle replays current state and publishes across project bus subscriptions`() {
        SandboxController().use { controller ->
            val bus = debuggerTestMessageBus()
            try {
                val commands = DebuggerCommands(controller, JetBrainsDiscovery(), { Path.of(System.getProperty("debugger.test.jar")) }, { root })
                DebuggerMcpServer(commands, bus).use { service ->
                    val first = mutableListOf<DebuggerMcpServer.State>()
                    val second = mutableListOf<DebuggerMcpServer.State>()
                    val a = bus.connect()
                    val b = bus.connect()
                    a.subscribe(DebuggerMcpListener.TOPIC, DebuggerMcpListener { first.add(it) })
                    b.subscribe(DebuggerMcpListener.TOPIC, DebuggerMcpListener { second.add(it) })
                    val token = service.start(0)
                    val port = service.state().port
                    assertTrue(port > 0)
                    assertEquals(token, service.start(port + if (port == 65535) -1 else 1))
                    assertEquals(port, service.state().port)
                    assertEquals(first, second)
                    val updated = service.newClient()
                    assertEquals(updated, second.last().token)
                    assertNotEquals(token, updated)
                    a.disconnect()
                    val count = first.size
                    service.close()
                    assertFalse(second.last().running)
                    assertEquals(count, first.size)
                    service.close()
                    assertFalse(service.state().running)
                    service.start(0)
                    assertEquals(updated, service.state().token)
                    assertEquals(service.state(), second.last())
                    b.disconnect()
                }
            } finally { bus.dispose() }
        }
    }

    @Test
    fun `open panels synchronize and reopened panel restores live service without restarting`() = service { service, token ->
        val frames = mutableListOf<javax.swing.JFrame>()
        val panels = mutableListOf<DebuggerMcpPanel>()
        fun open(): DebuggerMcpPanel {
            lateinit var panel: DebuggerMcpPanel
            javax.swing.SwingUtilities.invokeAndWait {
                panel = DebuggerMcpPanel(service) { Path.of(System.getProperty("debugger.test.jar")) }
                frames.add(javax.swing.JFrame("Debugger MCP state test").apply {
                    contentPane.add(panel); setSize(1000, 600); isVisible = true
                })
                panels.add(panel)
            }
            return panel
        }
        fun configuration(panel: DebuggerMcpPanel): String {
            var value = ""
            javax.swing.SwingUtilities.invokeAndWait {
                fun visit(component: java.awt.Component) {
                    if (component is javax.swing.JTextArea && component.name == "debugger-mcp-Streamable HTTP") value = component.text
                    if (component is java.awt.Container) component.components.forEach(::visit)
                    assertFalse(component is javax.swing.JButton && component.text == "复制启动命令")
                }
                visit(panel)
            }
            return value
        }
        try {
            val first = open()
            val second = open()
            assertEquals(configuration(first), configuration(second))
            assertContains(configuration(first), token)
            javax.swing.SwingUtilities.invokeAndWait {
                val components = mutableListOf<java.awt.Component>()
                fun collect(component: java.awt.Component) {
                    components.add(component)
                    if (component is java.awt.Container) component.components.forEach(::collect)
                }
                collect(first)
                val tabs = components.filterIsInstance<javax.swing.JTabbedPane>().single()
                assertEquals(listOf("Streamable HTTP", "SSE", "stdio"), (0 until tabs.tabCount).map(tabs::getTitleAt))
                val copy = components.filterIsInstance<javax.swing.JButton>().single { it.text == "复制配置" }
                for (index in 0 until tabs.tabCount) {
                    tabs.selectedIndex = index
                    copy.doClick()
                    val copied = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
                    val parsed = com.google.gson.JsonParser.parseString(copied).asJsonObject
                    assertEquals(setOf("mcpServers"), parsed.keySet())
                    val config = parsed.getAsJsonObject("mcpServers").getAsJsonObject("jtools-debugger")
                    when (index) {
                        0 -> assertEquals("http", config.get("type").asString)
                        1 -> assertEquals("sse", config.get("type").asString)
                        2 -> { assertTrue(config.has("command")); assertTrue(config.has("args")); assertTrue(config.has("env")) }
                    }
                }
            }
            val originalPort = service.state().port
            val newer = service.newClient()
            assertContains(configuration(first), newer)
            assertEquals(configuration(first), configuration(second))
            javax.swing.SwingUtilities.invokeAndWait { frames.first().dispose() }
            val reopened = open()
            assertEquals(originalPort, service.state().port)
            assertContains(configuration(reopened), newer)
            service.close()
            listOf(second, reopened).forEach { assertContains(configuration(it), "已停止") }
            service.close()
            service.start(0)
            assertContains(configuration(reopened), newer)
            assertEquals(configuration(second), configuration(reopened))
        } finally {
            javax.swing.SwingUtilities.invokeAndWait { frames.forEach { it.dispose() } }
        }
    }

    private fun service(action: (DebuggerMcpServer, String) -> Unit) {
        SandboxController().use { controller ->
            val dispatch = DebuggerCommands(controller, JetBrainsDiscovery(), { Path.of(System.getProperty("debugger.test.jar")) }, { root })
            val bus = debuggerTestMessageBus()
            try { DebuggerMcpServer(dispatch, bus).use { server -> action(server, server.start(0)) } }
            finally { bus.dispose() }
        }
    }

    private fun client(port: Int, token: String, sse: Boolean): McpSyncClient {
        val base = "http://127.0.0.1:$port"
        val request = HttpRequest.newBuilder().header("Authorization", "Bearer $token")
        val transport = if (sse) HttpClientSseClientTransport.builder(base).sseEndpoint("/sse").requestBuilder(request).build()
            else HttpClientStreamableHttpTransport.builder(base).endpoint("/mcp").requestBuilder(request).build()
        return McpClient.sync(transport).initializationTimeout(Duration.ofSeconds(15)).requestTimeout(Duration.ofSeconds(15)).build()
    }
}

internal fun debuggerTestMessageBus(): com.intellij.util.messages.MessageBus =
    com.intellij.util.messages.impl.MessageBusFactoryImpl.createRootBus(object : com.intellij.util.messages.MessageBusOwner {
        override fun createListener(descriptor: com.intellij.util.messages.impl.PluginListenerDescriptor): Any = error("No declarative listeners in this test")
        override fun isDisposed() = false
    })
