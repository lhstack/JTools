package com.lhstack.tools.debugger

import com.google.gson.JsonParser
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import jakarta.servlet.DispatcherType
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.servlet.FilterHolder
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import tools.jackson.databind.json.JsonMapper

/** Application-wide endpoint shared by every project and panel; client credentials own sandbox scopes. */
internal class DebuggerMcpServer(private val commands: DebuggerCommands, val messageBus: com.intellij.util.messages.MessageBus, private val settings: DebuggerMcpSettings) : AutoCloseable {
    private var server: Server? = null
    private val protocols = mutableListOf<McpSyncServer>()
    private val clients = ConcurrentHashMap<String, String>()
    private val mapper = JacksonMcpJsonMapper(JsonMapper.builder().build())
    data class State(val revision: Long, val port: Int, val token: String?, val configuredPort: Int, val error: String?) {
        val running: Boolean get() = port != 0
    }
    private var revision = 0L
    private var activeToken: String? = null
    private var configuredPort = 0
    private var enabled = false
    private var error: String? = null
    @Synchronized
    fun state(): State = State(revision, port, activeToken, configuredPort, error)

    private fun publish() {
        revision++
        messageBus.syncPublisher(DebuggerMcpListener.TOPIC).stateChanged(state())
    }
    var port: Int = 0; private set

    @Synchronized
    fun restore() {
        try {
            val saved = settings.read()
            enabled = saved.enabled
            configuredPort = saved.port
            activeToken = saved.activeToken
            clients.putAll(saved.clients)
            if (enabled) start(configuredPort) else publish()
        } catch (failure: Exception) {
            error = "MCP 配置恢复失败：${failure}"
            publish()
            throw failure
        }
    }

    private fun persist() = settings.write(DebuggerMcpSettings.Saved(enabled, configuredPort, activeToken, clients.toMap()))

    @Synchronized
    fun start(requestedPort: Int): String {
        if (server != null) {
            publish()
            return checkNotNull(activeToken)
        }
        require(requestedPort in 0..65535) { "端口须为 0..65535，0 表示自动分配" }
        val http = Server()
        val connector = ServerConnector(http).apply { host = "127.0.0.1"; port = requestedPort }
        http.addConnector(connector)
        val context = ServletContextHandler().apply { contextPath = "/" }
        http.handler = context
        context.addFilter(FilterHolder(McpAccessFilter(clients) { connector.localPort })
            .apply { isAsyncSupported = true }, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC))
        val stream = HttpServletStreamableServerTransportProvider.builder().jsonMapper(mapper).mcpEndpoint("/mcp")
            .contextExtractor { McpTransportContext.create(mapOf("owner" to it.getAttribute("debugger.owner"))) }
            .keepAliveInterval(Duration.ofSeconds(15)).build()
        val sse = HttpServletSseServerTransportProvider.builder().jsonMapper(mapper).sseEndpoint("/sse").messageEndpoint("/messages")
            .contextExtractor { McpTransportContext.create(mapOf("owner" to it.getAttribute("debugger.owner"))) }
            .keepAliveInterval(Duration.ofSeconds(15)).build()
        fun register(spec: McpServer.SyncSpecification<*>): McpSyncServer =
            spec.jsonSchemaValidator(DefaultJsonSchemaValidator(JsonMapper.builder().build())).serverInfo("jtools-debugger", "0.2.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(McpSchema.Tool.builder().name(DebuggerCommands.TOOL).description(DebuggerCommands.DESCRIPTION)
                    .inputSchema(mapper, DebuggerCommands.SCHEMA).build()) { exchange, request ->
                    val owner = requireNotNull(exchange.transportContext().get("owner") as? String) { "MCP 调用缺少访问身份" }
                    try {
                        val input = JsonParser.parseString(json.toJson(request.arguments())).asJsonObject
                        val result = commands.call(owner, input.text("command"), input.getAsJsonObject("arguments") ?: com.google.gson.JsonObject())
                        McpSchema.CallToolResult.builder().content(contents(result)).isError(false).build()
                    } catch (error: Exception) {
                        McpSchema.CallToolResult.builder().content(listOf(McpSchema.TextContent(json.toJson(mapOf("error" to error.toString(), "stack" to error.stackTraceToString()))))).isError(true).build()
                    }
                }.build()
        try {
            protocols.add(register(McpServer.sync(stream))); protocols.add(register(McpServer.sync(sse)))
            context.addServlet(ServletHolder(stream).apply { isAsyncSupported = true }, "/mcp")
            context.addServlet(ServletHolder(sse).apply { isAsyncSupported = true }, "/sse")
            context.addServlet(ServletHolder(sse).apply { isAsyncSupported = true }, "/messages")
            http.start()
            server = http; port = connector.localPort
            val token = activeToken ?: createClient()
            activeToken = token
            configuredPort = port
            enabled = true
            persist()
            error = null
            publish()
            return token
        } catch (error: Throwable) {
            try { protocols.forEach { it.closeGracefully() } } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            protocols.clear()
            try { http.stop() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            server = null
            port = 0
            this.error = "MCP 启动失败：${error}"
            publish()
            throw error
        }
    }

    @Synchronized
    fun newClient(): String {
        check(server != null) { "先启动 MCP 服务" }
        val token = createClient()
        val previous = activeToken
        activeToken = token
        try { persist() }
        catch (failure: Exception) {
            clients.remove(token)
            activeToken = previous
            throw failure
        }
        publish()
        return token
    }

    private fun createClient(): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        clients[token] = java.util.UUID.randomUUID().toString()
        return token
    }

    private fun contents(result: Any?): List<McpSchema.Content> {
        val data = json.toJsonTree(result)
        val content = mutableListOf<McpSchema.Content>(McpSchema.TextContent(json.toJson(result)))
        val image = data.takeIf { it.isJsonObject }?.asJsonObject?.get("image_path")?.asString
        if (image != null) {
            val path = Path.of(image)
            require(Files.size(path) <= 16 * 1024 * 1024) { "截图超过 MCP 16 MiB 限制" }
            content.add(McpSchema.ImageContent(null, Base64.getEncoder().encodeToString(Files.readAllBytes(path)), "image/png", null))
        }
        return content
    }

    @Synchronized
    fun stop() {
        val previous = enabled
        enabled = false
        try { persist() }
        catch (failure: Exception) { enabled = previous; throw failure }
        error = null
        close()
    }

    /** IDE shutdown releases resources without changing the saved auto-start preference. */
    @Synchronized
    override fun close() {
        if (server == null) {
            publish()
            return
        }
        protocols.forEach { it.closeGracefully() }; protocols.clear()
        server?.stop(); server = null; port = 0
        publish()
        // Credentials remain valid across service restart, so clients can recover their sandbox scope.
    }
}
