package com.lhstack.tools.agent.model.params

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.lhstack.tools.db.entity.ModelEntity
import com.lhstack.tools.db.entity.ProviderEntity

/**
 * 模型配置解析。完全照抄 awake-claw models.rs 的 resolve_model_config_from_store /
 * resolve_model_config_from_snapshot / model_snapshot。
 *
 * 把持久化的 ProviderEntity + ModelEntity（或会话模型快照 JSON）解析成运行时
 * ResolvedModelConfig。model_params / execution_params / additional_params 以 JSON
 * 文本存储，这里解析成 JsonElement 交给下游透传，不做结构约束。
 *
 * api_key 校验照抄 resolve_api_key：必须非空白，否则报错。
 */
object ModelResolver {

    /** 照抄 resolve_model_config_from_store。 */
    fun resolveFromStore(provider: ProviderEntity, model: ModelEntity): ResolvedModelConfig {
        val kind = ProviderKind.parse(provider.kind)
        val providerConfig = parseJson(provider.providerConfig)
        val openaiProviderType = ModelParams.openaiProviderType(providerConfig)
        val proxyUrl = providerProxyUrl(providerConfig)
        val apiKey = resolveApiKey(provider.apiKey, provider.name)
        val baseUrl = provider.baseUrl?.takeIf { it.isNotBlank() }
            ?: ModelParams.defaultBaseUrlForProvider(kind)
        val api = OpenAiApi.parseOptional(model.api?.takeIf { it.isNotBlank() })
            ?: OpenAiApi.parseOptional(provider.api?.takeIf { it.isNotBlank() })
            ?: OpenAiApi.DEFAULT
        val modelParams = parseOptionalJson(model.modelParams)
        val stream = modelParamBool(modelParams, "stream") ?: false
        val executionParams = model.executionParams
            ?.let { parseJson(it) }
            ?.let { modelExecutionParams(it) }
            ?: ModelExecutionParams()
        return ResolvedModelConfig(
            providerId = provider.id ?: 0,
            providerName = provider.name,
            id = model.id ?: 0,
            modelAlias = model.alias,
            displayName = model.displayName,
            providerKind = kind,
            apiKey = apiKey,
            baseUrl = baseUrl,
            proxyUrl = proxyUrl,
            modelId = model.modelId,
            api = api,
            openaiProviderType = openaiProviderType,
            stream = stream,
            anthropicVersion = provider.anthropicVersion,
            params = ModelRuntimeParams(
                contextWindow = model.contextWindow,
                modalities = parseStringArray(model.modalities),
                modelParams = modelParams,
                executionParams = executionParams,
                additionalParams = parseOptionalJson(model.additionalParams),
            ),
        )
    }

    /** 照抄 resolve_model_config_from_snapshot：从会话模型快照恢复运行时配置。 */
    fun resolveFromSnapshot(provider: ProviderEntity, snapshot: JsonElement): ResolvedModelConfig {
        val snap = snapshot.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("会话模型快照必须是 JSON object")
        val kind = ProviderKind.parse(provider.kind)
        val providerConfig = parseJson(provider.providerConfig)
        val openaiProviderType = ModelParams.openaiProviderType(providerConfig)
        val providerProxyUrl = providerProxyUrl(providerConfig)
        val apiKey = resolveApiKey(provider.apiKey, provider.name)
        val baseUrl = provider.baseUrl?.takeIf { it.isNotBlank() }
            ?: ModelParams.defaultBaseUrlForProvider(kind)
        val api = if (kind == ProviderKind.OPEN_AI) {
            snapshotString(snap, "api")?.let { OpenAiApi.parse(it) }
                ?: OpenAiApi.parseOptional(provider.api?.takeIf { it.isNotBlank() })
                ?: OpenAiApi.DEFAULT
        } else {
            OpenAiApi.parseOptional(provider.api?.takeIf { it.isNotBlank() }) ?: OpenAiApi.DEFAULT
        }
        val modelParams = snap.get("model_params")?.takeIf { !it.isJsonNull }
        val executionParams = snap.get("execution_params")
            ?.let { modelExecutionParams(it) }
            ?: ModelExecutionParams()
        return ResolvedModelConfig(
            providerId = provider.id ?: 0,
            providerName = provider.name,
            id = snapshotLong(snap, "id") ?: 0,
            modelAlias = snapshotString(snap, "model_label")
                ?: throw IllegalArgumentException("会话模型快照缺少 model_label"),
            displayName = snapshotString(snap, "display_name"),
            providerKind = kind,
            apiKey = apiKey,
            baseUrl = baseUrl,
            proxyUrl = snapshotString(snap, "proxy_url") ?: providerProxyUrl,
            modelId = snapshotString(snap, "model_id")
                ?: throw IllegalArgumentException("会话模型快照缺少 model_id"),
            api = api,
            openaiProviderType = openaiProviderType,
            stream = modelParamBool(modelParams, "stream")
                ?: snap.get("stream")?.asBoolOrNull()
                ?: false,
            anthropicVersion = snapshotString(snap, "anthropic_version") ?: provider.anthropicVersion,
            params = ModelRuntimeParams(
                contextWindow = snap.get("context_window")?.asLongOrNull(),
                modalities = snap.get("modalities")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.asStringOrNull() } ?: emptyList(),
                modelParams = modelParams,
                executionParams = executionParams,
                additionalParams = snap.get("additional_params")?.takeIf { !it.isJsonNull },
            ),
        )
    }

    /** 照抄 model_snapshot：把运行时配置序列化成会话快照 JSON。 */
    fun modelSnapshot(model: ResolvedModelConfig): JsonObject = JsonObject().apply {
        addProperty("provider_id", model.providerId)
        addProperty("provider_label", model.providerName)
        addProperty("id", model.id)
        addProperty("model_label", model.modelAlias)
        add("display_name", model.displayName?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty(
            "provider_kind",
            when (model.providerKind) {
                ProviderKind.OPEN_AI -> "openai"
                ProviderKind.ANTHROPIC -> "anthropic"
            },
        )
        addProperty("base_url", model.baseUrl)
        add("proxy_url", model.proxyUrl?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("model_id", model.modelId)
        addProperty("api", model.api.asStr())
        addProperty(
            "openai_provider_type",
            when (model.openaiProviderType) {
                OpenAiProviderType.OFFICIAL -> "official"
                OpenAiProviderType.COMPATIBLE -> "compatible"
            },
        )
        addProperty("stream", model.stream)
        add("anthropic_version", model.anthropicVersion?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("model_params", model.params.modelParams ?: JsonNull.INSTANCE)
        add("execution_params", JsonObject().apply {
            add("max_tool_call_rounds", model.params.executionParams.maxToolCallRounds?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("max_retries", model.params.executionParams.maxRetries?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        })
        add("context_window", model.params.contextWindow?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("modalities", JsonArray().apply { model.params.modalities.forEach { add(it) } })
        add("additional_params", model.params.additionalParams ?: JsonNull.INSTANCE)
    }

    // -------- helpers --------

    private fun providerProxyUrl(providerConfig: JsonElement?): String? {
        val proxyUrl = providerConfig.asJsonObjectOrNull()
            ?.get("proxy_url")
            ?.asStringOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = runCatching { java.net.URI(proxyUrl).scheme?.lowercase() }.getOrNull()
        require(scheme in setOf("http", "https", "socks5", "socks5h")) {
            "不支持的代理协议 `${scheme ?: ""}`，仅支持 http、https、socks5、socks5h"
        }
        return proxyUrl
    }

    /** 照抄 resolve_api_key：非空白才通过，否则报错。 */
    private fun resolveApiKey(apiKey: String?, providerName: String): String {
        val key = apiKey?.takeIf { it.isNotBlank() }
        if (key != null) {
            return key
        }
        throw IllegalStateException("解析供应商 `$providerName` 的 api key 失败: api_key 必须配置")
    }

    /** 照抄 model_execution_params。 */
    private fun modelExecutionParams(value: JsonElement): ModelExecutionParams {
        val obj = value.asJsonObjectOrNull() ?: return ModelExecutionParams()
        return ModelExecutionParams(
            maxToolCallRounds = obj.get("max_tool_call_rounds")?.asLongOrNull()?.toInt(),
            maxRetries = obj.get("max_retries")?.asLongOrNull()?.toInt(),
        )
    }

    /** 照抄 model_param_bool。 */
    private fun modelParamBool(modelParams: JsonElement?, key: String): Boolean? =
        modelParams?.asJsonObjectOrNull()?.get(key)?.asBoolOrNull()

    private fun snapshotString(snapshot: JsonObject, key: String): String? =
        snapshot.get(key)?.asStringOrNull()?.takeIf { it.isNotBlank() }

    private fun snapshotLong(snapshot: JsonObject, key: String): Long? =
        snapshot.get(key)?.asLongOrNull()

    private fun parseJson(text: String): JsonElement =
        try {
            JsonParser.parseString(text)
        } catch (_: Throwable) {
            JsonObject()
        }

    private fun parseOptionalJson(text: String?): JsonElement? {
        val raw = text?.takeIf { it.isNotBlank() } ?: return null
        return try {
            JsonParser.parseString(raw).takeIf { !it.isJsonNull }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseStringArray(text: String): List<String> =
        try {
            JsonParser.parseString(text).takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.asStringOrNull() } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
}
