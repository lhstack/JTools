package com.lhstack.tools.llm.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.lhstack.tools.db.service.SettingService
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.http.ModelHttpClientFactory
import com.lhstack.tools.agent.model.http.ModelHttpTrace
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.Usage
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn
import com.lhstack.tools.agent.model.log.ModelLogContext
import com.lhstack.tools.agent.model.log.ModelLogService
import com.lhstack.tools.agent.model.log.ModelRequestException
import com.lhstack.tools.agent.model.params.ModelParams
import com.lhstack.tools.agent.model.params.ResolvedModelConfig
import java.util.UUID

/**
 * 模型运行时执行器。完全照抄 awake-claw model_runtime.rs 的
 * execute_model_runtime_request：组装 additional_params、解析 max_tool_rounds /
 * max_retries、建模型日志、调 ModelProvider 执行、结构化输出后写日志（成功/失败）。
 *
 * 与 awake 差异仅在运行时机制：reqwest -> JDK HttpClient（ModelHttpClientFactory），
 * TraceHook 收集工具事件后交给 RunOutput.structuredValue 生成 model_log 响应体。
 *
 * 返回值对齐 awake：结构化响应 JSON + model_log_id。失败时抛 ModelRequestException
 * （携带 log_id），并已把失败信息写入日志。
 */
object ModelRuntime {

    private const val DEFAULT_MAX_TOOL_CALL_ROUNDS = 30

    /** 模型运行结果：结构化响应 + 日志 id，对齐 awake 的 (Value, i64)。 */
    data class Result(val value: JsonObject, val modelLogId: Long, val assistantMessageAt: String)

    /** 照抄 execute_model_runtime_request。 */
    fun execute(
        model: ResolvedModelConfig,
        agentMaxTurns: Int?,
        preamble: String,
        promptMessage: Message,
        history: List<Message>,
        tools: List<ToolDyn>,
        logContext: ModelLogContext,
        environmentId: Long?,
        streamed: Boolean,
        streamSink: ModelStreamSink = ModelStreamSink.NOOP,
        eventSink: ToolEventSink? = null,
        cancel: ModelCancel? = null,
        toolCancel: ModelCancel? = null,
        toolCancelSlot: java.util.concurrent.atomic.AtomicReference<ModelCancel>? = null,
        onLogCreated: ((Long) -> Unit)? = null,
        appendMessageChannel: AppendMessageChannel = AppendMessageChannel.NONE,
        continuation: ModelContinuationPort = appendMessageChannel.asContinuationPort(),
    ): Result {
        val toolDefinitions = modelLogToolDefinitions(tools)
        val additionalParams = ModelParams.additionalParams(model, environmentId)
        val maxToolRounds = resolveMaxToolRounds(model, agentMaxTurns)
        val maxRetries = resolveMaxRetries(model)
        val retryIntervalMs = resolveRetryIntervalMs()
        val httpTrace = ModelHttpTrace(UUID.randomUUID().toString())
        val recordedAppendMessages = RecordingAppendMessageChannel(appendMessageChannel)
        val modelLogId = ModelLogService.createModelLog(model, logContext, httpTrace.requestData())
        onLogCreated?.invoke(modelLogId)
        val hook = TraceHook()
        val partialOutput = PartialOutputRecorder(streamSink)
        val executor = ModelHttpClientFactory.executorFor(model.baseUrl, model.proxyUrl)

        val request = ModelProviderRequest(
            model = model,
            preamble = preamble,
            promptMessage = promptMessage,
            history = history,
            additionalParams = additionalParams,
            toolDefinitions = toolDefinitions,
            tools = tools,
            maxTurns = maxToolRounds,
            maxRetries = maxRetries,
            retryIntervalMs = retryIntervalMs,
            streamed = streamed,
            hook = hook,
            streamSink = EventForwardingStreamSink(partialOutput, eventSink),
            eventSink = eventSink,
            httpTrace = httpTrace,
            cancel = cancel,
            toolCancel = toolCancel,
            toolCancelSlot = toolCancelSlot,
            appendMessageChannel = recordedAppendMessages,
            continuation = continuation,
        )

        val output = try {
            ModelProvider.execute(executor, request)
        } catch (e: Throwable) {
            val message = e.message ?: e.toString()
            val partialResponse = partialOutput.structuredValue(
                hook,
                recordedAppendMessages.appendMessagesSnapshot(),
                completeProviderMessages(recordedAppendMessages.providerMessagesSnapshot(), hook.events()),
            )
            ModelLogService.updateModelRequestLogRequestData(modelLogId, modelRequestLogData(httpTrace, logContext))
            if (cancel?.isCancelled() == true) {
                val assistantMessageAt = ModelLogService.finishModelLogCancelled(
                    modelLogId,
                    httpTrace.responseData(partialResponse),
                )
                throw ModelRequestException(
                    modelLogId,
                    message,
                    partialResponse,
                    assistantMessageAt,
                    e,
                )
            }
            val assistantMessageAt = ModelLogService.finishModelLogError(
                modelLogId,
                httpTrace.responseData(partialResponse),
                message,
            )
            throw ModelRequestException(modelLogId, message, partialResponse, assistantMessageAt, e)
        }

        val value = output.structuredValue(hook.events()).also {
            val recordedMessages = recordedAppendMessages.appendMessagesSnapshot()
            it.add("append_messages", com.google.gson.JsonArray().apply {
                recordedMessages.forEach { message -> add(message.toJson()) }
            })
            it.add("provider_messages", ProviderMessageHistory.toJson(
                completeProviderMessages(recordedAppendMessages.providerMessagesSnapshot(), hook.events()),
            ))
        }
        ModelLogService.updateModelRequestLogRequestData(modelLogId, modelRequestLogData(httpTrace, logContext))
        val assistantMessageAt = ModelLogService.finishModelLogSuccess(modelLogId, httpTrace.responseData(value))
        return Result(value, modelLogId, assistantMessageAt)
    }

    private fun completeProviderMessages(messages: List<Message>, events: List<TraceEvent>): List<Message> {
        if (messages.isEmpty()) return emptyList()
        val completedResultIds = events.filterIsInstance<TraceEvent.ToolResult>()
            .flatMap { listOfNotNull(it.internalCallId, it.toolCallId) }
            .toSet()
        val completedCallIds = messages.asSequence()
            .filterIsInstance<Message.Assistant>()
            .flatMap { it.content.asSequence() }
            .filterIsInstance<com.lhstack.tools.llm.AssistantContent.ToolCall>()
            .map { it.toolCall }
            .filter { it.id in completedResultIds || it.callId in completedResultIds }
            .flatMap { sequenceOf(it.id, it.callId).filterNotNull() }
            .toSet()
        return messages.filter { message ->
            when (message) {
                is Message.Assistant -> message.content.none { it is com.lhstack.tools.llm.AssistantContent.ToolCall } ||
                    message.content.filterIsInstance<com.lhstack.tools.llm.AssistantContent.ToolCall>()
                        .all { it.toolCall.id in completedCallIds || it.toolCall.callId in completedCallIds }
                is Message.User -> message.content.none { it is com.lhstack.tools.llm.UserContent.ToolResult } ||
                    message.content.filterIsInstance<com.lhstack.tools.llm.UserContent.ToolResult>()
                        .all { it.toolResult.id in completedCallIds || it.toolResult.callId in completedCallIds }
                is Message.System -> true
            }
        }
    }

    /** 照抄 model_request_log_data：把 request_snapshot 并入首个请求数据。 */
    private fun modelRequestLogData(httpTrace: ModelHttpTrace, context: ModelLogContext): JsonElement =
        ModelLogService.requestLogData(
            httpTrace.requestData(),
            context.requestSnapshot,
            context.userMessageAt,
        )

    /** 照抄：agent_max_turns 优先 -> model execution_params -> 全局配置 -> 默认 30。 */
    private fun resolveMaxToolRounds(model: ResolvedModelConfig, agentMaxTurns: Int?): Int {
        agentMaxTurns?.let { return it }
        model.params.executionParams.maxToolCallRounds?.let { return it }
        return SettingService.optionalUsizeSetting("model.max_tool_call_rounds")
            ?: DEFAULT_MAX_TOOL_CALL_ROUNDS
    }

    /** 照抄：model execution_params -> 全局配置 -> 默认 0。 */
    private fun resolveMaxRetries(model: ResolvedModelConfig): Int {
        model.params.executionParams.maxRetries?.let { return it }
        return SettingService.optionalUsizeSetting("model.max_retries") ?: 0
    }

    private fun resolveRetryIntervalMs(): Long {
        val value = SettingService.setting("model.retry_interval_ms") ?: return DEFAULT_MODEL_RETRY_INTERVAL_MS
        return value.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("配置 `model.retry_interval_ms` 必须是非负整数")
    }

    /** 照抄 model_log_tool_definitions：取每个工具的定义（prompt 传空）。 */
    private fun modelLogToolDefinitions(tools: List<ToolDyn>): List<ToolDefinition> =
        tools.map { it.definition("") }

    private class EventForwardingStreamSink(
        private val delegate: ModelStreamSink,
        private val eventSink: ToolEventSink?,
    ) : ModelStreamSink {
        override fun onResponseDelta(text: String) {
            delegate.onResponseDelta(text)
            emit("response_delta", text)
        }

        override fun onReasoningDelta(text: String) {
            delegate.onReasoningDelta(text)
            emit("reasoning_delta", text)
        }

        private fun emit(eventType: String, text: String) {
            if (text.isEmpty()) return
            eventSink?.onStreamEvent(eventType, JsonObject().apply { addProperty("text", text) })
        }
    }

    private class PartialOutputRecorder(
        private val delegate: ModelStreamSink,
    ) : ModelStreamSink {
        private val lock = Any()
        private val response = StringBuilder()
        private val reasoning = StringBuilder()

        override fun onResponseDelta(text: String) {
            synchronized(lock) { response.append(text) }
            delegate.onResponseDelta(text)
        }

        override fun onReasoningDelta(text: String) {
            synchronized(lock) { reasoning.append(text) }
            delegate.onReasoningDelta(text)
        }

        fun structuredValue(
            hook: TraceHook,
            appendMessages: List<InjectedAppendMessage>,
            providerMessages: List<Message>,
        ): JsonObject? = synchronized(lock) {
            val responseText = response.toString()
            val reasoningText = reasoning.toString()
            val events = hook.events()
            if (responseText.isBlank() && reasoningText.isBlank() && events.isEmpty() && appendMessages.isEmpty()) {
                return@synchronized null
            }
            RunOutput(
                output = responseText,
                usage = Usage(),
                messages = emptyList(),
                roundMessages = providerMessages,
                reasoning = listOfNotNull(reasoningText.takeIf { it.isNotBlank() }),
                appendMessages = appendMessages,
            ).structuredValue(events).takeIf(AssistantOutputPolicy::hasOutput)
        }
    }

}
