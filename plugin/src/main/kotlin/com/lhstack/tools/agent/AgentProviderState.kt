package com.lhstack.tools.agent

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
        fun fromId(id: String?): AgentProxyType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: HTTP
        }
    }
}

class AgentProviderState {
    var id: String = ""
    var name: String = ""
    var type: String = AgentProviderType.OPENAI.id
    var apiKey: String = ""
    var baseUrl: String = ""
    var customHeaders: String = ""
    var maxTokens: Int = 1024
    var models: MutableList<String> = mutableListOf()
    var activeModel: String = ""
    var proxyEnabled: Boolean = false
    var proxyType: String = AgentProxyType.HTTP.id
    var proxyHost: String = ""
    var proxyPort: Int = 0
}

object AgentProviderSupport {
    fun defaultBaseUrl(type: AgentProviderType): String {
        return when (type) {
            AgentProviderType.OPENAI -> "https://api.openai.com/v1"
            AgentProviderType.ANTHROPIC -> "https://api.anthropic.com"
        }
    }

    fun normalizeProvider(provider: AgentProviderState) {
        if (provider.id.isBlank()) {
            provider.id = UUID.randomUUID().toString()
        }
        val resolvedType = AgentProviderType.fromId(provider.type)
        provider.type = resolvedType.id
        if (provider.name.isBlank()) {
            provider.name = resolvedType.displayName
        }
        if (provider.baseUrl.isBlank()) {
            provider.baseUrl = defaultBaseUrl(resolvedType)
        }
        if (provider.maxTokens <= 0) {
            provider.maxTokens = 1024
        }
        provider.proxyType = AgentProxyType.fromId(provider.proxyType).id
        provider.models = provider.models.filter { it.isNotBlank() }.toMutableList()
        provider.activeModel = provider.activeModel.trim()
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
