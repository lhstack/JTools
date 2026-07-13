package com.lhstack.tools.agent

import com.google.gson.JsonElement
import com.lhstack.tools.db.entity.ProviderEntity
import com.lhstack.tools.agent.model.http.ModelHttpClientFactory
import com.lhstack.tools.agent.model.params.ModelParams
import com.lhstack.tools.agent.model.params.ProviderKind
import com.lhstack.tools.agent.model.provider.ModelProvider
import com.lhstack.tools.agent.model.provider.RemoteModelsRequest

/**
 * awake-claw 供应商远端模型列表的 Swing 入口。
 *
 * 这里直接接收 SQLite 中的 ProviderEntity，不再经过旧 AgentProviderState。
 * 远端请求统一复用已按 awake 对齐的 ModelProvider/OpenAiClient/AnthropicClient。
 */
object AwakeRemoteModelService {
    fun listModels(provider: ProviderEntity): List<String> {
        provider.id ?: throw IllegalArgumentException("供应商尚未保存，无法获取远端模型")
        val kind = ProviderKind.parse(provider.kind)
        val apiKey = provider.apiKey
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("供应商缺少 API 密钥，无法获取远端模型")
        val baseUrl = provider.baseUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ModelParams.defaultBaseUrlForProvider(kind)
        val providerConfig = provider.providerConfig.parseJsonElement()
        val request = RemoteModelsRequest(
            providerKind = kind,
            apiKey = apiKey,
            baseUrl = baseUrl,
            anthropicVersion = provider.anthropicVersion,
            openaiProviderType = ModelParams.openaiProviderType(providerConfig),
        )
        val values = ModelProvider.fetchRemoteModels(
            executor = ModelHttpClientFactory.executorFor(baseUrl, providerProxyUrl(providerConfig)),
            request = request,
        )
        return values.mapNotNull(::modelId)
    }

    private fun providerProxyUrl(providerConfig: JsonElement): String? =
        providerConfig.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("proxy_url")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun modelId(value: JsonElement): String? {
        val objectValue = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return objectValue.get("id")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun String?.parseJsonElement(): JsonElement {
        val value = com.google.gson.JsonParser.parseString(this?.ifBlank { "{}" } ?: "{}")
        require(value.isJsonObject) { "provider_config 必须是 JSON object" }
        return value
    }
}
