package com.lhstack.tools.llm.openai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.lhstack.tools.agent.model.http.ModelHttpSupport
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.agent.model.params.ModelRuntimeParams
import com.lhstack.tools.agent.model.params.OpenAiProviderType

/**
 * OpenAI 请求构建。完全照抄 awake-claw openai.rs 的 ChatRequest / ResponsesRequest 及其
 * from_runtime、options from_params、remove helpers、tools、include 逻辑。
 *
 * 优化说明（借助 Java/Gson 特性）：Rust 侧用强类型 struct + serde flatten 承接请求体，
 * Kotlin 侧统一用 JsonObject 承接。因为「model_params 是唯一真源、多余字段透传」的语义
 * 本质就是 JSON 合并，用 JsonObject 直接表达更贴合且不丢字段。字段筛选、去 null、
 * body-controlled 字段剔除全部照抄 awake 的行为。
 */

/** OpenAI Chat 请求，对齐 ChatRequest。body 为已构建好的请求 JSON。 */
internal class OpenAiChatRequest(
    val body: JsonObject,
    val maxRetries: Int,
    val retryIntervalMs: Long,
    val maxToolRounds: Int,
    var initialContinuationBatchId: String? = null,
) {
    /** 照抄 append_messages：把新消息追加进 body.messages。 */
    fun appendMessages(messages: List<Message>) {
        val arr = body.getAsJsonArray("messages")
        for (message in messages) {
            OpenAiMessages.chatMessage(message).forEach { arr.add(it) }
        }
    }

    companion object {
        /** 照抄 ChatRequest::from_runtime。 */
        fun fromRuntime(
            modelId: String,
            preamble: String,
            messages: List<Message>,
            params: ModelRuntimeParams,
            openaiProviderType: OpenAiProviderType,
            additionalParams: JsonElement?,
            tools: List<ToolDefinition>,
            stream: Boolean,
            maxToolRounds: Int,
            maxRetries: Int,
            retryIntervalMs: Long = 0,
        ): OpenAiChatRequest {
            val options = optionsFromParams(
                params.modelParams,
                additionalParams,
                openaiProviderType,
                controlledKeys = CHAT_CONTROLLED_KEYS,
            )
            val optionsMaxRetries = takeInt(options, "max_retries")
            val requestTools = if (tools.isEmpty()) {
                options.remove("tools")?.takeIf { !it.isJsonNull }?.asJsonArray
            } else {
                OpenAiMessages.chatTools(tools)
            }
            val existingToolChoice = options.remove("tool_choice")?.takeIf { !it.isJsonNull }
            val toolChoice = if (requestTools != null && existingToolChoice == null) {
                JsonPrimitive("auto")
            } else {
                existingToolChoice
            }

            val body = JsonObject()
            body.add("messages", OpenAiMessages.chatMessages(preamble, messages))
            body.addProperty("model", modelId)
            body.addProperty("stream", stream)
            requestTools?.let { body.add("tools", it) }
            toolChoice?.let { body.add("tool_choice", it) }
            // 其余用户参数（去 null、去 body-controlled 后）原样透传
            for ((key, value) in options.entrySet()) {
                if (key == "stream" || key == "messages" || key == "model") continue
                body.add(key, value)
            }
            return OpenAiChatRequest(body, optionsMaxRetries ?: maxRetries, retryIntervalMs, maxToolRounds)
        }
    }
}

/** OpenAI Responses 请求，对齐 ResponsesRequest。 */
internal class OpenAiResponsesRequest(
    val body: JsonObject,
    val maxRetries: Int,
    val retryIntervalMs: Long,
    val maxToolRounds: Int,
    var initialContinuationBatchId: String? = null,
) {
    val stream: Boolean
        get() = body.get("stream")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    /** 照抄 append_messages：把新消息追加进 body.input 数组。 */
    fun appendMessages(messages: List<Message>) {
        val input = body.get("input")
        if (input == null || !input.isJsonArray) {
            throw IllegalStateException("OpenAI responses request input must be an array during tool loop")
        }
        OpenAiMessages.responseInput(messages).forEach { input.asJsonArray.add(it) }
    }

    companion object {
        fun fromRuntime(
            modelId: String,
            preamble: String,
            messages: List<Message>,
            params: ModelRuntimeParams,
            openaiProviderType: OpenAiProviderType,
            additionalParams: JsonElement?,
            tools: List<ToolDefinition>,
            stream: Boolean,
            maxToolRounds: Int,
            maxRetries: Int,
            retryIntervalMs: Long = 0,
        ): OpenAiResponsesRequest {
            val options = optionsFromParams(
                params.modelParams,
                additionalParams,
                openaiProviderType,
                controlledKeys = RESPONSES_CONTROLLED_KEYS,
            )
            // 照抄：reasoning.generate_summary 已废弃
            val reasoning = options.get("reasoning")?.takeIf { it.isJsonObject }?.asJsonObject
            if (reasoning?.has("generate_summary") == true) {
                throw IllegalArgumentException(
                    "OpenAI responses reasoning.generate_summary 已废弃，请使用 reasoning.summary"
                )
            }
            val optionsMaxRetries = takeInt(options, "max_retries")
            val requestTools = if (tools.isEmpty()) {
                options.remove("tools")?.takeIf { !it.isJsonNull }?.asJsonArray
            } else {
                OpenAiMessages.responseTools(tools)
            }
            val existingToolChoice = options.remove("tool_choice")?.takeIf { !it.isJsonNull }
            val toolChoice = if (requestTools != null && existingToolChoice == null) {
                JsonPrimitive("auto")
            } else {
                existingToolChoice
            }
            val includeInput = options.remove("include")?.takeIf { it.isJsonArray }?.asJsonArray
            val include = responsesInclude(includeInput, reasoning)

            val body = JsonObject()
            body.addProperty("model", modelId)
            body.addProperty("stream", stream)
            body.add("input", JsonArray().apply { OpenAiMessages.responseInput(messages).forEach { add(it) } })
            // instructions：preamble 非空则覆盖 options.instructions
            val instructions = if (preamble.trim().isNotEmpty()) {
                JsonPrimitive(preamble)
            } else {
                options.remove("instructions")?.takeIf { !it.isJsonNull }
            }
            instructions?.let { body.add("instructions", it) }
            include?.let { body.add("include", it) }
            requestTools?.let { body.add("tools", it) }
            toolChoice?.let { body.add("tool_choice", it) }
            for ((key, value) in options.entrySet()) {
                if (key == "stream" || key == "input" || key == "model" ||
                    key == "instructions" || key == "include"
                ) continue
                body.add(key, value)
            }
            return OpenAiResponsesRequest(body, optionsMaxRetries ?: maxRetries, retryIntervalMs, maxToolRounds)
        }
    }
}

/**
 * 照抄 options from_params + remove_null_request_params + remove_compatible_only_model_params +
 * remove_*_body_controlled_params：
 * 1. model_params 去 null、去 compatible-only 字段（official 时移除 thinking/output_config）
 * 2. additional_params 去 null
 * 3. additional 覆盖 model_params
 * 4. 移除 body 强控字段（messages/model/stream 或 input/model/stream）
 */
private fun optionsFromParams(
    modelParams: JsonElement?,
    additionalParams: JsonElement?,
    openaiProviderType: OpenAiProviderType,
    controlledKeys: List<String>,
): JsonObject {
    val merged = com.lhstack.tools.llm.LlmJson.mergedModelAndAdditionalParams(modelParams, additionalParams)
    removeNullRequestParams(merged)
    removeCompatibleOnlyModelParams(merged, openaiProviderType)
    controlledKeys.forEach { merged.remove(it) }
    return merged
}

/** 照抄 remove_null_request_params。 */
private fun removeNullRequestParams(params: JsonObject) {
    val nullKeys = params.entrySet().filter { it.value.isJsonNull }.map { it.key }
    nullKeys.forEach { params.remove(it) }
}

/** 照抄 remove_compatible_only_model_params：official 时移除 thinking/output_config。 */
private fun removeCompatibleOnlyModelParams(params: JsonObject, type: OpenAiProviderType) {
    if (type == OpenAiProviderType.COMPATIBLE) return
    params.remove("thinking")
    params.remove("output_config")
}

/** 照抄 responses_include：reasoning 用 token 时追加 reasoning.encrypted_content。 */
private fun responsesInclude(include: JsonArray?, reasoning: JsonObject?): JsonArray? {
    if (!responsesReasoningUsesTokens(reasoning)) {
        return include
    }
    val values = include ?: JsonArray()
    val exists = values.any { it.isJsonPrimitive && it.asString == "reasoning.encrypted_content" }
    if (!exists) {
        values.add("reasoning.encrypted_content")
    }
    return values
}

/** 照抄 responses_reasoning_uses_tokens。 */
private fun responsesReasoningUsesTokens(reasoning: JsonObject?): Boolean {
    if (reasoning == null || reasoning.size() == 0) return false
    val effort = reasoning.get("effort")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    return effort == null || effort != "none"
}

private fun takeInt(params: JsonObject, key: String): Int? {
    val value = ModelHttpSupport.takeOptional(params, key) ?: return null
    return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asInt else null
}

private val CHAT_CONTROLLED_KEYS = listOf("messages", "model", "stream")
private val RESPONSES_CONTROLLED_KEYS = listOf("input", "model", "stream")
