package com.lhstack.tools.agent

import com.google.gson.*
import com.jetbrains.rd.framework.base.deepClonePolymorphic
import io.agentscope.core.ReActAgent
import io.agentscope.core.agent.EventType
import io.agentscope.core.agent.StreamOptions
import io.agentscope.core.message.*
import io.agentscope.core.session.InMemorySession
import io.agentscope.core.tool.Toolkit
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ToolCallLog(
    val name: String,
    val arguments: String,
    val result: String,
)

data class ToolCallStreamEvent(
    val id: String,
    val index: Int,
    val name: String,
    val arguments: String,
    val done: Boolean,
)

data class AgentTextStreamEvent(
    val id: String,
    val text: String,
)

data class ToolResultStreamEvent(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String,
)

data class AgentCompletionResult(
    val assistantContent: String?,
    val toolCalls: List<ToolCallLog>,
    val reasoningContent: String? = null,
    val errorMessage: String? = null,
)

data class ModelListResult(
    val models: List<String>,
    val errorMessage: String? = null,
)

class AgentClient {
    private val runtimeFactory = AgentScopeRuntime()
    private val runtimeCache = ConcurrentHashMap<String, RuntimeHandle>()

    private data class ToolCallStreamState(
        var name: String = "",
        var arguments: String = "",
        var hasFragments: Boolean = false,
    )

    class CancelToken {
        private val cancelled = AtomicBoolean(false)
        private val interruptAction = AtomicReference<(() -> Unit)?>(null)

        fun cancel() {
            cancelled.set(true)
            interruptAction.getAndSet(null)?.invoke()
        }

        fun isCancelled(): Boolean = cancelled.get()

        fun registerInterrupt(action: () -> Unit) {
            interruptAction.getAndSet(action)
            if (cancelled.get()) {
                interruptAction.getAndSet(null)?.invoke()
            }
        }
    }

    fun clearSession(sessionId: String) {
        if (sessionId.isBlank()) {
            return
        }
        runtimeCache.remove(sessionId)
    }

    private data class RuntimeHandle(
        val signature: String,
        val runtimeSpec: AgentScopeRuntimeSpec,
        val agent: ReActAgent,
        val session: InMemorySession,
        var primed: Boolean,
        val provider: AgentProviderState
    )

    fun complete(
        sessionState: AgentSessionState,
        messages: MutableList<JsonObject>,
        toolRegistry: AgentToolRegistry,
        provider: AgentProviderState,
        model: String,
        resolvedSkills: AgentResolvedSkills = AgentResolvedSkills(emptyList(), null),
        onAssistantDelta: ((AgentTextStreamEvent) -> Unit)? = null,
        onReasoningDelta: ((AgentTextStreamEvent) -> Unit)? = null,
        onToolCall: ((ToolCallStreamEvent) -> Unit)? = null,
        onToolResult: ((ToolResultStreamEvent) -> Unit)? = null,
        maxToolIterations: Int = 5,
        cancelToken: CancelToken? = null,
    ): AgentCompletionResult {
        val assistantToolMessagesById = linkedMapOf<String, JsonObject>()
        val toolCallsById = linkedMapOf<String, Pair<String, String>>()
        val toolLogsById = linkedMapOf<String, ToolCallLog>()
        val toolCallStatesById = mutableMapOf<String, ToolCallStreamState>()
        val reasoningTextsById = mutableMapOf<String, String>()
        val assistantTextsById = mutableMapOf<String, String>()
        val toolResultTextsById = mutableMapOf<String, String>()
        val handle = getOrCreateRuntime(
            sessionState = sessionState,
            provider = provider,
            model = model,
            resolvedSkills = resolvedSkills,
            toolRegistry = toolRegistry,
            maxToolIterations = maxToolIterations,
        )

        try {
            AgentToolCallHistorySupport.sanitizeInPlace(messages)
            primeHistoryIfNeeded(handle, messages)
            val currentMsg = jsonToMsg(messages.lastOrNull()) ?: return AgentCompletionResult(
                assistantContent = null,
                toolCalls = emptyList(),
                reasoningContent = null,
                errorMessage = "当前消息为空"
            )
            cancelToken?.registerInterrupt {
                handle.agent.interrupt()
            }
            var finalAssistant: Msg? = null
            val options = StreamOptions.builder()
                .eventTypes(
                    EventType.REASONING,
                    EventType.TOOL_RESULT,
                    EventType.AGENT_RESULT,
                )
                .incremental(false)
                .includeReasoningChunk(true)
                .includeReasoningResult(false)
                .includeActingChunk(true)
                .includeSummaryChunk(true)
                .includeSummaryResult(false)
                .build()
            handle.agent.stream(listOf(currentMsg), options)
                .doOnNext { event ->
                    val message = event.message
                    for (block in message.content) {
                        if (block is ThinkingBlock) {
                            AgentReasoningSupport.extractThinking(block)?.let { text ->
                                emitTextDelta(
                                    eventKey = "reasoning",
                                    currentText = text,
                                    snapshots = reasoningTextsById,
                                    consumer = onReasoningDelta,
                                )
                            }
                        }
                        if (block is ToolUseBlock) {
                            val state = toolCallStatesById.getOrPut(block.id) { ToolCallStreamState() }
                            val rawName = block.name.trim()
                            if (rawName.isNotBlank() && rawName != TOOL_CALL_FRAGMENT_NAME) {
                                state.name = rawName
                            }
                            val resolvedName = state.name.ifBlank {
                                rawName.takeIf { it.isNotBlank() && it != TOOL_CALL_FRAGMENT_NAME }.orEmpty()
                            }
                            val arguments = toolUseArguments(block)
                            when {
                                rawName == TOOL_CALL_FRAGMENT_NAME -> {
                                    state.hasFragments = true
                                    val previous = state.arguments
                                    state.arguments = arguments
                                    toolCallsById[block.id] = resolvedName.ifBlank { rawName } to state.arguments
                                    assistantToolMessagesById[block.id] =
                                        AgentToolCallHistorySupport.assistantToolCallMessage(
                                            toolCallId = block.id,
                                            name = resolvedName.ifBlank { rawName },
                                            arguments = state.arguments,
                                        )
                                    val delta = AgentStreamTextSupport.delta(
                                        previous = previous,
                                        current = state.arguments,
                                    )
                                    if (delta.isNotBlank()) {
                                        onToolCall?.invoke(
                                            ToolCallStreamEvent(
                                                id = block.id,
                                                index = toolCallsById.keys.indexOf(block.id),
                                                name = resolvedName.ifBlank { rawName },
                                                arguments = delta,
                                                done = true,
                                            )
                                        )
                                    }
                                }

                                arguments.isBlank() -> {
                                    if (resolvedName.isNotBlank() && block.id !in toolCallsById) {
                                        toolCallsById[block.id] = resolvedName to ""
                                    }
                                }

                                state.hasFragments -> {
                                    state.arguments = arguments
                                    toolCallsById[block.id] = resolvedName.ifBlank { rawName } to arguments
                                    assistantToolMessagesById[block.id] =
                                        AgentToolCallHistorySupport.assistantToolCallMessage(
                                            toolCallId = block.id,
                                            name = resolvedName.ifBlank { rawName },
                                            arguments = arguments,
                                        )
                                }

                                else -> {
                                    val previous = state.arguments
                                    state.arguments = arguments
                                    toolCallsById[block.id] = resolvedName.ifBlank { rawName } to arguments
                                    assistantToolMessagesById[block.id] =
                                        AgentToolCallHistorySupport.assistantToolCallMessage(
                                            toolCallId = block.id,
                                            name = resolvedName.ifBlank { rawName },
                                            arguments = arguments,
                                        )
                                    val delta = AgentStreamTextSupport.delta(
                                        previous = previous,
                                        current = arguments,
                                    )
                                    if (delta.isNotBlank()) {
                                        onToolCall?.invoke(
                                            ToolCallStreamEvent(
                                                id = block.id,
                                                index = toolCallsById.keys.indexOf(block.id),
                                                name = resolvedName.ifBlank { rawName },
                                                arguments = delta,
                                                done = true,
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        if (block is TextBlock) {
                            finalAssistant = message
                            emitTextDelta(
                                eventKey = "assistant",
                                currentText = block.text,
                                snapshots = assistantTextsById,
                                consumer = onAssistantDelta,
                            )
                        }

                        if (block is ToolResultBlock) {
                            val call = toolCallsById[block.id]
                            val resultText = toolResultText(block)
                            toolLogsById[block.id] = ToolCallLog(
                                name = call?.first ?: block.name,
                                arguments = call?.second ?: "",
                                result = resultText,
                            )
                            val delta = AgentStreamTextSupport.delta(
                                previous = toolResultTextsById[block.id].orEmpty(),
                                current = resultText,
                            )
                            toolResultTextsById[block.id] = resultText
                            if (delta.isNotBlank()) {
                                onToolResult?.invoke(
                                    ToolResultStreamEvent(
                                        id = block.id,
                                        name = call?.first ?: block.name,
                                        arguments = call?.second ?: "",
                                        result = delta,
                                    )
                                )
                            }
                        }
                    }
                }
                .blockLast()
            if (cancelToken?.isCancelled() == true) {
                clearSession(sessionState.id)
                return AgentCompletionResult(
                    assistantContent = null,
                    toolCalls = toolLogsById.values.toList(),
                    reasoningContent = reasoningTextsById.values.joinToString("\n\n").ifBlank { null },
                    errorMessage = "已取消",
                )
            }
            handle.agent.saveTo(handle.session, handle.runtimeSpec.features.sessionKey)

            assistantToolMessagesById.values.forEach { messages.add(it) }
            toolLogsById.forEach { (toolUseId, log) ->
                messages.add(
                    AgentToolCallHistorySupport.toolResultMessage(
                        toolCallId = toolUseId,
                        result = log.result,
                    )
                )
            }

            val finalContent = finalAssistant?.getTextContent().orEmpty().ifBlank { null }
            if (finalAssistant != null) {
                messages.add(msgToAssistantJson(finalAssistant!!))
            }
            return AgentCompletionResult(
                assistantContent = finalContent,
                toolCalls = toolLogsById.values.toList(),
                reasoningContent = reasoningTextsById.values.joinToString("\n\n").ifBlank { null },
            )
        } catch (e: Throwable) {
            clearSession(sessionState.id)
            val errorMessage = when {
                cancelToken?.isCancelled() == true -> "已取消"
                e is CancellationException -> "已取消"
                else -> e.message ?: "调用失败"
            }
            return AgentCompletionResult(
                assistantContent = null,
                toolCalls = toolLogsById.values.toList(),
                reasoningContent = reasoningTextsById.values.joinToString("\n\n").ifBlank { null },
                errorMessage = errorMessage,
            )
        }
    }

    private fun emitTextDelta(
        eventKey: String,
        currentText: String,
        snapshots: MutableMap<String, String>,
        consumer: ((AgentTextStreamEvent) -> Unit)?,
    ) {
        val delta = AgentStreamTextSupport.delta(
            previous = snapshots[eventKey].orEmpty(),
            current = currentText,
        )
        snapshots[eventKey] = currentText
        consumer?.invoke(AgentTextStreamEvent(eventKey, delta))
    }

    fun listModels(provider: AgentProviderState): ModelListResult {
        return try {
            val models = when (provider.providerType) {
                AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
                AgentProviderCatalog.TYPE_DASHSCOPE -> fetchOpenAICompatibleModels(provider)

                AgentProviderCatalog.TYPE_ANTHROPIC -> fetchAnthropicModels(provider)
                AgentProviderCatalog.TYPE_OLLAMA -> fetchOllamaModels(provider)
                else -> provider.models.filter { it.isNotBlank() }
            }.distinct()
            if (models.isEmpty()) {
                ModelListResult(provider.models.filter { it.isNotBlank() }, "未获取到可用模型")
            } else {
                ModelListResult(models)
            }
        } catch (e: Throwable) {
            ModelListResult(provider.models.filter { it.isNotBlank() }, e.message ?: "模型列表获取失败")
        }
    }

    private fun getOrCreateRuntime(
        sessionState: AgentSessionState,
        provider: AgentProviderState,
        model: String,
        resolvedSkills: AgentResolvedSkills,
        toolRegistry: AgentToolRegistry,
        maxToolIterations: Int,
    ): RuntimeHandle {
        val key = sessionState.id.ifBlank {
            sessionState.id = UUID.randomUUID().toString()
            sessionState.id
        }
        val signature = buildSignature(provider, model, toolRegistry, resolvedSkills.selectedSkills)
        val existing = runtimeCache[key]
        if (existing != null && existing.signature == signature) {
            return existing
        }

        val toolkit = Toolkit().apply {
            AgentScopeToolAdapter.wrapAll(toolRegistry).forEach { registerAgentTool(it) }
        }
        val runtimeSpec = runtimeFactory.create(
            provider = provider,
            session = sessionState,
            config = AgentScopeRuntimeConfig(
                maxIterations = maxToolIterations,
                toolkit = toolkit,
                skillBox = resolvedSkills.skillBox,
                hooks = emptyList(),
            )
        )
        val agent = runtimeSpec.builder.build()
        val session = InMemorySession()
        val handle = RuntimeHandle(signature, runtimeSpec, agent, session, primed = false, provider.deepClonePolymorphic())
        runtimeCache[key] = handle
        return handle
    }

    private fun primeHistoryIfNeeded(handle: RuntimeHandle, messages: List<JsonObject>) {
        if (handle.primed) {
            return
        }
        if (handle.session.exists(handle.runtimeSpec.features.sessionKey)) {
            handle.agent.loadFrom(handle.session, handle.runtimeSpec.features.sessionKey)
            handle.primed = true
            return
        }
        val history = messages.dropLast(1).mapNotNull(::jsonToMsg)
        if (history.isNotEmpty()) {
            handle.agent.observe(history).block()
        }
        handle.agent.saveTo(handle.session, handle.runtimeSpec.features.sessionKey)
        handle.primed = true
    }

    private fun buildSignature(
        provider: AgentProviderState,
        model: String,
        toolRegistry: AgentToolRegistry,
        skills: List<AgentSkillState>,
    ): String {
        val toolSignature = toolRegistry.tools.joinToString("|") { "${it.name}:${it.parametersJson.hashCode()}" }
        val skillSignature = skills.joinToString("|") {
            listOf(
                it.id,
                it.name,
                it.description,
                it.skillContent.hashCode(),
                it.resources.joinToString(",") { resource -> "${resource.path}:${resource.content.hashCode()}" }
            ).joinToString(":")
        }
        return listOf(
            provider.id,
            provider.providerType,
            provider.vendorTemplate,
            provider.baseUrl,
            provider.endpointPath,
            provider.apiKey,
            model,
            toolSignature,
            skillSignature,
        ).joinToString("||")
    }

    private fun msgToAssistantJson(message: Msg): JsonObject {
        val toolCalls = JsonArray()
        message.getContentBlocks(ToolUseBlock::class.java).forEach { call ->
            toolCalls.add(
                JsonObject().apply {
                    addProperty("id", call.id)
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", call.name)
                        addProperty("arguments", gsonToJson(call.input))
                    })
                }
            )
        }
        return JsonObject().apply {
            addProperty("role", "assistant")
            message.getTextContent().takeIf { it.isNotBlank() }?.let { addProperty("content", it) }
            if (toolCalls.size() > 0) {
                add("tool_calls", toolCalls)
            }
        }
    }

    private fun jsonToMsg(message: JsonObject?): Msg? {
        if (message == null) {
            return null
        }
        val attachmentDrafts = message.getAsJsonArray("attachments")?.mapNotNull { element ->
            runCatching {
                element.asJsonObject.let { json ->
                    AgentAttachmentState(
                        id = json.get("id")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        name = json.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        path = json.get("path")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        mimeType = json.get("mimeType")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        size = json.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0,
                        kind = json.get("kind")?.takeIf { !it.isJsonNull }?.asString ?: AgentAttachmentKind.FILE.id,
                        deliveryMode = json.get("deliveryMode")?.takeIf { !it.isJsonNull }?.asString
                            ?: AgentAttachmentDeliveryMode.AUTO.id,
                    )
                }
            }.getOrNull()
        }.orEmpty()
        val roleValue = message.get("role")?.asString ?: return null
        val role = when (roleValue) {
            "system" -> MsgRole.SYSTEM
            "assistant" -> MsgRole.ASSISTANT
            "tool" -> MsgRole.TOOL
            else -> MsgRole.USER
        }
        if (role == MsgRole.USER && attachmentDrafts.isNotEmpty()) {
            val text = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            return AgentConversationMapper.mapUserMessage(text, attachmentDrafts).message
        }
        val contentBlocks = mutableListOf<io.agentscope.core.message.ContentBlock>()
        message.get("content")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let {
            contentBlocks.add(TextBlock.builder().text(it).build())
        }
        if (role == MsgRole.ASSISTANT) {
            message.getAsJsonArray("tool_calls")?.forEach { element ->
                val call = element.asJsonObject
                val id = call.get("id")?.takeIf { !it.isJsonNull }?.asString ?: UUID.randomUUID().toString()
                val function = call.getAsJsonObject("function") ?: JsonObject()
                val name = function.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val arguments = function.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                contentBlocks.add(
                    ToolUseBlock.builder()
                        .id(id)
                        .name(name)
                        .input(parseArgs(arguments))
                        .content(arguments)
                        .build()
                )
            }
        }
        if (role == MsgRole.TOOL) {
            val toolId =
                message.get("tool_call_id")?.takeIf { !it.isJsonNull }?.asString ?: UUID.randomUUID().toString()
            val text = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            contentBlocks.clear()
            contentBlocks.add(
                ToolResultBlock.of(
                    toolId,
                    message.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "tool",
                    TextBlock.builder().text(text).build(),
                )
            )
        }
        if (contentBlocks.isEmpty()) {
            return null
        }
        return Msg.builder()
            .role(role)
            .content(contentBlocks)
            .build()
    }

    private fun toolResultText(block: ToolResultBlock): String {
        return block.output.joinToString("\n") { output ->
            when (output) {
                is TextBlock -> output.text
                else -> output.toString()
            }
        }
    }

    private fun toolUseArguments(block: ToolUseBlock): String {
        return Gson().toJson(block.input)
    }

    companion object {
        private const val TOOL_CALL_FRAGMENT_NAME = "__fragment__"
    }

    private fun fetchOpenAICompatibleModels(provider: AgentProviderState): List<String> {
        val baseUrl = provider.baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        val response = executeJsonRequest(
            provider = provider,
            uri = URI.create("$baseUrl/models"),
            extraHeaders = emptyMap(),
        )
        return response?.getAsJsonArray("data")
            ?.mapNotNull { it.asJsonObject.get("id")?.takeIf { value -> !value.isJsonNull }?.asString }
            .orEmpty()
    }

    private fun fetchAnthropicModels(provider: AgentProviderState): List<String> {
        val baseUrl = provider.baseUrl.trim().trimEnd('/').removeSuffix("/v1/messages")
        val response = executeJsonRequest(
            provider = provider,
            uri = URI.create("$baseUrl/v1/models"),
            extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
        )
        return response?.getAsJsonArray("data")
            ?.mapNotNull { it.asJsonObject.get("id")?.takeIf { value -> !value.isJsonNull }?.asString }
            .orEmpty()
    }

    private fun fetchOllamaModels(provider: AgentProviderState): List<String> {
        val baseUrl = provider.baseUrl.trim().trimEnd('/')
        val response = executeJsonRequest(
            provider = provider,
            uri = URI.create("$baseUrl/api/tags"),
            extraHeaders = emptyMap(),
            includeAuth = false,
        )
        return response?.getAsJsonArray("models")
            ?.mapNotNull { it.asJsonObject.get("name")?.takeIf { value -> !value.isJsonNull }?.asString }
            .orEmpty()
    }

    private fun executeJsonRequest(
        provider: AgentProviderState,
        uri: URI,
        extraHeaders: Map<String, String>,
        includeAuth: Boolean = true,
    ): JsonObject? {
        val clientBuilder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        buildProxySelector(provider)?.let { clientBuilder.proxy(it) }
        val requestBuilder = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
        if (includeAuth && provider.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${provider.apiKey.trim()}")
            requestBuilder.header("x-api-key", provider.apiKey.trim())
        }
        AgentProviderSupport.parseCustomHeaders(provider.customHeaders).headers.forEach { (name, values) ->
            values.forEach { requestBuilder.header(name, it) }
        }
        extraHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
        val response = clientBuilder.build().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            return null
        }
        val body = response.body().trim()
        if (body.isBlank()) {
            return null
        }
        val element = JsonParser.parseString(body)
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun buildProxySelector(provider: AgentProviderState): ProxySelector? {
        if (!provider.proxyEnabled) {
            return null
        }
        val host = provider.proxyHost.trim()
        val port = provider.proxyPort
        if (host.isBlank() || port !in 1..65535) {
            return null
        }
        return ProxySelector.of(InetSocketAddress(host, port))
    }

    private fun parseArgs(raw: String): Map<String, Any> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return emptyMap()
        }
        val element = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
        if (element == null || !element.isJsonObject) {
            return emptyMap()
        }
        return gsonToAny(element.asJsonObject) as? Map<String, Any> ?: emptyMap()
    }

    private fun gsonToJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> JsonParser.parseString("\"${value.replace("\"", "\\\"")}\"").toString()
            else -> JsonObject().apply {
                add("value", toJsonElement(value))
            }.get("value").toString()
        }
    }

    private fun toJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> com.google.gson.JsonNull.INSTANCE
            is Number -> com.google.gson.JsonPrimitive(value)
            is Boolean -> com.google.gson.JsonPrimitive(value)
            is String -> com.google.gson.JsonPrimitive(value)
            is Map<*, *> -> JsonObject().apply {
                value.forEach { (key, item) ->
                    if (key != null) {
                        add(key.toString(), toJsonElement(item))
                    }
                }
            }

            is Iterable<*> -> JsonArray().apply {
                value.forEach { add(toJsonElement(it)) }
            }

            else -> com.google.gson.JsonPrimitive(value.toString())
        }
    }

    private fun gsonToAny(element: JsonElement): Any? {
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> primitive.asNumber
                    primitive.isString -> primitive.asString
                    else -> primitive.asString
                }
            }

            element.isJsonArray -> element.asJsonArray.map { gsonToAny(it) }
            element.isJsonObject -> element.asJsonObject.entrySet().associate { it.key to gsonToAny(it.value) }
            else -> null
        }
    }

    fun refreshRuntimeCache(state: AgentProviderState) {
        for (entry in runtimeCache) {
            val cacheProvider = entry.value.provider
            if (cacheProvider.id == state.id) {
                runCatching { entry.value.agent.interrupt() }
                runtimeCache.remove(entry.key)
            }
        }
    }
}
