package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.db.entity.McpServerEntity
import com.lhstack.tools.db.service.McpService
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema.*
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/** MCP protocol runtime backed by the official Java MCP SDK. */
internal class McpRuntime(
    private val server: McpServerEntity,
    private val timeoutSecs: Long,
    private val cancel: ModelCancel?,
) {
    fun test(): JsonObject = withClient { client ->
        JsonObject().apply {
            addProperty("connected", true)
            add("server_info", json(client.currentInitializationResult.serverInfo()))
            add("tools", json(client.listTools().tools()))
        }
    }

    fun tools(): JsonElement = withClient { json(it.listTools().tools()) }

    fun resources(): JsonElement = withClient { json(it.listResources().resources()) }

    fun readResource(uri: String): JsonElement = withClient {
        json(it.readResource(ReadResourceRequest(uri)))
    }

    fun callTool(name: String, arguments: JsonObject): JsonElement = withClient {
        val values = MCP_JSON_MAPPER.readValue(arguments.toString(), Map::class.java)
            .entries.associate { (key, value) -> key.toString() to value }
        json(it.callTool(CallToolRequest(name, values)))
    }

    private fun <T> withClient(action: (McpSyncClient) -> T): T {
        val transport = transport()
        val timeout = Duration.ofSeconds(timeoutSecs)
        val client = McpClient.sync(transport)
            .requestTimeout(timeout)
            .initializationTimeout(timeout)
            .clientInfo(Implementation("jtools-cli", "1.0"))
            .jsonSchemaValidator(MCP_SCHEMA_VALIDATOR)
            .build()
        val interruptId = cancel?.registerInterrupt(client::close)
        try {
            client.initialize()
            return action(client)
        } finally {
            interruptId?.let { cancel?.clearInterrupt(it) }
            client.close()
        }
    }

    private fun transport(): McpClientTransport = when (server.transport) {
        "stdio" -> stdioTransport()
        "sse" -> sseTransport()
        "streamable_http" -> streamableHttpTransport()
        else -> throw IllegalArgumentException(
            "不支持的 MCP 协议 `${server.transport}`，仅支持 stdio、sse、streamable_http"
        )
    }

    private fun stdioTransport(): McpClientTransport {
        val executable = server.command?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("stdio MCP 缺少 executable")
        val parameters = ServerParameters.builder(executable)
            .args(McpService.stringList(server.args))
            .env(McpService.stringMap(server.env, "env"))
            .build()
        return StdioClientTransport(parameters, MCP_JSON_MAPPER)
    }

    private fun sseTransport(): McpClientTransport {
        val endpoint = remoteEndpoint("SSE")
        val origin = URI(endpoint)
        val baseUri = URI(origin.scheme, origin.authority, null, null, null).toString()
        val path = origin.rawPath.orEmpty().ifBlank { "/sse" } +
            origin.rawQuery?.let { "?$it" }.orEmpty()
        return HttpClientSseClientTransport.builder(baseUri)
            .sseEndpoint(path)
            .httpRequestCustomizer(::applyHeaders)
            .jsonMapper(MCP_JSON_MAPPER)
            .connectTimeout(Duration.ofSeconds(timeoutSecs))
            .build()
    }

    private fun streamableHttpTransport(): McpClientTransport {
        val endpoint = URI(remoteEndpoint("Streamable HTTP"))
        val baseUri = URI(endpoint.scheme, endpoint.authority, null, null, null).toString()
        val path = endpoint.rawPath.orEmpty().ifBlank { "/mcp" } +
            endpoint.rawQuery?.let { "?$it" }.orEmpty()
        return HttpClientStreamableHttpTransport.builder(baseUri)
            .endpoint(path)
            .httpRequestCustomizer(::applyHeaders)
            .jsonMapper(MCP_JSON_MAPPER)
            .connectTimeout(Duration.ofSeconds(timeoutSecs))
            .build()
    }

    private fun remoteEndpoint(label: String): String = server.url?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$label MCP 缺少 url")

    /**
     * Per-request customizer。SDK 在设置默认请求头（含 POST 的
     * Content-Type: application/json; charset=utf-8）之后才调用它，因此这里能覆盖头。
     * 部分 MCP 服务端严格匹配 pplication/json，会拒绝带 charset 的 content-type
     * 并返回 400，这里对 POST 显式改回不带 charset 的值；同时应用用户自定义头。
     */
    private fun applyHeaders(
        builder: HttpRequest.Builder,
        method: String,
        endpoint: URI,
        body: String?,
        context: McpTransportContext,
    ) {
        McpService.stringMap(server.headers, "headers").forEach { (name, value) -> builder.setHeader(name, value) }
        if (method.equals("POST", ignoreCase = true)) {
            builder.setHeader("Content-Type", "application/json")
        }
    }

    private fun json(value: Any?): JsonElement =
        JsonParser.parseString(MCP_JSON_MAPPER.writeValueAsString(value))

    private companion object {
        val JACKSON_JSON_MAPPER: JsonMapper = JsonMapper.builder().build()
        val MCP_JSON_MAPPER = JacksonMcpJsonMapper(JACKSON_JSON_MAPPER)
        val MCP_SCHEMA_VALIDATOR = DefaultJsonSchemaValidator(JACKSON_JSON_MAPPER)
    }
}
