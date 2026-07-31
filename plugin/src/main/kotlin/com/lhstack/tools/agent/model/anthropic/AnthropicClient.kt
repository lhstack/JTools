package com.lhstack.tools.agent.model.anthropic

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.http.ModelHttpExecutor
import com.lhstack.tools.agent.model.http.ModelHttpStatusException
import com.lhstack.tools.agent.model.http.ModelHttpSupport
import com.lhstack.tools.agent.model.http.ModelHttpTrace
import com.lhstack.tools.agent.model.http.ModelRequestCancelledException
import com.lhstack.tools.agent.model.http.SseParser
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ProviderRound
import com.lhstack.tools.agent.model.llm.ProviderToolCall
import com.lhstack.tools.agent.model.llm.Usage
import com.lhstack.tools.agent.model.provider.AppendMessage
import com.lhstack.tools.agent.model.provider.AppendMessageChannel
import com.lhstack.tools.agent.model.provider.ModelStreamSink
import com.lhstack.tools.agent.model.provider.ToolRuntime
import com.lhstack.tools.agent.model.provider.toUserMessage

/**
 * Anthropic 客户端。完全照抄 awake-claw anthropic.rs 的 AnthropicClient。
 *
 * 覆盖 Messages API 的 stream / 非 stream，工具回环、重试、SSE 增量、thinking、
 * usage、模型列表分页全部照抄。
 *
 * 与 awake 差异仅在运行时机制：
 * - reqwest 异步 -> JDK11 HttpClient 阻塞（ModelHttpExecutor）。
 * - tokio::select 取消 -> ModelCancel 关流中断。
 * - emit_stream_event 线程局部 writer -> ModelStreamSink 显式回调。
 * 请求构建、字段筛选、解析逻辑与 awake 一一对应。
 */
class AnthropicClientParams(
    val executor: ModelHttpExecutor,
    val apiKey: String,
    val baseUrl: String,
    val anthropicVersion: String?,
    val customHeaders: Map<String, String> = emptyMap(),
    val httpTrace: ModelHttpTrace?,
    val streamSink: ModelStreamSink = ModelStreamSink.NOOP,
)

class AnthropicClient(private val params: AnthropicClientParams) {

    private val executor = params.executor
    private val apiKey = params.apiKey
    private val baseUrl = params.baseUrl
    private val anthropicVersion = params.anthropicVersion?.takeIf { it.isNotBlank() } ?: "2023-06-01"
    private val httpTrace = params.httpTrace
    private val streamSink = params.streamSink

    // -------- chat --------

    /** 照抄 chat：非流式工具回环。 */
    internal fun chat(
        request: AnthropicMessageRequest,
        toolRuntime: ToolRuntime,
        cancel: ModelCancel?,
        appendMessageChannel: AppendMessageChannel,
    ): ProviderRound {
        val total = ProviderRound()
        var rounds = 0
        while (true) {
            throwIfCancelled(cancel)
            val value = postMessage(request, cancel)
            val round = AnthropicParser.parseMessageResponse(value)
            emitRoundText(round)
            val loop = continueToolLoop(request, total, round, rounds, toolRuntime, cancel, appendMessageChannel)
            if (!loop.continueLoop) {
                return total
            }
            rounds = loop.rounds
        }
    }

    /** 照抄 stream：流式工具回环。 */
    internal fun stream(
        request: AnthropicMessageRequest,
        toolRuntime: ToolRuntime,
        cancel: ModelCancel?,
        appendMessageChannel: AppendMessageChannel,
    ): ProviderRound {
        val total = ProviderRound()
        var rounds = 0
        while (true) {
            throwIfCancelled(cancel)
            val round = messageStream(request, cancel)
            val loop = continueToolLoop(request, total, round, rounds, toolRuntime, cancel, appendMessageChannel)
            if (!loop.continueLoop) {
                return total
            }
            rounds = loop.rounds
        }
    }

    // -------- list models --------

    /** 照抄 list_models：分页拉取直到 has_more=false。 */
    fun listModels(): List<JsonElement> {
        val models = mutableListOf<JsonElement>()
        var afterId: String? = null
        while (true) {
            val page = listModelsPage(afterId)
            val data = page.takeIf { it.isJsonObject }?.asJsonObject?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw IllegalStateException("Anthropic /v1/models 响应缺少 data 数组")
            data.forEach { anthropicRemoteModel(it)?.let(models::add) }
            val hasMore = page.asJsonObject.get("has_more")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            if (!hasMore) {
                return models
            }
            val lastId = page.asJsonObject.get("last_id")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Anthropic /v1/models 响应 has_more=true 但缺少 last_id")
            afterId = lastId
        }
    }

    // -------- non-stream request --------

    private fun postMessage(request: AnthropicMessageRequest, cancel: ModelCancel?): JsonElement {
        val body = request.body()
        var retryAttempt = 0
        val failures = mutableListOf<String>()
        while (true) {
            try {
                return postMessageOnce(body, request.anthropicBeta(), cancel)
            } catch (e: ModelRequestCancelledException) {
                throw e
            } catch (e: Throwable) {
                throwIfCancelled(cancel)
                retryAttempt += 1
                failures.add("第 $retryAttempt 次重试判定，异常: ${e.message ?: e.toString()}")
                if (retryAttempt <= request.maxRetries) {
                    httpTrace?.retry()
                    continue
                }
                throw IllegalStateException("调用 Anthropic Messages API 失败:\n${failures.joinToString("\n")}")
            }
        }
    }

    private fun postMessageOnce(body: JsonObject, anthropicBeta: String?, cancel: ModelCancel?): JsonElement {
        val url = messagesUrl()
        val serialized = ModelHttpSupport.serializedRequestBody(body)
        httpTrace?.request(url, serialized)
        val text = try {
            executor.sendJson(url, messageHeaders(anthropicBeta), serialized.toString(), cancel)
        } catch (e: ModelHttpStatusException) {
            httpTrace?.error(url, e.status.toString(), "model API returned non-success status", JsonPrimitive(e.bodyText))
            throw IllegalStateException("model API returned ${e.status}: ${e.bodyText}")
        } catch (e: ModelRequestCancelledException) {
            throw e
        } catch (e: Throwable) {
            httpTrace?.error(url, null, e.message ?: e.toString(), null)
            throw e
        }
        val value = try {
            JsonParser.parseString(text)
        } catch (e: Throwable) {
            httpTrace?.response(url, JsonPrimitive(text))
            httpTrace?.error(url, null, e.message ?: e.toString(), null)
            throw e
        }
        httpTrace?.response(url, value)
        return value
    }

    // -------- stream request --------

    private fun messageStream(request: AnthropicMessageRequest, cancel: ModelCancel?): ProviderRound {
        val body = request.body()
        var retryAttempt = 0
        val failures = mutableListOf<String>()
        while (true) {
            try {
                return messageStreamOnce(body, request.anthropicBeta(), cancel)
            } catch (e: ModelRequestCancelledException) {
                throw e
            } catch (e: Throwable) {
                throwIfCancelled(cancel)
                retryAttempt += 1
                failures.add("第 $retryAttempt 次重试判定，异常: ${e.message ?: e.toString()}")
                if (retryAttempt <= request.maxRetries) {
                    httpTrace?.retry()
                    continue
                }
                throw IllegalStateException("调用 Anthropic Messages stream API 失败:\n${failures.joinToString("\n")}")
            }
        }
    }

    private fun messageStreamOnce(body: JsonObject, anthropicBeta: String?, cancel: ModelCancel?): ProviderRound {
        val url = messagesUrl()
        val parser = openStream(url, body, anthropicBeta, cancel)
        val text = StringBuilder()
        val reasoning = mutableListOf<String>()
        val usage = Usage()
        val blocks = sortedMapOf<Long, StreamBlock>()
        parser.use {
            while (true) {
                throwIfCancelled(cancel)
                val event = parser.next() ?: break
                val value = try {
                    JsonParser.parseString(event.data)
                } catch (e: Throwable) {
                    httpTrace?.error(url, null, e.message ?: e.toString(), null)
                    throw e
                }
                val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                when (event.event) {
                    "message_start" -> {
                        obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject?.get("usage")?.let {
                            usage.mergeSnapshot(AnthropicParser.usageFromValue(it))
                        }
                    }
                    "content_block_start" -> startStreamBlock(obj, blocks)
                    "content_block_delta" -> applyStreamDelta(obj, text, reasoning, blocks)
                    "message_delta" -> {
                        obj.get("usage")?.let { usage.mergeSnapshot(AnthropicParser.usageFromValue(it)) }
                    }
                    "message_stop" -> break
                }
            }
        }
        throwIfCancelled(cancel)
        val calls = toolCallsFromStreamBlocks(blocks)
        val round = ProviderRound(response = text.toString())
        round.reasoning.addAll(reasoning)
        round.toolCalls.addAll(calls)
        round.providerMessages.add(AnthropicParser.assistantMessage(text.toString(), calls))
        round.usage.add(AnthropicParser.withTotal(usage))
        traceRoundResponse(url, round)
        return round
    }

    private fun openStream(url: String, body: JsonObject, anthropicBeta: String?, cancel: ModelCancel?): SseParser {
        val serialized = ModelHttpSupport.serializedRequestBody(body)
        httpTrace?.request(url, serialized)
        return try {
            executor.sendJsonStream(url, messageHeaders(anthropicBeta), serialized.toString(), cancel)
        } catch (e: ModelHttpStatusException) {
            httpTrace?.error(url, e.status.toString(), "model API returned non-success status", JsonPrimitive(e.bodyText))
            throw IllegalStateException("model API returned ${e.status}: ${e.bodyText}")
        } catch (e: ModelRequestCancelledException) {
            throw e
        } catch (e: Throwable) {
            httpTrace?.error(url, null, e.message ?: e.toString(), null)
            throw e
        }
    }

    // -------- stream block handling (照抄 StreamBlock / start_stream_block / apply_stream_delta) --------

    private fun startStreamBlock(value: JsonObject, blocks: MutableMap<Long, StreamBlock>) {
        val index = value.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0
        value.get("content_block")?.takeIf { it.isJsonObject }?.let {
            blocks[index] = StreamBlock.fromStart(it.asJsonObject)
        }
    }

    private fun applyStreamDelta(
        value: JsonObject,
        text: StringBuilder,
        reasoning: MutableList<String>,
        blocks: MutableMap<Long, StreamBlock>,
    ) {
        val index = value.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0
        val delta = value.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
        blocks.getOrPut(index) { StreamBlock() }.applyDelta(delta)
        delta?.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let {
            text.append(it)
            streamSink.onResponseDelta(it)
        }
        delta?.get("thinking")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let {
            if (reasoning.isEmpty()) {
                reasoning.add(it)
            } else {
                reasoning[reasoning.size - 1] = reasoning.last() + it
            }
            streamSink.onReasoningDelta(it)
        }
    }

    private fun toolCallsFromStreamBlocks(blocks: Map<Long, StreamBlock>): List<ProviderToolCall> =
        blocks.values.filter { it.blockType == "tool_use" }.map { it.intoToolCall() }

    // -------- list models page --------

    private fun listModelsPage(afterId: String?): JsonElement {
        val base = ModelHttpSupport.joinUrl(baseUrl, "/v1/models")
        val query = StringBuilder("?limit=1000")
        if (afterId != null) {
            query.append("&after_id=").append(java.net.URLEncoder.encode(afterId, Charsets.UTF_8))
        }
        val text = try {
            executor.getForText(base + query, messageHeaders(null), null)
        } catch (e: ModelHttpStatusException) {
            throw IllegalStateException("model API returned ${e.status}: ${e.bodyText}")
        }
        return JsonParser.parseString(text)
    }

    // -------- helpers --------

        private fun messageHeaders(anthropicBeta: String?): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        headers.putAll(params.customHeaders)
        // 系统鉴权与版本头优先，避免被自定义头覆盖。
        headers["x-api-key"] = apiKey
        headers["Authorization"] = "Bearer $apiKey"
        headers["anthropic-version"] = anthropicVersion
        if (anthropicBeta != null) {
            headers["anthropic-beta"] = anthropicBeta
        }
        return headers
    }


    private fun messagesUrl(): String = ModelHttpSupport.joinUrl(baseUrl, "/v1/messages")

    private fun throwIfCancelled(cancel: ModelCancel?) {
        if (cancel?.isCancelled() == true) {
            throw ModelRequestCancelledException()
        }
    }

    private fun traceRoundResponse(url: String, round: ProviderRound) {
        val trace = httpTrace ?: return
        trace.response(url, providerRoundResponseBody(round))
    }

    private fun emitRoundText(round: ProviderRound) {
        if (round.response.isNotEmpty()) {
            streamSink.onResponseDelta(round.response)
        }
        round.reasoning.filter { it.isNotBlank() }.joinToString("\n\n").takeIf { it.isNotBlank() }?.let {
            streamSink.onReasoningDelta(it)
        }
    }

    // -------- tool loop --------

    private data class LoopResult(val continueLoop: Boolean, val rounds: Int)

    private fun continueToolLoop(
        request: AnthropicMessageRequest,
        total: ProviderRound,
        round: ProviderRound,
        rounds: Int,
        toolRuntime: ToolRuntime,
        cancel: ModelCancel?,
        appendMessageChannel: AppendMessageChannel,
    ): LoopResult {
        throwIfCancelled(cancel)
        val toolCalls = mergeProviderRound(total, round)
        if (toolCalls.isEmpty()) {
            return continueWithPendingAppendsOrStop(request, total, rounds, appendMessageChannel)
        }
        val nextRounds = rounds + 1
        if (nextRounds > request.maxToolRounds) {
            throw IllegalStateException("工具回环超过最大轮次 ${request.maxToolRounds}")
        }
        val toolMessage = toolResultMessage(toolRuntime.executeToolCalls(toolCalls))
        throwIfCancelled(cancel)
        val appendedMessages = appendMessageChannel.fetch()
        val userMessages = appendedMessages.map { it.toUserMessage() }
        val assistantMessage = total.providerMessages.last()
        val appended = buildList {
            add(assistantMessage)
            add(toolMessage)
            addAll(userMessages)
        }
        request.appendMessages(appended)
        total.providerMessages.add(toolMessage)
        total.providerMessages.addAll(userMessages)
        appendMessageChannel.onProviderMessages(buildList {
            add(assistantMessage)
            add(toolMessage)
            addAll(userMessages)
        })
        appendedMessages.forEach {
            total.appendMessages.add(
                com.lhstack.tools.agent.model.provider.InjectedAppendMessage(
                    it,
                    nextRounds
                )
            )
        }
        appendMessageChannel.onInjected(appendedMessages, nextRounds)
        return LoopResult(true, nextRounds)
    }

    private fun continueWithPendingAppendsOrStop(
        request: AnthropicMessageRequest,
        total: ProviderRound,
        rounds: Int,
        channel: AppendMessageChannel,
    ): LoopResult {
        val messages = channel.fetchPendingOrClose()
        val finalAssistant = total.providerMessages.last()
        if (messages == null) {
            channel.onProviderMessages(listOf(finalAssistant))
            return LoopResult(false, rounds)
        }
        val userMessages = messages.map { it.toUserMessage() }
        request.appendMessages(buildList {
            total.providerMessages.lastOrNull()?.let { add(it) }
            addAll(userMessages)
        })
        total.providerMessages.addAll(userMessages)
        channel.onProviderMessages(buildList {
            add(finalAssistant)
            addAll(userMessages)
        })
        messages.forEach {
            total.appendMessages.add(
                com.lhstack.tools.agent.model.provider.InjectedAppendMessage(
                    it,
                    rounds + 1
                )
            )
        }
        channel.onInjected(messages, rounds + 1)
        return LoopResult(true, rounds)
    }

    companion object {
        /** 照抄 anthropic_remote_model。 */
        private fun anthropicRemoteModel(item: JsonElement): JsonElement? {
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
            return JsonObject().apply {
                addProperty("id", id)
                add("display_name", nullableString(obj, "display_name"))
                add("type", nullableString(obj, "type"))
                add("created_at", nullableString(obj, "created_at"))
                add("max_input_tokens", nullableNumber(obj, "max_input_tokens"))
                add("max_tokens", nullableNumber(obj, "max_tokens"))
                add("capabilities", obj.get("capabilities") ?: JsonNull.INSTANCE)
            }
        }

        private fun nullableString(obj: JsonObject, key: String): JsonElement {
            val value = obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            return if (value != null) JsonPrimitive(value) else JsonNull.INSTANCE
        }

        private fun nullableNumber(obj: JsonObject, key: String): JsonElement {
            val value = obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            return if (value != null) JsonPrimitive(value) else JsonNull.INSTANCE
        }

        /** 照抄 merge_provider_round。 */
        private fun mergeProviderRound(total: ProviderRound, round: ProviderRound): List<ProviderToolCall> {
            appendRoundResponse(total, round.response)
            total.reasoning.addAll(round.reasoning)
            total.usage.add(round.usage)
            total.toolCalls.addAll(round.toolCalls)
            total.providerMessages.addAll(round.providerMessages)
            return round.toolCalls.toList()
        }

        private fun appendRoundResponse(total: ProviderRound, response: String) {
            val normalized = response.trim()
            if (normalized.isEmpty()) return
            total.response += normalized
        }

        private fun toolResultMessage(results: List<com.lhstack.tools.agent.model.llm.UserContent>): Message {
            if (results.isEmpty()) {
                throw IllegalStateException("OneOrMany requires at least one item")
            }
            return Message.User(results)
        }

        private fun providerRoundResponseBody(round: ProviderRound): JsonObject {
            return JsonObject().apply {
                addProperty("response", round.response)
                add("reasoning", JsonArray().apply { round.reasoning.forEach { add(it) } })
                add("tool_calls", JsonArray().apply {
                    round.toolCalls.forEach { call ->
                        add(JsonObject().apply {
                            addProperty("tool_name", call.name)
                            call.callId?.let { addProperty("tool_call_id", it) }
                            addProperty("internal_call_id", call.id)
                            add("args", call.arguments)
                        })
                    }
                })
                add("usage", round.usage.toJson())
            }
        }
    }
}

/** 流式内容块累积，照抄 awake 的 StreamBlock。 */
private class StreamBlock(
    var blockType: String = "",
    var id: String = "",
    var name: String = "",
    var input: JsonElement = JsonObject(),
    val partialInput: StringBuilder = StringBuilder(),
) {
    fun applyDelta(delta: JsonObject?) {
        delta?.get("partial_json")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let {
            partialInput.append(it)
        }
    }

    /** 照抄 into_tool_call：优先 partial_json，否则用 start 时的 input。 */
    fun intoToolCall(): ProviderToolCall {
        val arguments = if (partialInput.isEmpty()) {
            AnthropicParser.toolInputArguments(input)
        } else {
            ModelHttpSupport.parseArgs(partialInput.toString())
        }
        return ProviderToolCall(id = id, callId = id, name = name, arguments = arguments)
    }

    companion object {
        fun fromStart(block: JsonObject): StreamBlock = StreamBlock(
            blockType = strOf(block, "type"),
            id = strOf(block, "id"),
            name = strOf(block, "name"),
            input = block.get("input") ?: JsonObject(),
        )

        private fun strOf(obj: JsonObject, key: String): String =
            obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: ""
    }
}
