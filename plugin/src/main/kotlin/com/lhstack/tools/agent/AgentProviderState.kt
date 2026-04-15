package com.lhstack.tools.agent

import com.intellij.util.xmlb.annotations.Tag
import java.net.Proxy
import java.util.UUID

enum class AgentProviderType(val id: String, val displayName: String) {
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic");

    override fun toString(): String = displayName

    companion object {
        fun fromId(id: String?): AgentProviderType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OPENAI
        }
    }
}

enum class AgentProxyType(val id: String, val displayName: String, val javaType: Proxy.Type) {
    HTTP("http", "HTTP", Proxy.Type.HTTP),
    SOCKS("socks", "SOCKS", Proxy.Type.SOCKS);

    override fun toString(): String = displayName

    companion object {
        @JvmStatic
        fun fromId(id: String?): AgentProxyType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: HTTP
        }
    }
}

@Tag("agent-provider")
class AgentProviderState {
    var id: String = ""
    var name: String = ""
    var type: String = AgentProviderType.OPENAI.id
    var providerType: String = AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE
    var vendorTemplate: String = AgentProviderCatalog.TEMPLATE_OPENAI
    var apiKey: String = ""
    var baseUrl: String = ""
    var endpointPath: String = ""
    var customHeaders: String = ""
    var maxTokens: Int = 0
    var models: MutableList<String> = mutableListOf()
    var activeModel: String = ""
    var modelSettings: MutableList<AgentModelSettings> = mutableListOf()
    var capabilities: MutableList<String> = mutableListOf()
    var defaultParameters: MutableMap<String, String> = linkedMapOf()
    var recommendedParameters: MutableMap<String, String> = linkedMapOf()
    var proxyEnabled: Boolean = false
    var proxyType: String = AgentProxyType.HTTP.id
    var proxyHost: String = ""
    var proxyPort: Int = 0
}

@Tag("model-settings")
class AgentModelSettings {
    var model: String = ""
    var streamingEnabled: Boolean = true
    var chatModeEnabled: Boolean = true
    var responsesModeEnabled: Boolean = false
    var modelCapabilities: MutableList<String> = mutableListOf()
    var openAiReasoningEffort: String = ""
    var openAiTemperature: String = ""
    var openAiTopP: String = ""
    var openAiMaxTokens: String = ""
    var openAiMaxCompletionTokens: String = ""
    var openAiPresencePenalty: String = ""
    var openAiFrequencyPenalty: String = ""
    var openAiSeed: String = ""
    var openAiStopSequences: String = ""
    var openAiResponseFormat: String = ""
    var openAiResponseFormatSchemaName: String = ""
    var openAiResponseFormatSchemaDescription: String = ""
    var openAiResponseFormatSchemaStrict: Boolean = false
    var openAiResponseFormatSchemaJson: String = ""
    var openAiLogprobs: String = ""
    var openAiTopLogprobs: String = ""
    var openAiToolChoice: String = ""
    var openAiParallelToolCalls: String = ""
    var anthropicThinkingMode: String = ""
    var anthropicThinkingBudgetTokens: Int = 0
    var anthropicMaxTokens: String = ""
    var anthropicTemperature: String = ""
    var anthropicTopP: String = ""
    var anthropicTopK: String = ""
    var anthropicStopSequences: String = ""
    var anthropicServiceTier: String = ""
    var anthropicInferenceGeo: String = ""
    var anthropicMetadataUserId: String = ""
    var anthropicOutputEffort: String = ""
    var anthropicOutputSchemaJson: String = ""
}

object AgentProviderSupport {
    fun defaultBaseUrl(type: AgentProviderType): String {
        return when (type) {
            AgentProviderType.OPENAI -> "https://api.openai.com/v1"
            AgentProviderType.ANTHROPIC -> "https://api.anthropic.com"
        }
    }

    fun defaultBaseUrl(providerType: String, vendorTemplate: String): String {
        return AgentProviderCatalog.defaultBaseUrl(providerType, vendorTemplate)
    }

    fun normalizeProvider(provider: AgentProviderState) {
        if (provider.id.isBlank()) {
            provider.id = UUID.randomUUID().toString()
        }
        val resolvedType = AgentProviderType.fromId(provider.type)
        provider.type = resolvedType.id
        provider.providerType = AgentProviderCatalog.normalizeProviderType(provider.providerType, provider.type)
        provider.vendorTemplate =
            AgentProviderCatalog.normalizeVendorTemplate(provider.providerType, provider.vendorTemplate, provider.type)
        if (provider.name.isBlank()) {
            provider.name = resolvedType.displayName
        }
        if (provider.baseUrl.isBlank()) {
            provider.baseUrl = defaultBaseUrl(provider.providerType, provider.vendorTemplate)
        }
        provider.endpointPath = provider.endpointPath.trim().ifBlank {
            AgentProviderCatalog.defaultEndpointPath(provider.providerType)
        }
        if (provider.maxTokens < 0) {
            provider.maxTokens = 0
        }
        provider.proxyType = AgentProxyType.fromId(provider.proxyType).id
        provider.models = provider.models.filter { it.isNotBlank() }.toMutableList()
        provider.activeModel = provider.activeModel.trim()
        provider.capabilities = provider.capabilities
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
        provider.defaultParameters = LinkedHashMap(
            provider.defaultParameters.mapNotNull { (key, value) ->
                key.trim().takeIf { it.isNotBlank() }?.let { it to value.trim() }
            }.toMap()
        )
        provider.recommendedParameters = LinkedHashMap(
            provider.recommendedParameters.mapNotNull { (key, value) ->
                key.trim().takeIf { it.isNotBlank() }?.let { it to value.trim() }
            }.toMap()
        )
        normalizeModelSettings(provider)
    }

    fun findModelSettings(provider: AgentProviderState, model: String): AgentModelSettings? {
        val trimmed = model.trim()
        if (trimmed.isBlank()) {
            return null
        }
        return provider.modelSettings.firstOrNull { it.model == trimmed }
    }

    fun getOrCreateModelSettings(provider: AgentProviderState, model: String): AgentModelSettings {
        val trimmed = model.trim()
        val existing = findModelSettings(provider, trimmed)
        if (existing != null) {
            return existing
        }
        val created = AgentModelSettings().apply { this.model = trimmed }
        provider.modelSettings.add(created)
        return created
    }

    fun renameModelSettings(provider: AgentProviderState, oldName: String, newName: String) {
        val oldTrimmed = oldName.trim()
        val newTrimmed = newName.trim()
        if (oldTrimmed.isBlank()) {
            return
        }
        if (newTrimmed.isBlank()) {
            removeModelSettings(provider, oldTrimmed)
            return
        }
        val entry = provider.modelSettings.firstOrNull { it.model == oldTrimmed } ?: return
        if (provider.modelSettings.any { it.model == newTrimmed }) {
            provider.modelSettings.remove(entry)
            return
        }
        entry.model = newTrimmed
    }

    fun removeModelSettings(provider: AgentProviderState, model: String) {
        val trimmed = model.trim()
        if (trimmed.isBlank()) {
            return
        }
        provider.modelSettings.removeAll { it.model == trimmed }
    }

    private fun normalizeModelSettings(provider: AgentProviderState) {
        val normalized = provider.modelSettings.mapNotNull { entry ->
            val model = entry.model.trim()
            if (model.isBlank()) {
                return@mapNotNull null
            }
            entry.model = model
            entry.modelCapabilities = AgentModelCapabilityCatalog.normalize(entry.modelCapabilities).toMutableList()
            entry.openAiReasoningEffort = entry.openAiReasoningEffort.trim().lowercase()
            entry.openAiTemperature = entry.openAiTemperature.trim()
            entry.openAiTopP = entry.openAiTopP.trim()
            entry.openAiMaxTokens = entry.openAiMaxTokens.trim()
            entry.openAiMaxCompletionTokens = entry.openAiMaxCompletionTokens.trim()
            entry.openAiPresencePenalty = entry.openAiPresencePenalty.trim()
            entry.openAiFrequencyPenalty = entry.openAiFrequencyPenalty.trim()
            entry.openAiSeed = entry.openAiSeed.trim()
            entry.openAiStopSequences = entry.openAiStopSequences.trim()
            entry.openAiResponseFormat = entry.openAiResponseFormat.trim().lowercase()
            entry.openAiResponseFormatSchemaName = entry.openAiResponseFormatSchemaName.trim()
            entry.openAiResponseFormatSchemaDescription = entry.openAiResponseFormatSchemaDescription.trim()
            entry.openAiResponseFormatSchemaJson = entry.openAiResponseFormatSchemaJson.trim()
            entry.openAiLogprobs = entry.openAiLogprobs.trim().lowercase()
            entry.openAiTopLogprobs = entry.openAiTopLogprobs.trim()
            entry.openAiToolChoice = entry.openAiToolChoice.trim().lowercase()
            entry.openAiParallelToolCalls = entry.openAiParallelToolCalls.trim().lowercase()
            entry.anthropicThinkingMode = entry.anthropicThinkingMode.trim().lowercase()
            if (entry.anthropicThinkingBudgetTokens < 0) {
                entry.anthropicThinkingBudgetTokens = 0
            }
            entry.anthropicMaxTokens = entry.anthropicMaxTokens.trim()
            entry.anthropicTemperature = entry.anthropicTemperature.trim()
            entry.anthropicTopP = entry.anthropicTopP.trim()
            entry.anthropicTopK = entry.anthropicTopK.trim()
            entry.anthropicStopSequences = entry.anthropicStopSequences.trim()
            entry.anthropicServiceTier = entry.anthropicServiceTier.trim().lowercase()
            entry.anthropicInferenceGeo = entry.anthropicInferenceGeo.trim()
            entry.anthropicMetadataUserId = entry.anthropicMetadataUserId.trim()
            entry.anthropicOutputEffort = entry.anthropicOutputEffort.trim().lowercase()
            entry.anthropicOutputSchemaJson = entry.anthropicOutputSchemaJson.trim()
            entry
        }.distinctBy { it.model }.toMutableList()
        provider.modelSettings = normalized
    }

    data class HeaderParseResult(
        val headers: Map<String, List<String>>,
        val invalidLines: List<String>,
    )

    fun parseCustomHeaders(raw: String): HeaderParseResult {
        val result = linkedMapOf<String, MutableList<String>>()
        val invalid = mutableListOf<String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                return@forEach
            }
            val parsed = splitHeaderLine(trimmed)
            if (parsed == null) {
                invalid.add(trimmed)
                return@forEach
            }
            val (name, value) = parsed
            result.getOrPut(name) { mutableListOf() }.add(value)
        }
        return HeaderParseResult(result, invalid)
    }

    fun normalizeHeaderKey(raw: String): String {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\\n")
    }

    private fun splitHeaderLine(line: String): Pair<String, String>? {
        val eqIndex = line.indexOf('=')
        val colonIndex = line.indexOf(':')
        val index = when {
            eqIndex > 0 -> eqIndex
            colonIndex > 0 -> colonIndex
            else -> -1
        }
        if (index <= 0 || index >= line.length - 1) {
            return null
        }
        val name = line.substring(0, index).trim()
        val value = line.substring(index + 1).trim()
        if (name.isBlank() || value.isBlank()) {
            return null
        }
        return name to value
    }
}
