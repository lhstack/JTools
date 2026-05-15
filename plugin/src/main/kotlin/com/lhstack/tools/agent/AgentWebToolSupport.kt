package com.lhstack.tools.agent

import com.intellij.util.xmlb.annotations.Tag
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Tag("web-search-engine")
class AgentWebSearchEngineState {
    var name: String = ""
    var address: String = ""

    override fun toString(): String {
        return if (address.isBlank()) name else "$name - $address"
    }
}

object AgentWebToolSupport {
    const val QUERY_PLACEHOLDER = "{query}"

    val defaultSearchEngineIds: List<String> = listOf("bing", "baidu")

    val defaultSearchEngines: List<AgentWebSearchEngineState> = listOf(
        searchEngine("Bing", "https://www.bing.com/search?q=$QUERY_PLACEHOLDER&setlang=zh-CN"),
        searchEngine("Baidu", "https://www.baidu.com/s?wd=$QUERY_PLACEHOLDER"),
    )

    fun normalizeSearchEngineIds(raw: Collection<String>?): MutableList<String> {
        val normalized = raw.orEmpty()
            .mapNotNull { legacySearchEngineForId(it)?.name?.lowercase() }
            .distinct()
            .toMutableList()
        if (normalized.isEmpty()) {
            normalized.addAll(defaultSearchEngineIds)
        }
        return normalized
    }

    fun normalizeSearchEngines(
        raw: Collection<AgentWebSearchEngineState>?,
        legacyIds: Collection<String>? = null,
    ): MutableList<AgentWebSearchEngineState> {
        val normalized = raw.orEmpty()
            .mapNotNull { normalizeSearchEngine(it) }
            .distinctBy { "${it.name.lowercase()}|${it.address}" }
            .toMutableList()
        if (normalized.isNotEmpty()) {
            return normalized
        }
        val legacy = legacyIds.orEmpty()
            .mapNotNull { legacySearchEngineForId(it) }
            .map { copySearchEngine(it) }
            .distinctBy { it.name.lowercase() }
            .toMutableList()
        return legacy.ifEmpty { defaultSearchEngines.map { copySearchEngine(it) }.toMutableList() }
    }

    fun buildSearchUrl(address: String, query: String): String {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val trimmed = address.trim()
        if (trimmed.contains(QUERY_PLACEHOLDER)) {
            return trimmed.replace(QUERY_PLACEHOLDER, encodedQuery)
        }
        val separator = if (trimmed.contains("?")) "&" else "?"
        return "$trimmed${separator}q=$encodedQuery"
    }

    fun searchEngineDisplayNames(raw: Collection<AgentWebSearchEngineState>?): String {
        return normalizeSearchEngines(raw).joinToString("、") { it.name }
    }

    fun copySearchEngine(source: AgentWebSearchEngineState): AgentWebSearchEngineState {
        return searchEngine(source.name, source.address)
    }

    fun normalizeSearchEngine(source: AgentWebSearchEngineState): AgentWebSearchEngineState? {
        if (isGoogleSearchEngine(source)) {
            return null
        }
        val address = normalizeAddress(source.address)
        if (address.isBlank()) {
            return null
        }
        val name = source.name.trim().ifBlank { defaultNameForAddress(address) }
        return searchEngine(name, address)
    }

    fun defaultNameForAddress(address: String): String {
        return runCatching {
            URI.create(address).host.orEmpty()
                .removePrefix("www.")
                .substringBefore('.')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }.getOrDefault("").ifBlank { "搜索引擎" }
    }

    private fun legacySearchEngineForId(id: String?): AgentWebSearchEngineState? {
        return when (id?.trim()?.lowercase()) {
            "bing" -> defaultSearchEngines[0]
            "baidu" -> defaultSearchEngines[1]
            else -> null
        }
    }

    private fun isGoogleSearchEngine(source: AgentWebSearchEngineState): Boolean {
        val name = source.name.trim().lowercase()
        val address = source.address.trim().lowercase()
        return name == "google" || address.contains("google.")
    }

    private fun normalizeAddress(address: String): String {
        val trimmed = address.trim()
        if (trimmed.isBlank()) {
            return ""
        }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun searchEngine(name: String, address: String): AgentWebSearchEngineState {
        return AgentWebSearchEngineState().apply {
            this.name = name.trim()
            this.address = address.trim()
        }
    }
}
