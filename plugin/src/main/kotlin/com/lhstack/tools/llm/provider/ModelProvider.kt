package com.lhstack.tools.llm.provider


import com.google.gson.JsonElement
import com.lhstack.tools.llm.anthropic.AnthropicClient
import com.lhstack.tools.llm.anthropic.AnthropicClientParams
import com.lhstack.tools.llm.anthropic.AnthropicMessageRequest
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.http.ModelHttpExecutor
import com.lhstack.tools.agent.model.http.ModelHttpTrace
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ProviderRound
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn
import com.lhstack.tools.llm.openai.OpenAiChatRequest
import com.lhstack.tools.llm.openai.OpenAiClient
import com.lhstack.tools.llm.openai.OpenAiClientParams
import com.lhstack.tools.llm.openai.OpenAiResponsesRequest
import com.lhstack.tools.agent.model.params.OpenAiApi
import com.lhstack.tools.agent.model.params.OpenAiProviderType
import com.lhstack.tools.agent.model.params.ProviderKind
import com.lhstack.tools.agent.model.params.ResolvedModelConfig

/**
 * 模型请求。完全照抄 awake-claw model_provider.rs 的 ModelProviderRequest。
 *
 * hook / eventSink 承接 awake 的 TraceHook + emit_stream_event。cancel 承接 cancel_notify。
 */
class ModelProviderRequest(
    val model: ResolvedModelConfig,
    val preamble: String,
    val promptMessage: Message,
    val history: List<Message>,
    val additionalParams: JsonElement?,
    val toolDefinitions: List<ToolDefinition>,
    val tools: List<ToolDyn>,
    val maxTurns: Int,
    val maxRetries: Int,
    val retryIntervalMs: Long = 0,
    val streamed: Boolean,
    val hook: ToolHook = ToolHook.NOOP,
    val streamSink: ModelStreamSink = ModelStreamSink.NOOP,
    val eventSink: ToolEventSink? = null,
    val httpTrace: ModelHttpTrace,
    val cancel: ModelCancel? = null,
    val toolCancel: ModelCancel? = null,
    val toolCancelSlot: java.util.concurrent.atomic.AtomicReference<ModelCancel>? = null,
    val appendMessageChannel: AppendMessageChannel = AppendMessageChannel.NONE,
    val continuation: ModelContinuationPort = appendMessageChannel.asContinuationPort(),
)

/** 远程模型列表请求。照抄 RemoteModelsRequest。 */
class RemoteModelsRequest(
    val providerKind: ProviderKind,
    val apiKey: String,
    val baseUrl: String,
    val anthropicVersion: String?,
    val openaiProviderType: OpenAiProviderType,
    val customHeaders: Map<String, String> = emptyMap(),
)

/**
 * 模型调度器。完全照抄 awake-claw 的 execute_model_provider_request /
 * execute_openai_round / execute_anthropic_round / fetch_remote_provider_models。
 *
 * 按 provider_kind + api 分发到 OpenAI / Anthropic 客户端，执行一轮（含工具回环），
 * 结果拼成 RunOutput。
 */
object ModelProvider {

    /** 照抄 execute_model_provider_request：history + prompt 组消息，执行一轮，产出 RunOutput。 */
    fun execute(executor: ModelHttpExecutor, request: ModelProviderRequest): RunOutput {
        val messages = ArrayList(request.history)
        messages.add(request.promptMessage)
        val initialBatch = request.continuation.claimPending()
        initialBatch?.let { batch ->
            messages.add(batch.message)
            batch.claimedEvents.forEach { request.eventSink?.onStreamEvent(it.eventType, it.data) }
        }
        val toolRuntime = ToolRuntime(
            definitions = request.toolDefinitions,
            tools = request.tools,
            hook = request.hook,
            eventSink = request.eventSink,
            toolCancel = request.toolCancel,
            conversationCancel = request.cancel,
            toolCancelSlot = request.toolCancelSlot,
        )
        val round = executeProviderRound(executor, request, messages, toolRuntime)
        messages.addAll(round.providerMessages)
        val roundMessages = buildList {
            initialBatch?.historyMessage?.let(::add)
            addAll(round.historyMessages.ifEmpty { round.providerMessages })
        }
        return RunOutput(
            output = round.response,
            usage = round.usage,
            messages = messages,
            roundMessages = roundMessages,
            reasoning = round.reasoning.toList(),
            appendMessages = round.appendMessages.toList(),
        )
    }

    /** 照抄 fetch_remote_provider_models。 */
    fun fetchRemoteModels(executor: ModelHttpExecutor, request: RemoteModelsRequest): List<JsonElement> {
        return when (request.providerKind) {
            ProviderKind.OPEN_AI -> OpenAiClient(
                OpenAiClientParams(
                    executor = executor,
                    apiKey = request.apiKey,
                    baseUrl = request.baseUrl,
                    openaiProviderType = request.openaiProviderType,
                    customHeaders = request.customHeaders,
                    httpTrace = null,
                )
            ).listModels()

            ProviderKind.ANTHROPIC -> AnthropicClient(
                AnthropicClientParams(
                    executor = executor,
                    apiKey = request.apiKey,
                    baseUrl = request.baseUrl,
                    anthropicVersion = request.anthropicVersion,
                    customHeaders = request.customHeaders,
                    httpTrace = null,
                )
            ).listModels()
        }
    }

    private fun executeProviderRound(
        executor: ModelHttpExecutor,
        request: ModelProviderRequest,
        messages: List<Message>,
        toolRuntime: ToolRuntime,
    ): ProviderRound {
        return when (request.model.providerKind) {
            ProviderKind.OPEN_AI -> executeOpenAiRound(executor, request, messages, toolRuntime)
            ProviderKind.ANTHROPIC -> executeAnthropicRound(executor, request, messages, toolRuntime)
        }
    }

    /** 照抄 execute_openai_round：按 api 走 Completions / Responses，按 streamed 走流式。 */
    private fun executeOpenAiRound(
        executor: ModelHttpExecutor,
        request: ModelProviderRequest,
        messages: List<Message>,
        toolRuntime: ToolRuntime,
    ): ProviderRound {
        val client = OpenAiClient(
            OpenAiClientParams(
                executor = executor,
                apiKey = request.model.apiKey,
                baseUrl = request.model.baseUrl,
                openaiProviderType = request.model.openaiProviderType,
                customHeaders = request.model.customHeaders,
                httpTrace = request.httpTrace,
                streamSink = request.streamSink,
                eventSink = request.eventSink,
            )
        )
        return when (request.model.api) {
            OpenAiApi.COMPLETIONS -> {
                val chatRequest = OpenAiChatRequest.fromRuntime(
                    modelId = request.model.modelId,
                    preamble = request.preamble,
                    messages = messages,
                    params = request.model.params,
                    openaiProviderType = client.openaiProviderType(),
                    additionalParams = request.additionalParams,
                    tools = request.toolDefinitions,
                    stream = request.streamed,
                    maxToolRounds = request.maxTurns,
                    maxRetries = request.maxRetries,
                    retryIntervalMs = request.retryIntervalMs,
                )
                if (request.streamed) {
                    client.stream(chatRequest, toolRuntime, request.cancel, request.appendMessageChannel, request.continuation)
                } else {
                    client.chat(chatRequest, toolRuntime, request.cancel, request.appendMessageChannel, request.continuation)
                }
            }

            OpenAiApi.RESPONSES -> {
                val responsesRequest = OpenAiResponsesRequest.fromRuntime(
                    modelId = request.model.modelId,
                    preamble = request.preamble,
                    messages = messages,
                    params = request.model.params,
                    openaiProviderType = client.openaiProviderType(),
                    additionalParams = request.additionalParams,
                    tools = request.toolDefinitions,
                    stream = request.streamed,
                    maxToolRounds = request.maxTurns,
                    maxRetries = request.maxRetries,
                    retryIntervalMs = request.retryIntervalMs,
                )
                client.responses(responsesRequest, toolRuntime, request.cancel, request.appendMessageChannel, request.continuation)
            }
        }
    }

    /** 照抄 execute_anthropic_round。 */
    private fun executeAnthropicRound(
        executor: ModelHttpExecutor,
        request: ModelProviderRequest,
        messages: List<Message>,
        toolRuntime: ToolRuntime,
    ): ProviderRound {
        val client = AnthropicClient(
            AnthropicClientParams(
                executor = executor,
                apiKey = request.model.apiKey,
                baseUrl = request.model.baseUrl,
                anthropicVersion = request.model.anthropicVersion,
                customHeaders = request.model.customHeaders,
                httpTrace = request.httpTrace,
                streamSink = request.streamSink,
                eventSink = request.eventSink,
            )
        )
        val messageRequest = AnthropicMessageRequest.fromRuntime(
            modelId = request.model.modelId,
            preamble = request.preamble,
            messages = messages,
            params = request.model.params,
            additionalParams = request.additionalParams,
            tools = request.toolDefinitions,
            stream = request.streamed,
            maxToolRounds = request.maxTurns,
            maxRetries = request.maxRetries,
            retryIntervalMs = request.retryIntervalMs,
        )
        return if (request.streamed) {
            client.stream(messageRequest, toolRuntime, request.cancel, request.appendMessageChannel, request.continuation)
        } else {
            client.chat(messageRequest, toolRuntime, request.cancel, request.appendMessageChannel, request.continuation)
        }
    }
}
