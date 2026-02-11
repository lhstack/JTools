package com.lhstack.tools.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue as AnthropicJsonValue
import com.anthropic.core.jsonMapper as anthropicJsonMapper
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.Metadata
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.models.messages.ThinkingConfigEnabled
import com.anthropic.models.messages.ThinkingConfigParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlockParam
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.concurrency.AppExecutorUtil
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue as OpenAiJsonValue
import com.openai.models.ChatModel
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatJsonObject
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.ResponseFormatText
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.net.InetSocketAddress
import java.net.Proxy

data class ToolCallLog(
    val name: String,
    val arguments: String,
    val result: String,
)

data class ToolCallStreamEvent(
    val index: Int,
    val name: String,
    val arguments: String,
    val done: Boolean,
)

data class AgentCompletionResult(
    val assistantContent: String?,
    val toolCalls: List<ToolCallLog>,
    val errorMessage: String? = null,
)

data class ModelListResult(
    val models: List<String>,
    val errorMessage: String? = null,
)

class AgentClient {
    private val toolExecutor = AppExecutorUtil.getAppExecutorService()
    private val openAiClients = mutableMapOf<String, OpenAIClient>()
    private val anthropicClients = mutableMapOf<String, AnthropicClient>()
    private val clientLock = Any()
    private val anthropicMapper = anthropicJsonMapper()

    class CancelToken {
        private val cancelled = AtomicBoolean(false)
        private val futureRef = AtomicReference<CompletableFuture<*>>()

        fun cancel() {
            cancelled.set(true)
            futureRef.getAndSet(null)?.cancel(true)
        }

        fun isCancelled(): Boolean = cancelled.get()

        fun register(future: CompletableFuture<*>) {
            futureRef.getAndSet(future)?.cancel(true)
            if (cancelled.get()) {
                future.cancel(true)
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_TOOL_ITERATIONS = 5
        private const val MAX_REPEAT_TOOL_CALLS = 2
        private const val MAX_TOOL_RESULT_CHARS = 20_000
        private const val DEFAULT_TOOL_TIMEOUT_MS = 120_000L
    }

    fun complete(
        messages: MutableList<JsonObject>,
        toolRegistry: AgentToolRegistry,
        provider: AgentProviderState,
        model: String,
        onDelta: ((String) -> Unit)? = null,
        onReasoningDelta: ((String) -> Unit)? = null,
        onToolCall: ((ToolCallStreamEvent) -> Unit)? = null,
        onToolResult: ((ToolCallLog) -> Unit)? = null,
        maxToolIterations: Int = DEFAULT_MAX_TOOL_ITERATIONS,
        toolTimeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
        cancelToken: CancelToken? = null,
    ): AgentCompletionResult {
        val toolCalls = mutableListOf<ToolCallLog>()
        val providerType = AgentProviderType.fromId(provider.type)
        var iterations = 0
        var lastToolSignature: String? = null
        var repeatedToolCalls = 0
        val maxIterations = if (maxToolIterations <= 0) DEFAULT_MAX_TOOL_ITERATIONS else maxToolIterations

        while (iterations < maxIterations) {
            if (cancelToken?.isCancelled() == true) {
                return AgentCompletionResult(
                    assistantContent = null,
                    toolCalls = toolCalls,
                    errorMessage = "已取消"
                )
            }
            val response = try {
                when (providerType) {
                    AgentProviderType.OPENAI -> {
                        if (onDelta == null) {
                            requestOpenAiChatCompletion(
                                messages,
                                toolRegistry.toolsJson(),
                                provider,
                                model,
                                cancelToken
                            )
                        } else {
                            requestOpenAiChatCompletionStream(
                                messages,
                                toolRegistry.toolsJson(),
                                provider,
                                model,
                                onDelta,
                                onReasoningDelta,
                                onToolCall,
                                cancelToken
                            )
                        }
                    }
                    AgentProviderType.ANTHROPIC -> {
                        val maxTokens = provider.maxTokens
                        if (onDelta == null) {
                            requestAnthropicMessage(
                                messages,
                                toolRegistry.toolsJson(),
                                provider,
                                model,
                                maxTokens,
                                cancelToken
                            )
                        } else {
                            requestAnthropicMessageStream(
                                messages,
                                toolRegistry.toolsJson(),
                                provider,
                                model,
                                maxTokens,
                                onDelta,
                                onReasoningDelta,
                                onToolCall,
                                cancelToken
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                return AgentCompletionResult(
                    assistantContent = null,
                    toolCalls = toolCalls,
                    errorMessage = e.message ?: "请求失败"
                )
            }

            if (cancelToken?.isCancelled() == true) {
                return AgentCompletionResult(
                    assistantContent = null,
                    toolCalls = toolCalls,
                    errorMessage = "已取消"
                )
            }

            val message = response.second ?: return AgentCompletionResult(
                assistantContent = null,
                toolCalls = toolCalls,
                errorMessage = response.first ?: "返回数据异常"
            )

            val toolCallsArray = message.getAsJsonArray("tool_calls")
            if (toolCallsArray != null && toolCallsArray.size() > 0) {
                val signature = buildToolSignature(toolCallsArray)
                if (signature != null) {
                    if (signature == lastToolSignature) {
                        repeatedToolCalls += 1
                    } else {
                        repeatedToolCalls = 0
                    }
                    lastToolSignature = signature
                    if (repeatedToolCalls >= MAX_REPEAT_TOOL_CALLS) {
                        return AgentCompletionResult(
                            assistantContent = null,
                            toolCalls = toolCalls,
                            errorMessage = "检测到连续相同工具调用，已终止。请提供更多信息或调整问题。"
                        )
                    }
                }
                messages.add(message)
                val toolResults = executeTools(
                    toolRegistry,
                    toolCallsArray,
                    toolCalls,
                    onToolResult,
                    toolTimeoutMs,
                    cancelToken
                )
                if (cancelToken?.isCancelled() == true) {
                    return AgentCompletionResult(
                        assistantContent = null,
                        toolCalls = toolCalls,
                        errorMessage = "已取消"
                    )
                }
                toolResults.forEach { messages.add(it) }
                iterations++
                continue
            }

            val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString
            messages.add(message)
            return AgentCompletionResult(
                assistantContent = content,
                toolCalls = toolCalls
            )
        }

        return AgentCompletionResult(
            assistantContent = null,
            toolCalls = toolCalls,
            errorMessage = "函数调用次数过多(上限: $maxIterations)"
        )
    }

    fun listModels(provider: AgentProviderState): ModelListResult {
        val providerType = AgentProviderType.fromId(provider.type)
        return try {
            val rawModels = when (providerType) {
                AgentProviderType.OPENAI -> listOpenAiModels(provider)
                AgentProviderType.ANTHROPIC -> listAnthropicModels(provider)
            }
            if (rawModels.isEmpty()) {
                ModelListResult(rawModels, "未获取到可用模型")
            } else {
                ModelListResult(rawModels)
            }
        } catch (e: Throwable) {
            ModelListResult(emptyList(), e.message ?: "模型列表获取失败")
        }
    }

    private fun listOpenAiModels(provider: AgentProviderState): List<String> {
        val page = getOpenAiClient(provider).models().list()
        return page.data().map { it.id() }.filter { it.isNotBlank() }.toMutableList()
    }

    private fun listAnthropicModels(provider: AgentProviderState): List<String> {
        val page = getAnthropicClient(provider).models().list()
        return page.data().map { it.id() }.filter { it.isNotBlank() }.toMutableList()
    }


    private fun requestOpenAiChatCompletion(
        messages: List<JsonObject>,
        tools: JsonArray,
        provider: AgentProviderState,
        model: String,
        cancelToken: CancelToken?,
    ): Pair<String?, JsonObject?> {
        if (cancelToken?.isCancelled() == true) {
            return "已取消" to null
        }
        val client = getOpenAiClient(provider)
        val params = buildOpenAiParams(messages, tools, provider, model)
        val completion = client.chat().completions().create(params)
        val message = completion.choices().firstOrNull()?.message()
            ?: return "未返回 message" to null
        return null to openAiMessageToJson(message)
    }

    private fun requestOpenAiChatCompletionStream(
        messages: List<JsonObject>,
        tools: JsonArray,
        provider: AgentProviderState,
        model: String,
        onDelta: (String) -> Unit,
        onReasoningDelta: ((String) -> Unit)?,
        onToolCall: ((ToolCallStreamEvent) -> Unit)?,
        cancelToken: CancelToken?,
    ): Pair<String?, JsonObject?> {
        if (cancelToken?.isCancelled() == true) {
            return "已取消" to null
        }
        val client = getOpenAiClient(provider)
        val params = buildOpenAiParams(messages, tools, provider, model)
        val toolCalls = linkedMapOf<Int, ToolCallBuilder>()
        val toolCallIdToIndex = linkedMapOf<String, Int>()
        val contentBuilder = StringBuilder()
        val pendingReasoning = StringBuilder()
        var cancelled = false
        val response = client.chat().completions().createStreaming(params)
        response.use { streamResponse ->
            val iterator = streamResponse.stream().iterator()
            while (iterator.hasNext()) {
                if (cancelToken?.isCancelled() == true) {
                    cancelled = true
                    break
                }
                val chunk = iterator.next()
                chunk.choices().forEach { choice ->
                    val delta = choice.delta()
                    val extra = delta._additionalProperties()
                    val reasoningDelta =
                        extra["reasoning_content"]?.asString()?.orElse(null)
                            ?: extra["reasoning"]?.asString()?.orElse(null)
                    if (!reasoningDelta.isNullOrEmpty()) {
                        pendingReasoning.append(reasoningDelta)
                        onReasoningDelta?.invoke(reasoningDelta)
                    }
                    val contentDelta = delta.content().orElse(null)
                    if (!contentDelta.isNullOrEmpty()) {
                        contentBuilder.append(contentDelta)
                        onDelta.invoke(contentDelta)
                    }
                    val refusalDelta = delta.refusal().orElse(null)
                    if (!refusalDelta.isNullOrEmpty()) {
                        contentBuilder.append(refusalDelta)
                        onDelta.invoke(refusalDelta)
                    }
                    delta.toolCalls().orElse(emptyList()).forEach { callDelta ->
                        val indexValue = callDelta._index().asKnown().orElse(null)
                        val id = callDelta.id().orElse(null)
                        val index = when {
                            indexValue != null -> indexValue.toInt()
                            id != null -> toolCallIdToIndex.getOrPut(id) {
                                (toolCalls.keys.maxOrNull() ?: -1) + 1
                            }
                            toolCalls.size == 1 && toolCalls.values.first().id.isNullOrBlank() ->
                                toolCalls.keys.first()
                            else -> (toolCalls.keys.maxOrNull() ?: -1) + 1
                        }
                        val builder = toolCalls.getOrPut(index) { ToolCallBuilder() }
                        if (!id.isNullOrBlank()) {
                            builder.id = id
                        }
                        val function = callDelta.function().orElse(null)
                        val name = function?.name()?.orElse(null)
                        if (!name.isNullOrBlank()) {
                            builder.name = name
                            if (!builder.started) {
                                builder.started = true
                                onToolCall?.invoke(
                                    ToolCallStreamEvent(index, name, builder.arguments.toString(), false)
                                )
                            }
                        }
                        val arguments = function?.arguments()?.orElse(null)
                        if (!arguments.isNullOrEmpty()) {
                            builder.arguments.append(arguments)
                        }
                    }
                }
            }
        }
        if (cancelled) {
            return "已取消" to null
        }
        if (toolCalls.isNotEmpty()) {
            toolCalls.toSortedMap().forEach { (index, builder) ->
                if (!builder.name.isNullOrBlank()) {
                    onToolCall?.invoke(
                        ToolCallStreamEvent(index, builder.name.orEmpty(), builder.arguments.toString(), true)
                    )
                }
            }
        }
        val toolCallsJson = JsonArray()
        toolCalls.toSortedMap().forEach { (_, builder) ->
            if (!builder.name.isNullOrBlank()) {
                toolCallsJson.add(builder.toJson())
            }
        }
        val finalContent = if (contentBuilder.isNotBlank()) {
            contentBuilder.toString()
        } else {
            pendingReasoning.toString().takeIf { it.isNotBlank() }?.let { stripThinkTags(it) }.orEmpty()
        }
        val messageJson = JsonObject().apply {
            addProperty("role", "assistant")
            if (finalContent.isNotBlank()) {
                addProperty("content", finalContent)
            }
            if (toolCallsJson.size() > 0) {
                add("tool_calls", toolCallsJson)
            }
        }
        if (!messageJson.has("content") && toolCallsJson.size() == 0) {
            return "未返回内容" to null
        }
        return null to messageJson
    }

    private fun requestAnthropicMessage(
        messages: List<JsonObject>,
        tools: JsonArray,
        provider: AgentProviderState,
        model: String,
        maxTokens: Int,
        cancelToken: CancelToken?,
    ): Pair<String?, JsonObject?> {
        if (cancelToken?.isCancelled() == true) {
            return "已取消" to null
        }
        val client = getAnthropicClient(provider)
        val params = buildAnthropicParams(messages, tools, provider, model, maxTokens)
        val response = client.messages().create(params)
        val message = anthropicMessageToOpenAi(response) ?: return "未返回内容" to null
        return null to message
    }

    private fun requestAnthropicMessageStream(
        messages: List<JsonObject>,
        tools: JsonArray,
        provider: AgentProviderState,
        model: String,
        maxTokens: Int,
        onDelta: (String) -> Unit,
        onReasoningDelta: ((String) -> Unit)?,
        onToolCall: ((ToolCallStreamEvent) -> Unit)?,
        cancelToken: CancelToken?,
    ): Pair<String?, JsonObject?> {
        if (cancelToken?.isCancelled() == true) {
            return "已取消" to null
        }
        val client = getAnthropicClient(provider)
        val params = buildAnthropicParams(messages, tools, provider, model, maxTokens)
        val accumulator = MessageAccumulator.create()
        var cancelled = false
        val response = client.messages().createStreaming(params)
        response.use { streamResponse ->
            val iterator = streamResponse.stream().iterator()
            while (iterator.hasNext()) {
                if (cancelToken?.isCancelled() == true) {
                    cancelled = true
                    break
                }
                val event = iterator.next()
                accumulator.accumulate(event)
                val deltaEvent = event.contentBlockDelta().orElse(null)
                if (deltaEvent != null) {
                    val delta = deltaEvent.delta()
                    delta.text().ifPresent { onDelta(it.text()) }
                    delta.thinking().ifPresent { onReasoningDelta?.invoke(it.thinking()) }
                }
            }
        }
        if (cancelled) {
            return "已取消" to null
        }
        val message = anthropicMessageToOpenAi(accumulator.message()) ?: return "未返回内容" to null
        val toolCallsArray = message.getAsJsonArray("tool_calls")
        if (toolCallsArray != null && toolCallsArray.size() > 0) {
            toolCallsArray.forEachIndexed { index, element ->
                val call = element.asJsonObject
                val function = call.getAsJsonObject("function")
                val name = function?.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val arguments = function?.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                onToolCall?.invoke(ToolCallStreamEvent(index, name, arguments, true))
            }
        }
        return null to message
    }

    private fun executeTools(
        toolRegistry: AgentToolRegistry,
        toolCallsArray: JsonArray,
        toolLogs: MutableList<ToolCallLog>,
        onToolResult: ((ToolCallLog) -> Unit)?,
        toolTimeoutMs: Long,
        cancelToken: CancelToken?,
    ): List<JsonObject> {
        val toolMessages = mutableListOf<JsonObject>()
        if (cancelToken?.isCancelled() == true) {
            return toolMessages
        }
        for (element in toolCallsArray) {
            if (cancelToken?.isCancelled() == true) {
                break
            }
            val call = element.asJsonObject
            val callId = call.get("id")?.asString.orEmpty()
            val function = call.getAsJsonObject("function")
            val name = function?.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: call.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: ""
            val arguments = function?.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val tool = toolRegistry.findTool(name)
            val result = if (tool == null) {
                """{"ok":false,"error":"未找到函数: $name"}"""
            } else {
                runToolWithTimeout(tool, arguments, toolTimeoutMs)
            }
            val safeResult = truncateForModel(result, MAX_TOOL_RESULT_CHARS)
            val toolLog = ToolCallLog(name, arguments, safeResult)
            toolLogs.add(toolLog)
            onToolResult?.invoke(toolLog)
            toolMessages.add(
                JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", callId)
                    addProperty("content", safeResult)
                }
            )
        }
        return toolMessages
    }

    private fun runToolWithTimeout(tool: AgentTool, arguments: String, timeoutMs: Long): String {
        if (timeoutMs <= 0) {
            return try {
                tool.call(arguments)
            } catch (e: Throwable) {
                """{"ok":false,"error":"调用失败: ${e.message ?: "unknown"}"}"""
            }
        }
        val future = CompletableFuture.supplyAsync({ tool.call(arguments) }, toolExecutor)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            """{"ok":false,"error":"工具执行超时(${timeoutMs}ms)"}"""
        } catch (e: ExecutionException) {
            val message = e.cause?.message ?: "unknown"
            """{"ok":false,"error":"调用失败: $message"}"""
        } catch (e: Throwable) {
            """{"ok":false,"error":"调用失败: ${e.message ?: "unknown"}"}"""
        }
    }

    private fun getOpenAiClient(provider: AgentProviderState): OpenAIClient {
        val apiKey = provider.apiKey.trim()
        val baseUrl = normalizeOpenAiBaseUrl(provider.baseUrl)
        val proxyKey = buildProxyKey(provider)
        val headersKey = AgentProviderSupport.normalizeHeaderKey(provider.customHeaders)
        val key = "openai|$baseUrl|$apiKey|$proxyKey|$headersKey"
        synchronized(clientLock) {
            openAiClients[key]?.let { return it }
            val builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
            val headerResult = AgentProviderSupport.parseCustomHeaders(provider.customHeaders)
            if (headerResult.headers.isNotEmpty()) {
                builder.putAllHeaders(headerResult.headers)
            }
            buildProxy(provider)?.let { builder.proxy(it) }
            val client = builder.build()
            openAiClients[key] = client
            return client
        }
    }

    private fun getAnthropicClient(provider: AgentProviderState): AnthropicClient {
        val apiKey = provider.apiKey.trim()
        val baseUrl = normalizeAnthropicBaseUrl(provider.baseUrl)
        val proxyKey = buildProxyKey(provider)
        val headersKey = AgentProviderSupport.normalizeHeaderKey(provider.customHeaders)
        val key = "anthropic|$baseUrl|$apiKey|$proxyKey|$headersKey"
        synchronized(clientLock) {
            anthropicClients[key]?.let { return it }
            val builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
            val headerResult = AgentProviderSupport.parseCustomHeaders(provider.customHeaders)
            if (headerResult.headers.isNotEmpty()) {
                builder.putAllHeaders(headerResult.headers)
            }
            buildProxy(provider)?.let { builder.proxy(it) }
            val client = builder.build()
            anthropicClients[key] = client
            return client
        }
    }

    private fun buildProxyKey(provider: AgentProviderState): String {
        if (!provider.proxyEnabled) {
            return "proxy=none"
        }
        val type = AgentProxyType.fromId(provider.proxyType).id
        val host = provider.proxyHost.trim()
        val port = provider.proxyPort
        return "proxy=$type:$host:$port"
    }

    private fun buildProxy(provider: AgentProviderState): Proxy? {
        if (!provider.proxyEnabled) {
            return null
        }
        val host = provider.proxyHost.trim()
        val port = provider.proxyPort
        if (host.isBlank() || port !in 1..65535) {
            return null
        }
        val type = AgentProxyType.fromId(provider.proxyType).javaType
        return Proxy(type, InetSocketAddress(host, port))
    }

    private fun normalizeOpenAiBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().ifBlank { "https://api.openai.com/v1" }
        val normalized = trimmed.trimEnd('/')
        val withoutChat = if (normalized.endsWith("/chat/completions")) {
            normalized.removeSuffix("/chat/completions")
        } else {
            normalized
        }
        return if (withoutChat.contains("/v1")) {
            withoutChat
        } else {
            "$withoutChat/v1"
        }
    }

    private fun normalizeAnthropicBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().ifBlank { "https://api.anthropic.com" }
        val normalized = trimmed.trimEnd('/')
        return when {
            normalized.endsWith("/v1/messages") -> normalized.removeSuffix("/v1/messages")
            normalized.endsWith("/v1") -> normalized.removeSuffix("/v1")
            else -> normalized
        }
    }

    private fun extractSystemPrompt(messages: List<JsonObject>): String? {
        val prompts = messages.mapNotNull { message ->
            val role = message.get("role")?.asString ?: return@mapNotNull null
            if (role != "system") return@mapNotNull null
            message.get("content")?.takeIf { !it.isJsonNull }?.asString
        }
        return prompts.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun buildOpenAiParams(
        messages: List<JsonObject>,
        tools: JsonArray,
        provider: AgentProviderState,
        model: String,
    ): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(model))
            .messages(toOpenAiMessages(messages))
        val openAiTools = toOpenAiTools(tools)
        if (openAiTools.isNotEmpty()) {
            builder.tools(openAiTools)
        }
        val settings = AgentProviderSupport.findModelSettings(provider, model)
        val effort = settings?.openAiReasoningEffort?.trim().orEmpty()
        if (effort.isNotBlank()) {
            builder.reasoningEffort(ReasoningEffort.of(effort))
        }
        parseOptionalDouble(settings?.openAiTemperature)?.let { builder.temperature(it) }
        parseOptionalDouble(settings?.openAiTopP)?.let { builder.topP(it) }
        parseOptionalLong(settings?.openAiMaxTokens)?.let {
            builder.putAdditionalBodyProperty("max_tokens", OpenAiJsonValue.from(it))
        }
        parseOptionalLong(settings?.openAiMaxCompletionTokens)?.let { builder.maxCompletionTokens(it) }
        parseOptionalDouble(settings?.openAiPresencePenalty)?.let { builder.presencePenalty(it) }
        parseOptionalDouble(settings?.openAiFrequencyPenalty)?.let { builder.frequencyPenalty(it) }
        parseOptionalLong(settings?.openAiSeed)?.let { builder.seed(it) }
        val stopSequences = parseStopSequences(settings?.openAiStopSequences)
        if (stopSequences.isNotEmpty()) {
            if (stopSequences.size == 1) {
                builder.stop(stopSequences.first())
            } else {
                builder.stopOfStrings(stopSequences)
            }
        }
        val responseFormat = settings?.openAiResponseFormat?.trim().orEmpty()
        when (responseFormat) {
            "text" -> builder.responseFormat(ResponseFormatText.builder().build())
            "json_object" -> builder.responseFormat(ResponseFormatJsonObject.builder().build())
            "json_schema" -> {
                val schemaMap = parseOpenAiSchemaMap(settings?.openAiResponseFormatSchemaJson)
                if (schemaMap != null) {
                    val schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder()
                        .additionalProperties(schemaMap)
                        .build()
                    val schemaBuilder = ResponseFormatJsonSchema.JsonSchema.builder()
                        .name(settings?.openAiResponseFormatSchemaName?.ifBlank { "response" } ?: "response")
                        .schema(schema)
                    val description = settings?.openAiResponseFormatSchemaDescription?.trim().orEmpty()
                    if (description.isNotBlank()) {
                        schemaBuilder.description(description)
                    }
                    if (settings?.openAiResponseFormatSchemaStrict == true) {
                        schemaBuilder.strict(true)
                    }
                    builder.responseFormat(
                        ResponseFormatJsonSchema.builder()
                            .jsonSchema(schemaBuilder.build())
                            .build()
                    )
                }
            }
        }
        val logprobsValue = parseOptionalBoolean(settings?.openAiLogprobs)
        if (logprobsValue != null) {
            builder.logprobs(logprobsValue)
            if (logprobsValue) {
                parseOptionalLong(settings?.openAiTopLogprobs)?.let { builder.topLogprobs(it) }
            }
        }
        val toolChoice = settings?.openAiToolChoice?.trim().orEmpty()
        if (toolChoice.isNotBlank()) {
            builder.toolChoice(ChatCompletionToolChoiceOption.Auto.of(toolChoice))
        } else if (openAiTools.isNotEmpty()) {
            builder.toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO)
        }
        parseOptionalBoolean(settings?.openAiParallelToolCalls)?.let { builder.parallelToolCalls(it) }
        return builder.build()
    }

    private fun buildAnthropicParams(
        messages: List<JsonObject>,
        tools: JsonArray,
        provider: AgentProviderState,
        model: String,
        maxTokens: Int,
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(Model.of(model))
            .maxTokens(maxTokens.toLong())
            .messages(toAnthropicMessages(messages))
        val settings = AgentProviderSupport.findModelSettings(provider, model)
        parseOptionalLong(settings?.anthropicMaxTokens)?.let { builder.maxTokens(it) }
        val systemPrompt = extractSystemPrompt(messages)
        if (!systemPrompt.isNullOrBlank()) {
            builder.system(systemPrompt)
        }
        val anthropicTools = toAnthropicTools(tools)
        if (anthropicTools.isNotEmpty()) {
            builder.tools(anthropicTools)
            builder.toolChoice(ToolChoiceAuto.builder().build())
        }
        val mode = settings?.anthropicThinkingMode?.trim().orEmpty()
        when (mode) {
            "enabled" -> {
                val budget = settings?.anthropicThinkingBudgetTokens ?: 0
                if (budget > 0) {
                    builder.thinking(
                        ThinkingConfigParam.ofEnabled(
                            ThinkingConfigEnabled.builder().budgetTokens(budget.toLong()).build()
                        )
                    )
                }
            }
            "adaptive" -> {
                builder.thinking(
                    ThinkingConfigParam.ofAdaptive(
                        ThinkingConfigAdaptive.builder().build()
                    )
                )
            }
            "disabled" -> {
                builder.thinking(
                    ThinkingConfigParam.ofDisabled(
                        ThinkingConfigDisabled.builder().build()
                    )
                )
            }
        }
        parseOptionalDouble(settings?.anthropicTemperature)?.let { builder.temperature(it) }
        parseOptionalDouble(settings?.anthropicTopP)?.let { builder.topP(it) }
        parseOptionalLong(settings?.anthropicTopK)?.let { builder.topK(it) }
        val stopSequences = parseStopSequences(settings?.anthropicStopSequences)
        if (stopSequences.isNotEmpty()) {
            builder.stopSequences(stopSequences)
        }
        val serviceTier = settings?.anthropicServiceTier?.trim().orEmpty()
        if (serviceTier.isNotBlank()) {
            builder.serviceTier(MessageCreateParams.ServiceTier.of(serviceTier))
        }
        val inferenceGeo = settings?.anthropicInferenceGeo?.trim().orEmpty()
        if (inferenceGeo.isNotBlank()) {
            builder.inferenceGeo(inferenceGeo)
        }
        val metadataUserId = settings?.anthropicMetadataUserId?.trim().orEmpty()
        if (metadataUserId.isNotBlank()) {
            builder.metadata(Metadata.builder().userId(metadataUserId).build())
        }
        val outputEffort = settings?.anthropicOutputEffort?.trim().orEmpty()
        val outputSchemaMap = parseAnthropicSchemaMap(settings?.anthropicOutputSchemaJson)
        if (outputEffort.isNotBlank() || outputSchemaMap != null) {
            val outputBuilder = OutputConfig.builder()
            if (outputEffort.isNotBlank()) {
                outputBuilder.effort(OutputConfig.Effort.of(outputEffort))
            }
            if (outputSchemaMap != null) {
                val schema = JsonOutputFormat.Schema.builder()
                    .additionalProperties(outputSchemaMap)
                    .build()
                outputBuilder.format(
                    JsonOutputFormat.builder()
                        .schema(schema)
                        .build()
                )
            }
            builder.outputConfig(outputBuilder.build())
        }
        return builder.build()
    }

    private fun parseOptionalLong(raw: String?): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        return trimmed.toLongOrNull()
    }

    private fun parseOptionalDouble(raw: String?): Double? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        return trimmed.toDoubleOrNull()
    }

    private fun parseOptionalBoolean(raw: String?): Boolean? {
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        return when (trimmed) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun parseStopSequences(raw: String?): List<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return emptyList()
        }
        return trimmed.replace(",", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun parseOpenAiSchemaMap(raw: String?): Map<String, OpenAiJsonValue>? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        val element = try {
            JsonParser.parseString(trimmed)
        } catch (_: Throwable) {
            return null
        }
        if (!element.isJsonObject) {
            return null
        }
        return element.asJsonObject.entrySet().associate { it.key to toOpenAiJsonValue(it.value) }
    }

    private fun parseAnthropicSchemaMap(raw: String?): Map<String, AnthropicJsonValue>? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        val element = try {
            JsonParser.parseString(trimmed)
        } catch (_: Throwable) {
            return null
        }
        if (!element.isJsonObject) {
            return null
        }
        return element.asJsonObject.entrySet().associate { it.key to toAnthropicJsonValue(it.value) }
    }

    private fun toOpenAiMessages(messages: List<JsonObject>): List<ChatCompletionMessageParam> {
        val result = mutableListOf<ChatCompletionMessageParam>()
        messages.forEach { message ->
            val role = message.get("role")?.asString ?: return@forEach
            val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            when (role) {
                "system" -> {
                    result.add(
                        ChatCompletionMessageParam.ofSystem(
                            ChatCompletionSystemMessageParam.builder().content(content).build()
                        )
                    )
                }
                "user" -> {
                    result.add(
                        ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder().content(content).build()
                        )
                    )
                }
                "assistant" -> {
                    val builder = ChatCompletionAssistantMessageParam.builder()
                    if (content.isNotBlank()) {
                        builder.content(content)
                    }
                    val toolCallsArray = message.getAsJsonArray("tool_calls")
                    if (toolCallsArray != null) {
                        val toolCalls = toOpenAiToolCalls(toolCallsArray)
                        if (toolCalls.isNotEmpty()) {
                            builder.toolCalls(toolCalls)
                        }
                    }
                    result.add(ChatCompletionMessageParam.ofAssistant(builder.build()))
                }
                "tool" -> {
                    val toolCallId = message.get("tool_call_id")?.asString.orEmpty()
                    result.add(
                        ChatCompletionMessageParam.ofTool(
                            ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCallId)
                                .content(content)
                                .build()
                        )
                    )
                }
            }
        }
        return result
    }

    private fun toOpenAiTools(tools: JsonArray): List<ChatCompletionTool> {
        val result = mutableListOf<ChatCompletionTool>()
        tools.forEach { element ->
            val tool = element.asJsonObject
            val function = tool.getAsJsonObject("function") ?: return@forEach
            val name = function.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val description = function.get("description")?.takeIf { !it.isJsonNull }?.asString
            val desc = description
            val parameters = function.getAsJsonObject("parameters")
            val paramsValue = toOpenAiJsonValue(parameters ?: JsonObject())
            val paramsMap = paramsValue.asObject().orElse(emptyMap())
            val params = FunctionParameters.builder().putAllAdditionalProperties(paramsMap).build()
            val definition = FunctionDefinition.builder()
                .name(name)
                .apply { if (!desc.isNullOrBlank()) description(desc) }
                .parameters(params)
                .build()
            val toolDefinition = ChatCompletionFunctionTool.builder().function(definition).build()
            result.add(ChatCompletionTool.ofFunction(toolDefinition))
        }
        return result
    }

    private fun toOpenAiToolCalls(toolCalls: JsonArray): List<ChatCompletionMessageToolCall> {
        val result = mutableListOf<ChatCompletionMessageToolCall>()
        toolCalls.forEach { element ->
            val call = element.asJsonObject
            val id = call.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val function = call.getAsJsonObject("function") ?: return@forEach
            val name = function.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val arguments = function.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val functionCall = ChatCompletionMessageFunctionToolCall.Function.builder()
                .name(name)
                .arguments(arguments)
                .build()
            val toolCall = ChatCompletionMessageFunctionToolCall.builder()
                .id(id)
                .function(functionCall)
                .build()
            result.add(ChatCompletionMessageToolCall.ofFunction(toolCall))
        }
        return result
    }

    private fun openAiMessageToJson(message: ChatCompletionMessage): JsonObject {
        val toolCalls = JsonArray()
        message.toolCalls().orElse(emptyList()).forEach { call ->
            if (!call.isFunction()) {
                return@forEach
            }
            val functionCall = call.asFunction()
            val function = functionCall.function()
            toolCalls.add(JsonObject().apply {
                addProperty("id", functionCall.id())
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", function.name())
                    addProperty("arguments", function.arguments())
                })
            })
        }
        val content = message.content().orElse(null)?.takeIf { it.isNotBlank() }
            ?: message.refusal().orElse(null)?.takeIf { it.isNotBlank() }
        return JsonObject().apply {
            addProperty("role", "assistant")
            content?.let { addProperty("content", it) }
            if (toolCalls.size() > 0) {
                add("tool_calls", toolCalls)
            }
        }
    }

    private fun toAnthropicMessages(messages: List<JsonObject>): List<MessageParam> {
        val result = mutableListOf<MessageParam>()
        messages.forEach { message ->
            val role = message.get("role")?.asString ?: return@forEach
            if (role == "system") {
                return@forEach
            }
            if (role == "tool") {
                val toolId = message.get("tool_call_id")?.asString.orEmpty()
                val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val toolResult = ToolResultBlockParam.builder()
                    .toolUseId(toolId)
                    .content(content)
                    .build()
                val contentBlocks = listOf(ContentBlockParam.ofToolResult(toolResult))
                result.add(
                    MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(contentBlocks)
                        .build()
                )
                return@forEach
            }
            val contentBlocks = mutableListOf<ContentBlockParam>()
            val text = message.get("content")?.takeIf { !it.isJsonNull }?.asString
            if (!text.isNullOrBlank()) {
                val textBlock = TextBlockParam.builder().text(text).build()
                contentBlocks.add(ContentBlockParam.ofText(textBlock))
            }
            val toolCalls = message.getAsJsonArray("tool_calls")
            if (toolCalls != null) {
                toolCalls.forEach { element ->
                    val call = element.asJsonObject
                    val id = call.get("id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val function = call.getAsJsonObject("function")
                    val name = function?.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val arguments = function?.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    val input = parseArgsJson(arguments)
                    val inputValue = toAnthropicJsonValue(input)
                    val inputMap = inputValue.asObject().orElse(emptyMap())
                    val toolUseInput = ToolUseBlockParam.Input.builder()
                        .additionalProperties(inputMap)
                        .build()
                    val toolUseBlock = ToolUseBlockParam.builder()
                        .id(id)
                        .name(name)
                        .input(toolUseInput)
                        .build()
                    contentBlocks.add(ContentBlockParam.ofToolUse(toolUseBlock))
                }
            }
            if (contentBlocks.isNotEmpty()) {
                val roleParam = if (role == "assistant") MessageParam.Role.ASSISTANT else MessageParam.Role.USER
                result.add(
                    MessageParam.builder()
                        .role(roleParam)
                        .contentOfBlockParams(contentBlocks)
                        .build()
                )
            }
        }
        return result
    }

    private fun toAnthropicTools(tools: JsonArray): List<ToolUnion> {
        val result = mutableListOf<ToolUnion>()
        tools.forEach { element ->
            val tool = element.asJsonObject
            val function = tool.getAsJsonObject("function") ?: return@forEach
            val name = function.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
            val description = function.get("description")?.takeIf { !it.isJsonNull }?.asString
            val desc = description
            val inputSchemaJson = function.getAsJsonObject("parameters") ?: JsonObject()
            val schemaValue = toAnthropicJsonValue(inputSchemaJson)
            val schemaMap = schemaValue.asObject().orElse(emptyMap())
            val schemaBuilder = Tool.InputSchema.builder()
            schemaMap["type"]?.let { schemaBuilder.type(it) }
            schemaMap["properties"]?.asObject()?.orElse(emptyMap<String, AnthropicJsonValue>())?.let { props ->
                val propsValue = Tool.InputSchema.Properties.builder()
                    .additionalProperties(props)
                    .build()
                schemaBuilder.properties(propsValue)
            }
            schemaMap["required"]?.asArray()?.let { requiredArray ->
                val required = requiredArray.orElse(Collections.emptyList()).mapNotNull { it.asString().orElse(null) }
                if (required.isNotEmpty()) {
                    schemaBuilder.required(required)
                }
            }
            val extra = schemaMap.filterKeys { it != "type" && it != "properties" && it != "required" }
            if (extra.isNotEmpty()) {
                schemaBuilder.additionalProperties(extra)
            }
            val toolDefinition = Tool.builder()
                .name(name)
                .inputSchema(schemaBuilder.build())
                .apply { if (!desc.isNullOrBlank()) description(desc) }
                .build()
            result.add(ToolUnion.ofTool(toolDefinition))
        }
        return result
    }

    private fun anthropicMessageToOpenAi(message: Message): JsonObject? {
        val textBuilder = StringBuilder()
        val toolCalls = JsonArray()
        message.content().forEach { block ->
            when {
                block.isText() -> textBuilder.append(block.asText().text())
                block.isToolUse() -> {
                    val toolUse = block.asToolUse()
                    toolCalls.add(JsonObject().apply {
                        addProperty("id", toolUse.id())
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", toolUse.name())
                            addProperty("arguments", anthropicJsonValueToString(toolUse._input()))
                        })
                    })
                }
            }
        }
        return JsonObject().apply {
            addProperty("role", "assistant")
            if (textBuilder.isNotEmpty()) {
                addProperty("content", textBuilder.toString())
            }
            if (toolCalls.size() > 0) {
                add("tool_calls", toolCalls)
            }
        }
    }

    private fun stripThinkTags(text: String): String {
        return text.replace("<think>", "").replace("</think>", "").trim()
    }

    private fun anthropicJsonValueToString(value: AnthropicJsonValue): String {
        return try {
            anthropicMapper.writeValueAsString(value)
        } catch (_: Throwable) {
            value.toString()
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

    private fun toOpenAiJsonValue(element: JsonElement): OpenAiJsonValue {
        return OpenAiJsonValue.from(gsonToAny(element))
    }

    private fun toAnthropicJsonValue(element: JsonElement): AnthropicJsonValue {
        return AnthropicJsonValue.from(gsonToAny(element))
    }

    private fun parseArgsJson(raw: String): JsonObject {
        if (raw.isBlank()) {
            return JsonObject()
        }
        return try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Throwable) {
            JsonObject()
        }
    }

    private fun truncateForModel(value: String, maxChars: Int): String {
        if (value.length <= maxChars) {
            return value
        }
        return value.take(maxChars) + "...(truncated, maxChars=$maxChars, length=${value.length})"
    }

    private fun buildToolSignature(toolCallsArray: JsonArray): String? {
        val signatureParts = mutableListOf<String>()
        toolCallsArray.forEach { element ->
            val call = element.asJsonObject
            val function = call.getAsJsonObject("function")
            val name = function?.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: call.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: ""
            if (name.isBlank()) {
                return null
            }
            val arguments = function?.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            signatureParts.add("$name:$arguments")
        }
        return signatureParts.joinToString("|")
    }

    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val arguments: StringBuilder = StringBuilder()
        var started: Boolean = false

        fun toJson(): JsonObject {
            val function = JsonObject().apply {
                addProperty("name", name.orEmpty())
                addProperty("arguments", arguments.toString())
            }
            return JsonObject().apply {
                addProperty("id", id.orEmpty())
                addProperty("type", "function")
                add("function", function)
            }
        }
    }
}
