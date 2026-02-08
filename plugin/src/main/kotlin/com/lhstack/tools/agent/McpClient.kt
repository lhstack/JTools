package com.lhstack.tools.agent

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import io.modelcontextprotocol.client.McpClient as SdkMcpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class McpToolDescriptorState(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class McpResourceDescriptorState(
    val uri: String,
    val name: String?,
    val description: String?,
    val mimeType: String?,
)

data class McpPromptArgumentState(
    val name: String,
    val description: String?,
    val required: Boolean,
)

data class McpPromptDescriptorState(
    val name: String,
    val description: String?,
    val arguments: List<McpPromptArgumentState>,
)

class McpClientException(message: String) : RuntimeException(message)

object McpClientManager {
    private val logger = Logger.getInstance(McpClientManager::class.java)
    private val clients = ConcurrentHashMap<String, McpClient>()

    fun getClient(state: McpServerState): McpClient {
        McpSupport.ensureServerId(state)
        return clients.compute(state.id) { _, existing ->
            if (existing == null || existing.isStale(state)) {
                existing?.close()
                McpClient(state)
            } else {
                existing
            }
        }!!
    }

    fun invalidate(serverId: String) {
        clients.remove(serverId)?.close()
    }

    fun syncStates(states: List<McpServerState>) {
        val ids = states.mapNotNull { it.id.takeIf { value -> value.isNotBlank() } }.toSet()
        clients.keys.filter { !ids.contains(it) }.forEach { id ->
            clients.remove(id)?.close()
        }
    }

    fun safeListTools(state: McpServerState): List<McpToolDescriptorState> {
        return try {
            getClient(state).listTools()
        } catch (e: Throwable) {
            logger.warn("Failed to list MCP tools for ${state.name}", e)
            emptyList()
        }
    }

    fun safeListResources(state: McpServerState): List<McpResourceDescriptorState> {
        return try {
            getClient(state).listResources()
        } catch (e: Throwable) {
            logger.warn("Failed to list MCP resources for ${state.name}", e)
            emptyList()
        }
    }

    fun safeListPrompts(state: McpServerState): List<McpPromptDescriptorState> {
        return try {
            getClient(state).listPrompts()
        } catch (e: Throwable) {
            logger.warn("Failed to list MCP prompts for ${state.name}", e)
            emptyList()
        }
    }
}

class McpClient(state: McpServerState) {
    private val logger = Logger.getInstance(McpClient::class.java)
    private val cacheTtlMs = 30_000L
    private val timeoutMs = 120_000L
    private val snapshot = copyState(state)
    private val state: McpServerState = state
    private val objectMapper = ObjectMapper()
    private val jsonMapper = JacksonMcpJsonMapper(objectMapper)
    private var transport: McpClientTransport? = null
    private var client: McpSyncClient? = null
    private var toolsCache: CacheEntry<List<McpToolDescriptorState>>? = null
    private var resourcesCache: CacheEntry<List<McpResourceDescriptorState>>? = null
    private var promptsCache: CacheEntry<List<McpPromptDescriptorState>>? = null

    fun isStale(other: McpServerState): Boolean {
        return snapshot.id != other.id || snapshot.transport != other.transport ||
            snapshot.url != other.url || snapshot.stdioCommand != other.stdioCommand ||
            snapshot.stdioArgs != other.stdioArgs || snapshot.stdioEnv != other.stdioEnv ||
            snapshot.headers != other.headers || snapshot.authType != other.authType ||
            snapshot.authHeaderName != other.authHeaderName || snapshot.authHeaderValue != other.authHeaderValue ||
            snapshot.authUsername != other.authUsername || snapshot.authPassword != other.authPassword ||
            snapshot.authQueryParam != other.authQueryParam || snapshot.authQueryValue != other.authQueryValue
    }

    fun close() {
        client?.close()
        client = null
        transport = null
    }

    fun listTools(force: Boolean = false): List<McpToolDescriptorState> {
        val cached = toolsCache
        if (!force && cached != null && !cached.isExpired(cacheTtlMs)) {
            return cached.value
        }
        val result = ensureClient().listTools()
        val tools = result.tools()?.mapNotNull { tool ->
            val name = tool.name()?.trim().orEmpty()
            if (name.isBlank()) {
                return@mapNotNull null
            }
            val description = tool.description()?.trim().orEmpty()
            val inputSchema = toJsonObject(tool.inputSchema())
            McpToolDescriptorState(name, description, inputSchema)
        }.orEmpty()
        toolsCache = CacheEntry(tools)
        return tools
    }

    fun listResources(force: Boolean = false): List<McpResourceDescriptorState> {
        val cached = resourcesCache
        if (!force && cached != null && !cached.isExpired(cacheTtlMs)) {
            return cached.value
        }
        val result = ensureClient().listResources()
        val resources = result.resources()?.mapNotNull { resource ->
            val uri = resource.uri()?.trim().orEmpty()
            if (uri.isBlank()) {
                return@mapNotNull null
            }
            McpResourceDescriptorState(
                uri = uri,
                name = resource.name(),
                description = resource.description(),
                mimeType = resource.mimeType()
            )
        }.orEmpty()
        resourcesCache = CacheEntry(resources)
        return resources
    }

    fun listPrompts(force: Boolean = false): List<McpPromptDescriptorState> {
        val cached = promptsCache
        if (!force && cached != null && !cached.isExpired(cacheTtlMs)) {
            return cached.value
        }
        val result = ensureClient().listPrompts()
        val prompts = result.prompts()?.mapNotNull { prompt ->
            val name = prompt.name()?.trim().orEmpty()
            if (name.isBlank()) {
                return@mapNotNull null
            }
            val args = prompt.arguments()?.map { arg ->
                McpPromptArgumentState(
                    name = arg.name(),
                    description = arg.description(),
                    required = arg.required() == true
                )
            }.orEmpty()
            McpPromptDescriptorState(name, prompt.description(), args)
        }.orEmpty()
        promptsCache = CacheEntry(prompts)
        return prompts
    }

    fun getCachedTools(): List<McpToolDescriptorState>? = toolsCache?.value

    fun getCachedResources(): List<McpResourceDescriptorState>? = resourcesCache?.value

    fun getCachedPrompts(): List<McpPromptDescriptorState>? = promptsCache?.value

    fun readResource(uri: String): JsonObject {
        val result = ensureClient().readResource(McpSchema.ReadResourceRequest(uri))
        return toJsonObject(result)
    }

    fun requestTool(name: String, arguments: JsonObject): JsonObject {
        val argsMap = toArgsMap(arguments)
        val result = ensureClient().callTool(McpSchema.CallToolRequest(name, argsMap))
        return toJsonObject(result)
    }

    fun getPrompt(name: String, arguments: JsonObject?): JsonObject {
        val argsMap = toArgsMap(arguments)
        val result = ensureClient().getPrompt(McpSchema.GetPromptRequest(name, argsMap))
        return toJsonObject(result)
    }

    fun ping() {
        ensureClient().ping()
    }

    private fun ensureClient(): McpSyncClient {
        client?.let { return it }
        val newTransport = createTransport()
        transport = newTransport
        val syncClient = SdkMcpClient.sync(newTransport)
            .requestTimeout(Duration.ofMillis(timeoutMs))
            .initializationTimeout(Duration.ofMillis(timeoutMs))
            .jsonSchemaValidator(DefaultJsonSchemaValidator(objectMapper))
            .build()
        client = syncClient
        return syncClient
    }

    private fun createTransport(): McpClientTransport {
        return when (McpTransportType.fromId(state.transport)) {
            McpTransportType.STDIO -> createStdioTransport()
            McpTransportType.SSE -> createSseTransport()
            McpTransportType.STREAMABLE_HTTP -> createStreamableTransport()
        }
    }

    private fun createStdioTransport(): McpClientTransport {
        val command = state.stdioCommand.trim()
        val params = ServerParameters.builder(command)
            .args(state.stdioArgs)
            .env(state.stdioEnv)
            .build()
        return StdioClientTransport(params, jsonMapper)
    }

    private fun createSseTransport(): McpClientTransport {
        val endpoint = splitEndpoint(state.url, "/sse")
        return HttpClientSseClientTransport.builder(endpoint.baseUri)
            .sseEndpoint(endpoint.endpoint)
            .jsonMapper(jsonMapper)
            .httpRequestCustomizer(buildRequestCustomizer())
            .build()
    }

    private fun createStreamableTransport(): McpClientTransport {
        val endpoint = splitEndpoint(state.url, "/mcp")
        return HttpClientStreamableHttpTransport.builder(endpoint.baseUri)
            .endpoint(endpoint.endpoint)
            .jsonMapper(jsonMapper)
            .httpRequestCustomizer(buildRequestCustomizer())
            .build()
    }

    private fun buildRequestCustomizer(): McpSyncHttpClientRequestCustomizer {
        return McpSyncHttpClientRequestCustomizer { builder, _, endpoint, body, _ ->
            val headers = mutableMapOf<String, String>()
            headers.putAll(state.headers)
            when (McpAuthType.fromId(state.authType)) {
                McpAuthType.HEADER -> {
                    if (state.authHeaderName.isNotBlank() && state.authHeaderValue.isNotBlank()) {
                        headers[state.authHeaderName] = state.authHeaderValue
                    }
                }
                McpAuthType.BASIC -> {
                    val token = Base64.getEncoder().encodeToString(
                        "${state.authUsername}:${state.authPassword}".toByteArray(StandardCharsets.UTF_8)
                    )
                    headers["Authorization"] = "Basic $token"
                }
                else -> Unit
            }
            if (!hasHeader(headers, "Accept")) {
                headers["Accept"] = "application/json, text/event-stream"
            }
            if (!body.isNullOrBlank() && !hasHeader(headers, "Content-Type")) {
                headers["Content-Type"] = "application/json"
            }
            headers.forEach { (key, value) ->
                if (key.isNotBlank()) {
                    builder.setHeader(key, value)
                }
            }
            builder.uri(appendQuery(endpoint))
        }
    }

    private fun appendQuery(endpoint: URI): URI {
        val authType = McpAuthType.fromId(state.authType)
        if (authType != McpAuthType.QUERY || state.authQueryParam.isBlank()) {
            return endpoint
        }
        val key = URLEncoder.encode(state.authQueryParam, StandardCharsets.UTF_8)
        val value = URLEncoder.encode(state.authQueryValue, StandardCharsets.UTF_8)
        val param = "$key=$value"
        val newQuery = if (endpoint.rawQuery.isNullOrBlank()) {
            param
        } else {
            endpoint.rawQuery + "&" + param
        }
        return URI(endpoint.scheme, endpoint.authority, endpoint.path, newQuery, endpoint.fragment)
    }

    private fun hasHeader(headers: Map<String, String>, name: String): Boolean {
        return headers.keys.any { it.equals(name, ignoreCase = true) }
    }

    private fun splitEndpoint(rawUrl: String, defaultEndpoint: String): HttpEndpoint {
        val uri = URI(rawUrl)
        val baseUri = URI(uri.scheme, uri.authority, null, null, null).toString()
        var path = uri.rawPath ?: ""
        if (path.isBlank() || path == "/") {
            path = defaultEndpoint
        }
        val endpoint = if (uri.rawQuery.isNullOrBlank()) {
            path
        } else {
            "$path?${uri.rawQuery}"
        }
        return HttpEndpoint(baseUri, endpoint)
    }

    private fun toArgsMap(arguments: JsonObject?): Map<String, Any> {
        if (arguments == null || arguments.size() == 0) {
            return emptyMap()
        }
        return try {
            objectMapper.readValue(arguments.toString(), object : TypeReference<Map<String, Any>>() {})
        } catch (e: Exception) {
            logger.warn("Failed to parse MCP arguments", e)
            emptyMap()
        }
    }

    private fun toJsonObject(value: Any?): JsonObject {
        if (value == null) {
            return JsonObject()
        }
        return try {
            val json = objectMapper.writeValueAsString(value)
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            logger.warn("Failed to serialize MCP response", e)
            JsonObject()
        }
    }

    private fun copyState(source: McpServerState): McpServerState {
        return McpServerState().apply {
            id = source.id
            name = source.name
            enabled = source.enabled
            transport = source.transport
            stdioCommand = source.stdioCommand
            stdioArgs = source.stdioArgs.toMutableList()
            stdioEnv = source.stdioEnv.toMutableMap()
            url = source.url
            headers = source.headers.toMutableMap()
            authType = source.authType
            authHeaderName = source.authHeaderName
            authHeaderValue = source.authHeaderValue
            authUsername = source.authUsername
            authPassword = source.authPassword
            authQueryParam = source.authQueryParam
            authQueryValue = source.authQueryValue
            disabledTools = source.disabledTools.toMutableList()
        }
    }

    private data class CacheEntry<T>(val value: T, val createdAt: Long = System.currentTimeMillis()) {
        fun isExpired(ttl: Long): Boolean = System.currentTimeMillis() - createdAt > ttl
    }

    private data class HttpEndpoint(val baseUri: String, val endpoint: String)
}
