package com.lhstack.tools.debugger

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import tools.jackson.databind.json.JsonMapper

/** Client-launched stdio adapter. stdout belongs exclusively to the MCP transport. */
object DebuggerStdioMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2 && args[0] == "--url") { "Usage: java -jar plugin-debugger-0.2.0-mcp.jar --url http://127.0.0.1:<port>/mcp; set JTOOLS_DEBUGGER_TOKEN" }
        val endpoint = URI(args[1])
        require(endpoint.scheme == "http" && endpoint.host in setOf("127.0.0.1", "localhost") && endpoint.path == "/mcp" && endpoint.userInfo == null && endpoint.query == null) { "仅支持本机 debugger /mcp 地址" }
        val token = requireNotNull(System.getenv("JTOOLS_DEBUGGER_TOKEN")) { "缺少 JTOOLS_DEBUGGER_TOKEN" }
        require(token.isNotBlank()) { "JTOOLS_DEBUGGER_TOKEN 不能为空" }
        val remote = HttpClientStreamableHttpTransport.builder("${endpoint.scheme}://${endpoint.rawAuthority}")
            .endpoint("/mcp").requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer $token")).build()
        val client = McpClient.sync(remote).requestTimeout(Duration.ofSeconds(310)).initializationTimeout(Duration.ofSeconds(30)).build()
        client.initialize()
        val done = CountDownLatch(1)
        val input = object : java.io.FilterInputStream(System.`in`) {
            override fun read(): Int = super.read().also { if (it == -1) done.countDown() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it == -1) done.countDown() }
        }
        val mapper = JacksonMcpJsonMapper(JsonMapper.builder().build())
        val server = McpServer.sync(StdioServerTransportProvider(mapper, input, System.out))
            .serverInfo("jtools-debugger-stdio", "0.2.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .toolCall(client.listTools().tools().single { it.name() == DebuggerCommands.TOOL }) { _, request ->
                try { client.callTool(request) }
                catch (error: Exception) {
                    McpSchema.CallToolResult.builder().isError(true).content(listOf(McpSchema.TextContent("${error}\n${error.stackTraceToString()}"))).build()
                }
            }.build()
        Runtime.getRuntime().addShutdownHook(Thread { done.countDown() })
        try { done.await() }
        finally { server.closeGracefully(); client.closeGracefully() }
    }
}
