package com.lhstack.tools.agent

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.ToolChoice
import io.agentscope.core.model.ollama.OllamaOptions

class AgentScopeOptionMapper {

    fun map(provider: AgentProviderState, settings: AgentModelSettings? = null): GenerateOptions {
        val normalized = provider.also(AgentProviderSupport::normalizeProvider)
        val builder = GenerateOptions.builder()
            .apiKey(normalized.apiKey.takeIf { it.isNotBlank() })
            .baseUrl(normalized.baseUrl.takeIf { it.isNotBlank() })
            .endpointPath(normalized.endpointPath.takeIf { it.isNotBlank() })
            .modelName(resolveModelName(normalized))

        applyProviderDefaultOptions(builder, normalized)
        applyModelScopedOptions(builder, normalized.providerType, settings)

        val headers = AgentProviderSupport.parseCustomHeaders(normalized.customHeaders).headers
        headers.forEach { (name, values) ->
            if (values.isNotEmpty()) {
                builder.additionalHeader(name, values.joinToString(", "))
            }
        }
        return builder.build()
    }

    fun mapOllama(provider: AgentProviderState, settings: AgentModelSettings? = null): OllamaOptions {
        return OllamaOptions.fromGenerateOptions(map(provider, settings))
    }

    private fun applyProviderDefaultOptions(builder: GenerateOptions.Builder, provider: AgentProviderState) {
        builder.maxTokens(provider.maxTokens.takeIf { it > 0 })
        provider.defaultParameters.forEach { (key, rawValue) ->
            applyOption(builder, key, rawValue)
        }
    }

    private fun applyModelScopedOptions(
        builder: GenerateOptions.Builder,
        providerType: String,
        settings: AgentModelSettings?,
    ) {
        if (settings == null) {
            return
        }
        resolveCommonModelParameters(providerType, settings).forEach { (key, value) ->
            applyOption(builder, key, value)
        }
        resolveCustomModelParameters(providerType, settings).forEach { (key, value) ->
            applyOption(builder, key, value)
        }
    }

    private fun resolveCommonModelParameters(providerType: String, settings: AgentModelSettings): Map<String, Any> {
        val parameters = linkedMapOf<String, Any>()
        if (providerType == AgentProviderCatalog.TYPE_ANTHROPIC) {
            putIfNotBlank(parameters, "max_tokens", settings.anthropicMaxTokens)
            putIfNotBlank(parameters, "temperature", settings.anthropicTemperature)
            putIfNotBlank(parameters, "top_p", settings.anthropicTopP)
            putIfNotBlank(parameters, "top_k", settings.anthropicTopK)
            parseStopSequences(settings.anthropicStopSequences)?.let { parameters["stop_sequences"] = it }
            if (settings.anthropicThinkingMode == "enabled" && settings.anthropicThinkingBudgetTokens > 0) {
                parameters["thinking_budget"] = settings.anthropicThinkingBudgetTokens
            }
        } else {
            putIfNotBlank(parameters, "temperature", settings.openAiTemperature)
            putIfNotBlank(parameters, "top_p", settings.openAiTopP)
            putIfNotBlank(parameters, "max_tokens", settings.openAiMaxTokens)
            putIfNotBlank(parameters, "max_completion_tokens", settings.openAiMaxCompletionTokens)
            putIfNotBlank(parameters, "presence_penalty", settings.openAiPresencePenalty)
            putIfNotBlank(parameters, "frequency_penalty", settings.openAiFrequencyPenalty)
            putIfNotBlank(parameters, "seed", settings.openAiSeed)
            putIfNotBlank(parameters, "tool_choice", settings.openAiToolChoice)
            parseStopSequences(settings.openAiStopSequences)?.let { parameters["stop"] = it }
        }
        return parameters
    }

    private fun resolveCustomModelParameters(providerType: String, settings: AgentModelSettings): Map<String, Any> {
        val parameters = linkedMapOf<String, Any>()
        if (providerType == AgentProviderCatalog.TYPE_ANTHROPIC) {
            buildAnthropicThinking(settings)?.let { parameters["thinking"] = it }
            putIfNotBlank(parameters, "service_tier", settings.anthropicServiceTier)
            putIfNotBlank(parameters, "inference_geo", settings.anthropicInferenceGeo)
            putIfNotBlank(parameters, "output_effort", settings.anthropicOutputEffort)
            parseJsonObject(settings.anthropicOutputSchemaJson)?.let { parameters["output_schema"] = it }
            settings.anthropicMetadataUserId.trim().takeIf { it.isNotEmpty() }?.let { userId ->
                parameters["metadata"] = linkedMapOf("user_id" to userId)
            }
        } else if (
            providerType == AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE ||
            providerType == AgentProviderCatalog.TYPE_DASHSCOPE
        ) {
            putIfNotBlank(parameters, "reasoning_effort", settings.openAiReasoningEffort)
            buildOpenAiResponseFormat(settings)?.let { parameters["response_format"] = it }
            putIfNotBlank(parameters, "logprobs", settings.openAiLogprobs)
            putIfNotBlank(parameters, "top_logprobs", settings.openAiTopLogprobs)
            putIfNotBlank(parameters, "parallel_tool_calls", settings.openAiParallelToolCalls)
        }
        return parameters
    }

    private fun buildOpenAiResponseFormat(settings: AgentModelSettings): Map<String, Any>? {
        val mode = settings.openAiResponseFormat.trim().lowercase()
        if (mode.isEmpty()) {
            return null
        }
        if (mode != "json_schema") {
            return linkedMapOf("type" to mode)
        }
        val jsonSchema = linkedMapOf<String, Any>()
        settings.openAiResponseFormatSchemaName.trim().takeIf { it.isNotEmpty() }?.let { jsonSchema["name"] = it }
        settings.openAiResponseFormatSchemaDescription.trim().takeIf { it.isNotEmpty() }?.let {
            jsonSchema["description"] = it
        }
        if (settings.openAiResponseFormatSchemaStrict) {
            jsonSchema["strict"] = true
        }
        parseJsonObject(settings.openAiResponseFormatSchemaJson)?.let { jsonSchema["schema"] = it }
        return linkedMapOf<String, Any>(
            "type" to "json_schema",
            "json_schema" to jsonSchema
        )
    }

    private fun buildAnthropicThinking(settings: AgentModelSettings): Map<String, Any>? {
        val mode = settings.anthropicThinkingMode.trim().lowercase()
        if (mode.isEmpty()) {
            return null
        }
        val thinking = linkedMapOf<String, Any>("type" to mode)
        if (mode == "enabled" && settings.anthropicThinkingBudgetTokens > 0) {
            thinking["budget_tokens"] = settings.anthropicThinkingBudgetTokens
        }
        return thinking
    }

    private fun parseStopSequences(raw: String): List<String>? {
        val items = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return items.takeIf { it.isNotEmpty() }
    }

    private fun parseJsonObject(raw: String): Any? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return runCatching {
            jsonToNative(JsonParser.parseString(trimmed))
        }.getOrNull()
    }

    private fun jsonToNative(element: JsonElement): Any? {
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> primitive.asNumber
                    else -> primitive.asString
                }
            }
            element.isJsonArray -> element.asJsonArray.map { jsonToNative(it) }
            else -> linkedMapOf<String, Any?>().apply {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    put(key, jsonToNative(value))
                }
            }
        }
    }

    private fun putIfNotBlank(target: MutableMap<String, Any>, key: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) {
            target[key] = trimmed
        }
    }

    private fun applyOption(builder: GenerateOptions.Builder, key: String, value: Any?) {
        if (value == null) {
            return
        }
        when (key.trim().lowercase()) {
            "temperature" -> builder.temperature(asDouble(value))
            "top_p" -> builder.topP(asDouble(value))
            "max_tokens" -> builder.maxTokens(asInt(value))
            "max_completion_tokens" -> builder.maxCompletionTokens(asInt(value))
            "frequency_penalty" -> builder.frequencyPenalty(asDouble(value))
            "presence_penalty" -> builder.presencePenalty(asDouble(value))
            "thinking_budget" -> builder.thinkingBudget(asInt(value))
            "reasoning_effort" -> builder.reasoningEffort(asString(value)?.trim()?.lowercase())
            "tool_choice" -> {
                val toolChoice = if (value is ToolChoice) value else mapToolChoice(asString(value).orEmpty())
                builder.toolChoice(toolChoice)
            }
            "top_k" -> builder.topK(asInt(value))
            "seed" -> builder.seed(asLong(value))
            "logprobs" -> builder.additionalBodyParam("logprobs", asBoolean(value) ?: normalizeBodyValue(value))
            "top_logprobs" -> builder.additionalBodyParam("top_logprobs", asInt(value) ?: normalizeBodyValue(value))
            "parallel_tool_calls" -> builder.additionalBodyParam(
                "parallel_tool_calls",
                asBoolean(value) ?: normalizeBodyValue(value)
            )
            "response_format" -> builder.additionalBodyParam("response_format", normalizeResponseFormat(value))
            else -> builder.additionalBodyParam(key, normalizeBodyValue(value))
        }
    }

    private fun resolveModelName(provider: AgentProviderState): String {
        val activeModel = provider.activeModel.trim()
        if (activeModel.isNotBlank()) {
            return activeModel
        }
        return provider.models.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun mapToolChoice(rawValue: String): ToolChoice? {
        return when (val normalized = rawValue.trim().lowercase()) {
            "", "auto" -> ToolChoice.Auto()
            "none" -> ToolChoice.None()
            "required" -> ToolChoice.Required()
            else -> ToolChoice.Specific(normalized)
        }
    }

    private fun parseValue(rawValue: String): Any {
        val value = rawValue.trim()
        return when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            value.toIntOrNull() != null -> value.toInt()
            value.toLongOrNull() != null -> value.toLong()
            value.toDoubleOrNull() != null -> value.toDouble()
            else -> value
        }
    }

    private fun normalizeBodyValue(value: Any): Any {
        return when (value) {
            is String -> parseValue(value)
            else -> value
        }
    }

    private fun normalizeResponseFormat(value: Any): Any {
        if (value is Map<*, *>) {
            return value
        }
        val raw = asString(value)?.trim().orEmpty()
        if (raw.isEmpty()) {
            return raw
        }
        return parseJsonObject(raw) ?: linkedMapOf("type" to raw.lowercase())
    }

    private fun asString(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value
            else -> value.toString()
        }
    }

    private fun asInt(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun asLong(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun asDouble(value: Any?): Double? {
        return when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun asBoolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun GenerateOptions.Builder.apiKey(value: String?): GenerateOptions.Builder {
        return if (value == null) this else apiKey(value)
    }

    private fun GenerateOptions.Builder.baseUrl(value: String?): GenerateOptions.Builder {
        return if (value == null) this else baseUrl(value)
    }

    private fun GenerateOptions.Builder.endpointPath(value: String?): GenerateOptions.Builder {
        return if (value == null) this else endpointPath(value)
    }

    private fun GenerateOptions.Builder.maxTokens(value: Int?): GenerateOptions.Builder {
        return if (value == null) this else maxTokens(value)
    }

    private fun GenerateOptions.Builder.temperature(value: Double?): GenerateOptions.Builder {
        return if (value == null) this else temperature(value)
    }

    private fun GenerateOptions.Builder.topP(value: Double?): GenerateOptions.Builder {
        return if (value == null) this else topP(value)
    }

    private fun GenerateOptions.Builder.maxCompletionTokens(value: Int?): GenerateOptions.Builder {
        return if (value == null) this else maxCompletionTokens(value)
    }

    private fun GenerateOptions.Builder.frequencyPenalty(value: Double?): GenerateOptions.Builder {
        return if (value == null) this else frequencyPenalty(value)
    }

    private fun GenerateOptions.Builder.presencePenalty(value: Double?): GenerateOptions.Builder {
        return if (value == null) this else presencePenalty(value)
    }

    private fun GenerateOptions.Builder.thinkingBudget(value: Int?): GenerateOptions.Builder {
        return if (value == null) this else thinkingBudget(value)
    }

    private fun GenerateOptions.Builder.toolChoice(value: ToolChoice?): GenerateOptions.Builder {
        return if (value == null) this else toolChoice(value)
    }

    private fun GenerateOptions.Builder.topK(value: Int?): GenerateOptions.Builder {
        return if (value == null) this else topK(value)
    }

    private fun GenerateOptions.Builder.seed(value: Long?): GenerateOptions.Builder {
        return if (value == null) this else seed(value)
    }
}
