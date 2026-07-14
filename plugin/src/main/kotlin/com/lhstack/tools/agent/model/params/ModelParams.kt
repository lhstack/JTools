package com.lhstack.tools.agent.model.params

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 模型参数解析层。完全照抄 awake-claw 的 src/service/models.rs。
 *
 * 关键约定（awake commit 8b1888a「修复参数没生效问题」）：
 * model_params 是唯一真源。客户端不再从单独字段抽取 temperature/top_p/top_k/max_tokens
 * 去覆盖，全部走 model_params + additional_params 合并后透传；默认值只在 model_params
 * 与 additional_params 都未设置该 key 时才注入，绝不覆盖用户配置。
 */

enum class ProviderKind {
    OPEN_AI,
    ANTHROPIC;

    companion object {
        /** 照抄 parse_provider_kind。 */
        fun parse(value: String): ProviderKind = when (value) {
            "open_ai", "openai", "open_ai_compatible" -> OPEN_AI
            "anthropic" -> ANTHROPIC
            else -> throw IllegalArgumentException("未知供应商类型 `$value`")
        }
    }
}

enum class OpenAiProviderType {
    OFFICIAL,
    COMPATIBLE,
}

/** 照抄 config.rs 的 OpenAiApi，默认 Responses。 */
enum class OpenAiApi {
    RESPONSES,
    COMPLETIONS;

    fun asStr(): String = when (this) {
        RESPONSES -> "responses"
        COMPLETIONS -> "completions"
    }

    companion object {
        val DEFAULT = RESPONSES

        /** 照抄 parse_openai_api。 */
        fun parse(value: String): OpenAiApi = when (value) {
            "responses" -> RESPONSES
            "completions", "chat_completions", "chat/completions" -> COMPLETIONS
            else -> throw IllegalArgumentException("未知 OpenAI API 类型 `$value`")
        }

        fun parseOptional(value: String?): OpenAiApi? = value?.let { parse(it) }
    }
}

/** 照抄 ModelExecutionParams。 */
data class ModelExecutionParams(
    val maxToolCallRounds: Int? = null,
    val maxRetries: Int? = null,
)

/**
 * 照抄 ModelRuntimeParams。注意：修复后不再有 temperature/top_p/top_k/max_tokens/
 * reasoning_* 等单独字段，只保留 model_params 等真源字段。
 */
data class ModelRuntimeParams(
    val contextWindow: Long? = null,
    val modalities: List<String> = emptyList(),
    val modelParams: JsonElement? = null,
    val executionParams: ModelExecutionParams = ModelExecutionParams(),
    val additionalParams: JsonElement? = null,
)

/** 照抄 ResolvedModelConfig。 */
data class ResolvedModelConfig(
    val providerId: Long,
    val providerName: String,
    val id: Long,
    val modelAlias: String,
    val displayName: String?,
    val providerKind: ProviderKind,
    val apiKey: String,
    val baseUrl: String,
    val proxyUrl: String?,
    val modelId: String,
    val api: OpenAiApi,
    val openaiProviderType: OpenAiProviderType,
    val stream: Boolean,
    val anthropicVersion: String?,
    val params: ModelRuntimeParams,
)

object ModelParams {

    fun defaultBaseUrl(): String = "https://api.openai.com/v1"

    /** 照抄 default_base_url_for_provider。 */
    fun defaultBaseUrlForProvider(kind: ProviderKind): String = when (kind) {
        ProviderKind.OPEN_AI -> defaultBaseUrl()
        ProviderKind.ANTHROPIC -> "https://api.anthropic.com"
    }

    /** 照抄 default_anthropic_max_tokens。 */
    fun defaultAnthropicMaxTokens(): Long = 32768

    /** 照抄 openai_provider_type。 */
    fun openaiProviderType(providerConfig: JsonElement?): OpenAiProviderType {
        val type = providerConfig?.asJsonObjectOrNull()
            ?.get("openai_provider_type")?.asStringOrNull()
        return if (type == "compatible") OpenAiProviderType.COMPATIBLE else OpenAiProviderType.OFFICIAL
    }

    /**
     * 照抄 runtime_output_tokens：
     * 从 model_params 读取输出 token 上限，Anthropic 缺失时补默认值。
     * model_params 是唯一真源，此处不覆盖，仅在缺失时兜底。
     */
    fun runtimeOutputTokens(model: ResolvedModelConfig): Long? =
        outputTokenLimit(model.providerKind, model.api, model.params.modelParams)
            ?: if (model.providerKind == ProviderKind.ANTHROPIC) defaultAnthropicMaxTokens() else null

    /** 照抄 output_token_limit。 */
    private fun outputTokenLimit(
        providerKind: ProviderKind,
        api: OpenAiApi,
        modelParams: JsonElement?,
    ): Long? {
        val params = modelParams?.asJsonObjectOrNull() ?: return null
        return when (providerKind) {
            ProviderKind.ANTHROPIC -> params.get("max_tokens")?.asLongOrNull()
            ProviderKind.OPEN_AI -> when (api) {
                OpenAiApi.RESPONSES -> params.get("max_output_tokens")?.asLongOrNull()
                OpenAiApi.COMPLETIONS -> params.get("max_completion_tokens")?.asLongOrNull()
            }
        }
    }

    /**
     * 照抄 additional_params：
     * model_params 是唯一真源，默认值只在 model_params 与 additional_params 都未设置
     * 该 key 时才注入。
     */
    fun additionalParams(model: ResolvedModelConfig, environmentId: Long?): JsonElement? {
        val params = model.params.additionalParams?.asJsonObjectOrNull()?.deepCopy() ?: JsonObject()
        val modelParams = model.params.modelParams?.asJsonObjectOrNull()

        when (model.providerKind) {
            ProviderKind.OPEN_AI -> {
                if (model.openaiProviderType == OpenAiProviderType.COMPATIBLE) {
                    return if (params.entrySet().isEmpty()) null else params
                }
                when (model.api) {
                    OpenAiApi.RESPONSES -> {
                        val defaults = JsonObject().apply {
                            addProperty("tool_choice", "auto")
                            addProperty("parallel_tool_calls", true)
                            addProperty("store", false)
                            addProperty("prompt_cache_key", responsesPromptCacheKey(model, environmentId))
                        }
                        mergeDefaultsIfAbsent(params, modelParams, defaults)
                    }
                    OpenAiApi.COMPLETIONS -> {
                        val defaults = JsonObject().apply {
                            addProperty("parallel_tool_calls", true)
                            addProperty("prompt_cache_key", completionsPromptCacheKey(model, environmentId))
                        }
                        mergeDefaultsIfAbsent(params, modelParams, defaults)
                    }
                }
            }
            // Anthropic 的 thinking 由 model_params 决定，请求层原样透传，不在此合成。
            ProviderKind.ANTHROPIC -> {}
        }

        return if (params.entrySet().isEmpty()) null else params
    }

    private fun responsesPromptCacheKey(model: ResolvedModelConfig, environmentId: Long?): String {
        val env = environmentId?.toString() ?: "none"
        return "jtools:responses:provider:${model.providerId}:model:${model.modelId}:env:$env"
    }

    private fun completionsPromptCacheKey(model: ResolvedModelConfig, environmentId: Long?): String {
        val env = environmentId?.toString() ?: "none"
        return "jtools:completions:provider:${model.providerId}:model:${model.modelId}:env:$env"
    }

    /**
     * 照抄 merge_defaults_if_absent：
     * 仅当 model_params 与 additional_params 都未设置该 key 时才注入默认值。
     */
    private fun mergeDefaultsIfAbsent(
        target: JsonObject,
        modelParams: JsonObject?,
        defaults: JsonObject,
    ) {
        for ((key, value) in defaults.entrySet()) {
            if (modelParams?.has(key) == true) {
                continue
            }
            if (!target.has(key)) {
                target.add(key, value)
            }
        }
    }
}

// ---------------- Gson helpers ----------------

internal fun JsonElement?.asJsonObjectOrNull(): JsonObject? =
    if (this != null && this.isJsonObject) this.asJsonObject else null

internal fun JsonElement?.asStringOrNull(): String? =
    if (this != null && this.isJsonPrimitive && (this as JsonPrimitive).isString) this.asString else null

internal fun JsonElement?.asLongOrNull(): Long? =
    if (this != null && this.isJsonPrimitive && (this as JsonPrimitive).isNumber) this.asLong else null

internal fun JsonElement?.asBoolOrNull(): Boolean? =
    if (this != null && this.isJsonPrimitive && (this as JsonPrimitive).isBoolean) this.asBoolean else null
