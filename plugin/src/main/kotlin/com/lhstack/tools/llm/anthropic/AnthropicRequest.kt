package com.lhstack.tools.llm.anthropic

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.lhstack.tools.agent.model.http.ModelHttpSupport
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.agent.model.params.ModelParams
import com.lhstack.tools.agent.model.params.ModelRuntimeParams

/**
 * Anthropic Messages 请求构建。完全照抄 awake-claw anthropic.rs 的 MessageRequest 及其
 * from_runtime / body / append_messages 逻辑。
 *
 * 关键点（对齐 awake commit 8b1888a 参数修复）：
 * - max_tokens 从 model_params + additional_params 合并后 take，缺失时补 default_anthropic_max_tokens。
 * - temperature / top_k / top_p / thinking 等全部从合并后的 map 里 take_optional，
 *   不再从 params 单独字段取，model_params 是唯一真源。
 * - anthropic-beta / max_retries 是内部字段，不进请求体。
 * - tools 非空时用运行时工具，否则透传 additional 里的 tools；有 tools 且无 tool_choice 时补 auto。
 *
 * 优化说明：Rust 侧用强类型 struct，Kotlin 侧统一用 JsonObject 承接请求体，
 * 「额外字段透传」语义本质是 JSON 合并，用 JsonObject 表达更贴合且不丢字段。
 */
internal class AnthropicMessageRequest(
    private val model: String,
    private val maxTokens: Long,
    private val messages: JsonArray,
    private val system: JsonElement?,
    private val fields: JsonObject,
    private val additionalParams: JsonObject,
    private val anthropicBeta: String?,
    val maxRetries: Int,
    val retryIntervalMs: Long,
    val maxToolRounds: Int,
    var initialContinuationBatchId: String? = null,
) {

    fun anthropicBeta(): String? = anthropicBeta

    /** 照抄 body：拼装最终请求体，additional_params 打平进根对象。 */
    fun body(): JsonObject {
        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("max_tokens", maxTokens)
        body.add("messages", messages)
        system?.let { body.add("system", it) }
        body.addProperty("stream", fields.get("stream")?.asBoolean ?: false)
        // 已 take 的可选字段（去 null）依序写入
        for (key in OPTIONAL_BODY_KEYS) {
            fields.get(key)?.takeIf { !it.isJsonNull }?.let { body.add(key, it) }
        }
        // additional_params 打平透传（不覆盖已写入的强控字段）
        for ((key, value) in additionalParams.entrySet()) {
            if (!body.has(key)) {
                body.add(key, value)
            }
        }
        return body
    }

    /** 照抄 append_messages：把新消息转成 Anthropic message param 追加。 */
    fun appendMessages(newMessages: List<Message>) {
        AnthropicMessages.messageParamsFromMessages(newMessages).forEach { messages.add(it) }
    }

    companion object {
        private val OPTIONAL_BODY_KEYS = listOf(
            "metadata",
            "stop_sequences",
            "temperature",
            "thinking",
            "tool_choice",
            "tools",
            "top_k",
            "top_p",
            "service_tier",
            "container",
            "inference_geo",
            "output_config",
            "cache_control",
        )

        /** 照抄 MessageRequest::from_runtime。 */
        fun fromRuntime(
            modelId: String,
            preamble: String,
            messages: List<Message>,
            params: ModelRuntimeParams,
            additionalParams: JsonElement?,
            tools: List<ToolDefinition>,
            stream: Boolean,
            maxToolRounds: Int,
            maxRetries: Int,
            retryIntervalMs: Long = 0,
        ): AnthropicMessageRequest {
            val additional = com.lhstack.tools.llm.LlmJson.mergedModelAndAdditionalParams(
                params.modelParams,
                additionalParams,
            )
            val maxTokens = ModelHttpSupport.takeOptional(additional, "max_tokens")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                ?: ModelParams.defaultAnthropicMaxTokens()

            val (system, messageParams) = AnthropicMessages.messagesFromRuntime(preamble, messages)

            val requestTools = if (tools.isEmpty()) {
                ModelHttpSupport.takeOptional(additional, "tools")?.takeIf { it.isJsonArray }?.asJsonArray
            } else {
                AnthropicMessages.functionTools(tools)
            }

            // 逐字段 take（去 null 由 body() 统一处理）；顺序与 awake 一致
            val fields = JsonObject()
            fields.addProperty("stream", stream)
            takeInto(fields, additional, "metadata")
            takeInto(fields, additional, "stop_sequences")
            takeInto(fields, additional, "temperature")
            takeInto(fields, additional, "thinking")
            fields.get("thinking")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::normalizeThinking)
            val toolChoice = ModelHttpSupport.takeOptional(additional, "tool_choice")
            takeInto(fields, additional, "top_k")
            takeInto(fields, additional, "top_p")
            takeInto(fields, additional, "service_tier")
            takeInto(fields, additional, "container")
            takeInto(fields, additional, "inference_geo")
            takeInto(fields, additional, "output_config")
            takeInto(fields, additional, "cache_control")
            val anthropicBeta = ModelHttpSupport.takeOptional(additional, "anthropic-beta")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val optionsMaxRetries = ModelHttpSupport.takeOptional(additional, "max_retries")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

            requestTools?.let { fields.add("tools", it) }
            // 有 tools 且无 tool_choice 时补 auto；tool_choice 是对象但缺 type 时补 type=auto
            val resolvedToolChoice = resolveToolChoice(toolChoice, requestTools != null)
            resolvedToolChoice?.let { fields.add("tool_choice", it) }

            val systemElement = if (system.size() > 0) system else null

            return AnthropicMessageRequest(
                model = modelId,
                maxTokens = maxTokens,
                messages = JsonArray().apply { messageParams.forEach { add(it) } },
                system = systemElement,
                fields = fields,
                additionalParams = additional,
                anthropicBeta = anthropicBeta,
                maxRetries = optionsMaxRetries ?: maxRetries,
                retryIntervalMs = retryIntervalMs,
                maxToolRounds = maxToolRounds,
            )
        }


        private fun normalizeThinking(thinking: JsonObject) {
            val type = thinking.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: throw IllegalArgumentException("Anthropic thinking.type 必须是 enabled、disabled 或 adaptive")
            when (type) {
                "disabled" -> {
                    thinking.remove("budget_tokens")
                    thinking.remove("display")
                }
                "enabled", "adaptive" -> {
                    val budget = thinking.get("budget_tokens")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                        ?.asLong
                    if (budget == null || budget < 1024) {
                        throw IllegalArgumentException("开启 Anthropic thinking 后 budget_tokens 不能小于 1024")
                    }
                    val display = thinking.get("display")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    if (display != "summarized" && display != "omitted") {
                        throw IllegalArgumentException("开启 Anthropic thinking 后 display 必须是 summarized 或 omitted")
                    }
                }
                else -> throw IllegalArgumentException("Anthropic thinking.type 必须是 enabled、disabled 或 adaptive")
            }
        }

        private fun takeInto(target: JsonObject, source: JsonObject, key: String) {
            val value = ModelHttpSupport.takeOptional(source, key) ?: return
            target.add(key, value)
        }

        /**
         * 归一化 tool_choice：
         * - 未设置：有 tools 时补 auto，否则不发。
         * - 是对象但缺 type（如 UI 只设了 disable_parallel_tool_use）：补 type=auto，避免发出空 type。
         * - 其余情况原样保留。
         */
        private fun resolveToolChoice(toolChoice: JsonElement?, hasTools: Boolean): JsonElement? {
            if (toolChoice == null || toolChoice.isJsonNull) {
                return if (hasTools) toolChoiceAuto() else null
            }
            if (toolChoice.isJsonObject) {
                val obj = toolChoice.asJsonObject
                val type = obj.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (type.isNullOrBlank()) {
                    obj.addProperty("type", "auto")
                }
            }
            return toolChoice
        }

        /** 照抄 ToolChoice::auto。 */
        private fun toolChoiceAuto(): JsonObject = JsonObject().apply {
            addProperty("type", "auto")
        }
    }
}
