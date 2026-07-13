package com.lhstack.tools.agent.model.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.lhstack.tools.db.service.SettingService
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.http.ModelHttpClientFactory
import com.lhstack.tools.agent.model.http.ModelHttpTrace
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
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
    data class Result(val value: JsonObject, val modelLogId: Long)

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
    ): Result {
        val toolDefinitions = modelLogToolDefinitions(tools)
        val additionalParams = ModelParams.additionalParams(model, environmentId)
        val maxToolRounds = resolveMaxToolRounds(model, agentMaxTurns)
        val maxRetries = resolveMaxRetries(model)
        val httpTrace = ModelHttpTrace(UUID.randomUUID().toString())
        val modelLogId = ModelLogService.createModelLog(model, logContext, httpTrace.requestData())
        val hook = TraceHook()
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
            streamed = streamed,
            hook = hook,
            streamSink = streamSink,
            eventSink = eventSink,
            httpTrace = httpTrace,
            cancel = cancel,
            toolCancel = toolCancel,
        )

        val output = try {
            ModelProvider.execute(executor, request)
        } catch (e: Throwable) {
            e.printStackTrace()
            val message = e.message ?: e.toString()
            if (cancel?.isCancelled() == true) {
                ModelLogService.updateModelRequestLogRequestData(modelLogId, modelRequestLogData(httpTrace, logContext))
                throw ModelRequestException(modelLogId, message, e)
            }
            ModelLogService.updateModelRequestLogRequestData(modelLogId, modelRequestLogData(httpTrace, logContext))
            ModelLogService.finishModelLogError(modelLogId, httpTrace.responseData(null), message)
            throw ModelRequestException(modelLogId, message, e)
        }

        val value = output.structuredValue(hook.events())
        ModelLogService.updateModelRequestLogRequestData(modelLogId, modelRequestLogData(httpTrace, logContext))
        ModelLogService.finishModelLogSuccess(modelLogId, httpTrace.responseData(value))
        return Result(value, modelLogId)
    }

    /** 照抄 model_request_log_data：把 request_snapshot 并入首个请求数据。 */
    private fun modelRequestLogData(httpTrace: ModelHttpTrace, context: ModelLogContext): JsonElement {
        val requestData = httpTrace.requestData()
        context.requestSnapshot?.let { requestData.add("request_snapshot", it.deepCopy()) }
        return requestData
    }

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

    /** 照抄 model_log_tool_definitions：取每个工具的定义（prompt 传空）。 */
    private fun modelLogToolDefinitions(tools: List<ToolDyn>): List<ToolDefinition> =
        tools.map { it.definition("") }

}
