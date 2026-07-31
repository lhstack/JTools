package com.lhstack.tools.agent.model.openai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import com.lhstack.tools.agent.model.params.OpenAiProviderType
import com.lhstack.tools.agent.model.provider.AppendMessage
import com.lhstack.tools.agent.model.provider.AppendMessageChannel
import com.lhstack.tools.agent.model.provider.ModelStreamSink
import com.lhstack.tools.agent.model.provider.ToolRuntime
import com.lhstack.tools.agent.model.provider.toUserMessage

/**
 * OpenAI 客户端。完全照抄 awake-claw openai.rs 的 OpenAiClient。
 *
 * 覆盖 Chat Completions / Responses 两种 API，各支持 stream / 非 stream，
 * 工具回环、重试、SSE 增量、reasoning、usage 全部照抄。
 *
 * 与 awake 差异仅在运行时机制：
 * - reqwest 异步 -> JDK11 HttpClient 阻塞（由 ModelHttpExecutor 承接）。
 * - tokio::select 取消 -> ModelCancel 关流中断。
 * - emit_stream_event 线程局部 writer -> ModelStreamSink 显式回调。
 * 请求构建、字段筛选、解析逻辑与 awake 一一对应。
 */
class OpenAiClientParams(
    val executor: ModelHttpExecutor,
    val apiKey: String,
    val baseUrl: String,
    val openaiProviderType: OpenAiProviderType,
    val customHeaders: Map<String, String> = emptyMap(),
    val httpTrace: ModelHttpTrace?,
    val streamSink: ModelStreamSink = ModelStreamSink.NOOP,
)

class OpenAiClient(private val params: OpenAiClientParams) {

    private val executor = params.executor
    private val apiKey = params.apiKey
    private val baseUrl = params.baseUrl
    private val httpTrace = params.httpTrace
    private val streamSink = params.streamSink

    fun openaiProviderType(): OpenAiProviderType = params.openaiProviderType

    // -------- chat completions --------

    /** 照抄 chat：非流式工具回环。 */
    internal fun chat(
        request: OpenAiChatRequest,
        toolRuntime: ToolRuntime,
        cancel: ModelCancel?,
        appendMessageChannel: AppendMessageChannel,
    ): ProviderRound {
        val total = ProviderRound()
        var rounds = 0
        while (true) {
            throwIfCancelled(cancel)
            val round = chatOnce(request, cancel)
            emitRoundText(round)
            val loop = continueChatToolLoop(request, total, round, rounds, toolRuntime, cancel, appendMessageChannel)
            if (!loop.continueLoop) {
                return total
            }
            rounds = loop.rounds
        }
    }

    /** 照抄 stream：流式工具回环。 */
    internal fun stream(
        request: OpenAiChatRequest,
        toolRuntime: ToolRuntime,
        cancel: ModelCancel?,
        appendMessageChannel: AppendMessageChannel,
    ): ProviderRound {
        val total = ProviderRound()
        var rounds = 0
        while (true) {
            throwIfCancelled(cancel)
            val round = chatStream(chatUrl(), request.body, request.maxRetries, cancel)
            val loop = continueChatToolLoop(request, total, round, rounds, toolRuntime, cancel, appendMessageChannel)
            if (!loop.continueLoop) {
                return total
            }
            rounds = loop.rounds
        }
    }

    // -------- responses --------

    /** 照抄 responses：Responses API，按 stream 标志走流式或非流式，工具回环。 */
    internal fun responses(
        request: OpenAiResponsesRequest,
        toolRuntime: ToolRuntime,
        cancel: ModelCancel?,
        appendMessageChannel: AppendMessageChannel,
    ): ProviderRound {
        val total = ProviderRound()
        var rounds = 0
        while (true) {
            throwIfCancelled(cancel)
            val round = if (request.stream) {
                responsesStream(responsesUrl(), request.body, request.maxRetries, cancel)
            } else {
                val value = postJsonWithRetries(
                    "/responses",
                    request.body,
                    request.maxRetries,
                    "OpenAI Responses API",
                    cancel,
                )
                OpenAiParser.parseResponse(value)
            }
            if (!request.stream) emitRoundText(round)
            val loop = continueResponsesToolLoop(request, total, round, rounds, toolRuntime, cancel, appendMessageChannel)
            if (!loop.continueLoop) {
                return total
            }
            rounds = loop.rounds
        }
    }

    // -------- list models --------

    /** 照抄 list_models。 */
    fun listModels(): List<JsonElement> {
        val text = try {
            executor.getForText(
                ModelHttpSupport.joinUrl(baseUrl, "/models"),
                mapOf("Authorization" to "Bearer $apiKey"),
                null,
            )
        } catch (e: ModelHttpStatusException) {
            throw IllegalStateException("model API returned ${e.status}: ${e.bodyText}")
        }
        val value = JsonParser.parseString(text)
        val data = value.takeIf { it.isJsonObject }?.asJsonObject?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalStateException("OpenAI /models 响应缺少 data 数组")
        return data.mapNotNull { openaiRemoteModel(it) }
    }

    // -------- internal request helpers --------

    private fun chatOnce(request: OpenAiChatRequest, cancel: ModelCancel?): ProviderRound {
        val value = postJsonWithRetries(
            "/chat/completions",
            request.body,
            request.maxRetries,
            "OpenAI Chat Completions API",
            cancel,
        )
        return OpenAiParser.parseChatResponse(value)
    }

    /** 照抄 post_json_with_retries：单次失败累计重试信息，超过阈值抛错。取消不重试。 */
    private fun postJsonWithRetries(
        path: String,
        body: JsonObject,
        maxRetries: Int,
        apiName: String,
        cancel: ModelCancel?,
    ): JsonElement {
        val url = ModelHttpSupport.joinUrl(baseUrl, path)
        var retryAttempt = 0
        val failures = mutableListOf<String>()
        while (true) {
            try {
                return postJsonOnce(url, body, cancel)
            } catch (e: ModelRequestCancelledException) {
                throw e
            } catch (e: Throwable) {
                throwIfCancelled(cancel)
                retryAttempt += 1
                failures.add("第 $retryAttempt 次重试判定，异常: ${e.message ?: e.toString()}")
                if (retryAttempt <= maxRetries) {
                    httpTrace?.retry()
                    continue
                }
                throw IllegalStateException("调用 $apiName 失败:\n${failures.joinToString("\n")}")
            }
        }
    }

    /** 照抄 post_json_once：序列化去 null、trace 记录、非成功状态带响应体报错。 */
    private fun postJsonOnce(url: String, body: JsonObject, cancel: ModelCancel?): JsonElement {
        val serialized = ModelHttpSupport.serializedRequestBody(body)
        httpTrace?.request(url, serialized)
        val text = try {
            executor.sendJson(url, authHeaders(), serialized.toString(), cancel)
        } catch (e: ModelHttpStatusException) {
            httpTrace?.error(url, e.status.toString(), "model API returned non-success status", com.google.gson.JsonPrimitive(e.bodyText))
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
            httpTrace?.response(url, com.google.gson.JsonPrimitive(text))
            httpTrace?.error(url, null, e.message ?: e.toString(), null)
            throw e
        }
        httpTrace?.response(url, value)
        return value
    }

    private fun authHeaders(): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        headers.putAll(params.customHeaders)
        // 系统鉴权优先，避免自定义头覆盖 Authorization。
        headers["Authorization"] = "Bearer $apiKey"
        return headers
    }

    private fun chatUrl(): String = ModelHttpSupport.joinUrl(baseUrl, "/chat/completions")
    private fun responsesUrl(): String = ModelHttpSupport.joinUrl(baseUrl, "/responses")

    // -------- chat stream --------

    private fun chatStream(
        url: String,
        body: JsonObject,
        maxRetries: Int,
        cancel: ModelCancel?,
    ): ProviderRound {
        var retryAttempt = 0
        val failures = mutableListOf<String>()
        while (true) {
            try {
                return chatStreamOnce(url, body, cancel)
            } catch (e: ModelRequestCancelledException) {
                throw e
            } catch (e: Throwable) {
                throwIfCancelled(cancel)
                retryAttempt += 1
                failures.add("第 $retryAttempt 次重试判定，异常: ${e.message ?: e.toString()}")
                if (retryAttempt <= maxRetries) {
                    httpTrace?.retry()
                    continue
                }
                throw IllegalStateException("调用 OpenAI Chat Completions stream API 失败:\n${failures.joinToString("\n")}")
            }
        }
    }

    private fun chatStreamOnce(url: String, body: JsonObject, cancel: ModelCancel?): ProviderRound {
        val parser = openStream(url, body, cancel)
        val responseText = StringBuilder()
        val reasoning = mutableListOf<String>()
        var usage = Usage()
        val calls = sortedMapOf<Int, StreamToolCall>()
        parser.use {
            while (true) {
                throwIfCancelled(cancel)
                val event = parser.next() ?: break
                if (event.data == "[DONE]") break
                val value = try {
                    JsonParser.parseString(event.data)
                } catch (e: Throwable) {
                    httpTrace?.error(url, null, e.message ?: e.toString(), null)
                    throw e
                }
                streamErrorMessage(value)?.let { message ->
                    httpTrace?.error(url, null, message, null)
                    throw IllegalStateException(message)
                }
                val delta = value.takeIf { it.isJsonObject }?.asJsonObject
                    ?.getAsJsonArray("choices")?.firstOrNull()
                    ?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("delta")
                if (delta != null) {
                    collectChatDelta(delta, responseText, reasoning, calls)
                }
                value.takeIf { it.isJsonObject }?.asJsonObject?.get("usage")
                    ?.takeIf { !it.isJsonNull }
                    ?.let { usage = OpenAiParser.chatUsage(it) }
            }
        }
        throwIfCancelled(cancel)
        val round = roundFromParts(responseText.toString(), reasoning, calls, usage)
        traceRoundResponse(url, round)
        return round
    }

    /** 照抄 collect_chat_delta：content / reasoning* / tool_calls 增量聚合并回调。 */
    private fun collectChatDelta(
        delta: JsonObject,
        responseText: StringBuilder,
        reasoning: MutableList<String>,
        calls: MutableMap<Int, StreamToolCall>,
    ) {
        stringField(delta, "content")?.let {
            responseText.append(it)
            streamSink.onResponseDelta(it)
        }
        for (key in listOf("reasoning_content", "reasoning_text", "reasoning")) {
            stringField(delta, key)?.let {
                OpenAiParser.appendReasoningDelta(reasoning, it)
                streamSink.onReasoningDelta(it)
            }
        }
        val toolCalls = delta.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (call in toolCalls) {
            if (!call.isJsonObject) continue
            val obj = call.asJsonObject
            val index = obj.get("index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
            val entry = calls.getOrPut(index) { StreamToolCall() }
            stringField(obj, "id")?.takeIf { it.isNotEmpty() }?.let { entry.id = it }
            val function = obj.get("function")?.takeIf { it.isJsonObject }?.asJsonObject
            function?.let { fn ->
                stringField(fn, "name")?.takeIf { it.isNotEmpty() }?.let { entry.name = it }
                stringField(fn, "arguments")?.let { entry.arguments.append(it) }
            }
        }
    }

    // -------- responses stream --------

    private fun responsesStream(
        url: String,
        body: JsonObject,
        maxRetries: Int,
        cancel: ModelCancel?,
    ): ProviderRound {
        var retryAttempt = 0
        val failures = mutableListOf<String>()
        while (true) {
            try {
                return responsesStreamOnce(url, body, cancel)
            } catch (e: ModelRequestCancelledException) {
                throw e
            } catch (e: Throwable) {
                throwIfCancelled(cancel)
                retryAttempt += 1
                failures.add("第 $retryAttempt 次重试判定，异常: ${e.message ?: e.toString()}")
                if (retryAttempt <= maxRetries) {
                    httpTrace?.retry()
                    continue
                }
                throw IllegalStateException("调用 OpenAI Responses stream API 失败:\n${failures.joinToString("\n")}")
            }
        }
    }

    private fun responsesStreamOnce(url: String, body: JsonObject, cancel: ModelCancel?): ProviderRound {
        val parser = openStream(url, body, cancel)
        val text = StringBuilder()
        val reasoning = mutableListOf<String>()
        var rawFinal: JsonElement = com.google.gson.JsonNull.INSTANCE
        val outputItems = sortedMapOf<Long, JsonElement>()
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
                streamErrorMessage(value)?.let { message ->
                    httpTrace?.error(url, null, message, null)
                    throw IllegalStateException(message)
                }
                val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                when (stringField(obj, "type") ?: "") {
                    "response.output_text.delta" -> pushTextDelta(text, obj)
                    "response.reasoning_summary_text.delta" -> pushReasoningDelta(reasoning, obj)
                    "response.output_item.done" -> collectDoneOutputItem(obj, outputItems)
                    "response.completed" -> {
                        rawFinal = completedStreamResponse(obj)
                    }
                }
            }
        }
        throwIfCancelled(cancel)
        if (rawFinal.isJsonObject) {
            mergeStreamOutputItems(rawFinal.asJsonObject, outputItems)
            val round = OpenAiParser.parseResponse(rawFinal)
            if (text.isNotEmpty()) round.response = text.toString()
            if (reasoning.isNotEmpty()) {
                round.reasoning.clear()
                round.reasoning.addAll(reasoning)
            }
            traceRoundResponse(url, round)
            return round
        }
        if (outputItems.isNotEmpty()) {
            val wrapper = JsonObject().apply {
                add("output", JsonArray().apply { outputItems.values.forEach { add(it) } })
            }
            val round = OpenAiParser.parseResponse(wrapper)
            if (text.isNotEmpty()) round.response = text.toString()
            if (reasoning.isNotEmpty()) {
                round.reasoning.clear()
                round.reasoning.addAll(reasoning)
            }
            traceRoundResponse(url, round)
            return round
        }
        val round = ProviderRound(response = text.toString())
        round.reasoning.addAll(reasoning)
        traceRoundResponse(url, round)
        return round
    }

    private fun pushTextDelta(text: StringBuilder, value: JsonObject) {
        stringField(value, "delta")?.let {
            text.append(it)
            streamSink.onResponseDelta(it)
        }
    }

    private fun pushReasoningDelta(reasoning: MutableList<String>, value: JsonObject) {
        OpenAiParser.responseReasoningText(value)?.let {
            OpenAiParser.appendReasoningDelta(reasoning, it)
            streamSink.onReasoningDelta(it)
        }
    }

    /** 照抄 completed_stream_response：只取 status=completed 的 response。 */
    private fun completedStreamResponse(value: JsonObject): JsonElement {
        val response = value.get("response")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return com.google.gson.JsonNull.INSTANCE
        if (stringField(response, "status") != "completed") {
            return com.google.gson.JsonNull.INSTANCE
        }
        return response
    }

    private fun collectDoneOutputItem(value: JsonObject, outputItems: MutableMap<Long, JsonElement>) {
        val item = value.get("item")?.takeIf { it.isJsonObject } ?: return
        val index = value.get("output_index")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            ?: outputItems.size.toLong()
        outputItems[index] = item
    }

    private fun mergeStreamOutputItems(rawFinal: JsonObject, outputItems: Map<Long, JsonElement>) {
        if (outputItems.isEmpty()) return
        val outputEmpty = rawFinal.get("output")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it == 0 } ?: true
        if (outputEmpty) {
            rawFinal.add("output", JsonArray().apply { outputItems.values.forEach { add(it) } })
        }
    }

    // -------- stream open + trace --------

    private fun openStream(url: String, body: JsonObject, cancel: ModelCancel?): SseParser {
        val serialized = ModelHttpSupport.serializedRequestBody(body)
        httpTrace?.request(url, serialized)
        return try {
            executor.sendJsonStream(url, authHeaders(), serialized.toString(), cancel)
        } catch (e: ModelHttpStatusException) {
            httpTrace?.error(url, e.status.toString(), "model API returned non-success status", com.google.gson.JsonPrimitive(e.bodyText))
            throw IllegalStateException("model API returned ${e.status}: ${e.bodyText}")
        } catch (e: ModelRequestCancelledException) {
            throw e
        } catch (e: Throwable) {
            httpTrace?.error(url, null, e.message ?: e.toString(), null)
            throw e
        }
    }

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

    // -------- tool loops --------

    private data class LoopResult(val continueLoop: Boolean, val rounds: Int)

    private fun continueChatToolLoop(
        request: OpenAiChatRequest,
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
            return continueWithPendingAppendsOrStop(request::appendMessages, total, rounds, appendMessageChannel)
        }
        val nextRounds = rounds + 1
        if (nextRounds > request.maxToolRounds) {
            throw IllegalStateException("工具回环超过最大轮次 ${request.maxToolRounds}")
        }
        val toolMessage = toolResultMessage(toolRuntime.executeToolCalls(toolCalls))
        throwIfCancelled(cancel)
        val appendedMessages = appendMessageChannel.fetch()
        val assistantMessage = total.providerMessages.last()
        val appended = buildList {
            add(assistantMessage)
            add(toolMessage)
            addAll(appendedMessages.map { it.toUserMessage() })
        }
        request.appendMessages(appended)
        total.providerMessages.add(toolMessage)
        total.providerMessages.addAll(appendedMessages.map { it.toUserMessage() })
        appendMessageChannel.onProviderMessages(buildList {
            add(assistantMessage)
            add(toolMessage)
            addAll(appendedMessages.map { it.toUserMessage() })
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

    private fun continueResponsesToolLoop(
        request: OpenAiResponsesRequest,
        total: ProviderRound,
        round: ProviderRound,
        rounds: Int,
        toolRuntime: ToolRuntime,
        cancel: ModelCancel?,
        appendMessageChannel: AppendMessageChannel,
    ): LoopResult {
        val toolCalls = mergeProviderRound(total, round)
        if (toolCalls.isEmpty()) {
            return continueWithPendingAppendsOrStop(request::appendMessages, total, rounds, appendMessageChannel)
        }
        throwIfCancelled(cancel)
        val nextRounds = rounds + 1
        if (nextRounds > request.maxToolRounds) {
            throw IllegalStateException("工具回环超过最大轮次 ${request.maxToolRounds}")
        }
        val toolMessage = toolResultMessage(toolRuntime.executeToolCalls(toolCalls))
        throwIfCancelled(cancel)
        val appendedMessages = appendMessageChannel.fetch()
        val assistantMessage = total.providerMessages.last()
        val appended = buildList {
            add(assistantMessage)
            add(toolMessage)
            addAll(appendedMessages.map { it.toUserMessage() })
        }
        request.appendMessages(appended)
        total.providerMessages.add(toolMessage)
        total.providerMessages.addAll(appendedMessages.map { it.toUserMessage() })
        appendMessageChannel.onProviderMessages(buildList {
            add(assistantMessage)
            add(toolMessage)
            addAll(appendedMessages.map { it.toUserMessage() })
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
        appendMessages: (List<Message>) -> Unit,
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
        appendMessages(buildList {
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
        /** 照抄 openai_remote_model。 */
        private fun openaiRemoteModel(item: JsonElement): JsonElement? {
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
            return JsonObject().apply {
                addProperty("id", id)
                val displayName = (obj.get("display_name") ?: obj.get("name"))
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (displayName != null) addProperty("display_name", displayName) else add("display_name", com.google.gson.JsonNull.INSTANCE)
                obj.get("object")?.takeIf { it.isJsonPrimitive }?.let { add("object", it) }
                obj.get("created")?.takeIf { it.isJsonPrimitive }?.let { add("created", it) }
                obj.get("owned_by")?.takeIf { it.isJsonPrimitive }?.let { add("owned_by", it) }
            }
        }

        /** 照抄 merge_provider_round：累计 response/reasoning/usage/tool_calls/messages，返回本轮 tool_calls。 */
        private fun mergeProviderRound(total: ProviderRound, round: ProviderRound): List<ProviderToolCall> {
            appendRoundResponse(total, round.response)
            total.reasoning.addAll(round.reasoning)
            total.usage.add(round.usage)
            total.toolCalls.addAll(round.toolCalls)
            total.providerMessages.addAll(round.providerMessages)
            total.appendMessages.addAll(round.appendMessages)
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

        /** 照抄 round_from_parts：流式片段拼装成 ProviderRound。 */
        private fun roundFromParts(
            text: String,
            reasoning: List<String>,
            calls: Map<Int, StreamToolCall>,
            usage: Usage,
        ): ProviderRound {
            val providerCalls = calls.values.map { call ->
                ProviderToolCall(
                    id = call.id,
                    callId = call.id,
                    name = call.name,
                    arguments = ModelHttpSupport.parseArgs(call.arguments.toString()),
                )
            }
            val round = ProviderRound(response = text)
            round.reasoning.addAll(reasoning)
            round.toolCalls.addAll(providerCalls)
            round.providerMessages.add(OpenAiParser.assistantMessage(text, providerCalls))
            round.usage.add(usage)
            return round
        }

        /** 照抄 stream_error_message。 */
        private fun streamErrorMessage(value: JsonElement): String? {
            val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val error = obj.get("error")?.takeIf { !it.isJsonNull }
                ?: if (stringField(obj, "type") == "error") obj.get("error") else null
                ?: return null
            if (!error.isJsonObject) return error.toString()
            val errorObj = error.asJsonObject
            val message = errorObj.get("message")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (message != null) {
                val errorType = errorObj.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                return if (!errorType.isNullOrEmpty()) "$errorType: $message" else message
            }
            return error.toString()
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

        private fun stringField(obj: JsonObject, key: String): String? {
            val value = obj.get(key) ?: return null
            return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
        }
    }
}

/** 流式工具调用累积块，对齐 awake 的 StreamToolCall。 */
private class StreamToolCall(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
)
