package com.lhstack.tools.agent

import org.htmlunit.BrowserVersion
import org.htmlunit.ProxyConfig
import org.htmlunit.Page
import org.htmlunit.TextPage
import org.htmlunit.UnexpectedPage
import org.htmlunit.WebClient
import org.htmlunit.html.HtmlPage
import com.lhstack.tools.plugins.PluginState
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.math.min

object AgentWebTools {
    private const val MAX_FETCH_TEXT_LENGTH = 30_000
    private const val MAX_SEARCH_BODY_LENGTH = 1_500_000
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    fun fetch(url: String, prompt: String): Map<String, Any?> {
        val normalizedUrl = normalizeUrl(url)
        return runCatching { fetchRendered(normalizedUrl, prompt) }
            .getOrElse { error ->
                mapOf(
                    "ok" to false,
                    "url" to normalizedUrl,
                    "prompt" to prompt,
                    "error" to (error.message ?: "fetch failed")
                )
            }
    }

    fun search(query: String, allowedDomains: List<String>, blockedDomains: List<String>): Map<String, Any?> {
        val engines = configuredSearchEngines()
        val filters = DomainFilters(allowedDomains.normalizeDomains(), blockedDomains.normalizeDomains())
        val attempts = mutableListOf<Map<String, Any?>>()
        engines.forEach { engine ->
            val attempt = runCatching {
                val response = request(engine.searchUrl(query), Duration.ofSeconds(20))
                val body = response.body().take(MAX_SEARCH_BODY_LENGTH)
                val document = Jsoup.parse(body, response.uri().toString())
                val parsed = engine.parse(document)
                    .distinctBy { it.url }
                    .filter { filters.accepts(it.url) }
                    .take(8)
                attempts += mapOf(
                    "engine" to engine.id,
                    "ok" to (response.statusCode() in 200..399),
                    "statusCode" to response.statusCode(),
                    "resultCount" to parsed.size,
                    "url" to response.uri().toString()
                )
                parsed
            }.getOrElse { error ->
                attempts += mapOf(
                    "engine" to engine.id,
                    "ok" to false,
                    "error" to (error.message ?: "search failed")
                )
                emptyList()
            }
            if (attempt.isNotEmpty()) {
                return mapOf(
                    "ok" to true,
                    "query" to query,
                    "engine" to engine.id,
                    "results" to attempt.map { it.toMap() },
                    "attempts" to attempts
                )
            }
        }
        return mapOf(
            "ok" to false,
            "query" to query,
            "engine" to null,
            "results" to emptyList<Map<String, String>>(),
            "attempts" to attempts,
            "error" to "未从 ${AgentWebToolSupport.searchEngineDisplayNames(engines.map { it.state })} 获取到可用搜索结果"
        )
    }

    private fun fetchRendered(url: String, prompt: String): Map<String, Any?> {
        WebClient(BrowserVersion.CHROME).use { webClient ->
            applyProxyIfNeeded(webClient.options)
            webClient.options.isJavaScriptEnabled = true
            webClient.options.isCssEnabled = false
            webClient.options.isThrowExceptionOnScriptError = false
            webClient.options.isThrowExceptionOnFailingStatusCode = false
            webClient.options.timeout = 30_000
            webClient.options.isRedirectEnabled = true
            webClient.addRequestHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            webClient.addRequestHeader("User-Agent", DEFAULT_USER_AGENT)

            val page = webClient.getPage<Page>(url)
            webClient.waitForBackgroundJavaScript(3_000)
            val response = page.webResponse
            val contentType = response.contentType.orEmpty().lowercase()
            val finalUrl = page.url.toString()
            val statusCode = response.statusCode
            val body = when (page) {
                is HtmlPage -> page.asNormalizedText()
                is TextPage -> page.content
                is UnexpectedPage -> response.contentAsString
                else -> response.contentAsString
            }
            val renderedHtml = (page as? HtmlPage)?.asXml()
            val kind = classifyContent(contentType, body)
            val content = when (kind) {
                "html" -> normalizeText(body)
                "text", "json", "xml" -> body.trim()
                else -> ""
            }
            return mapOf(
                "ok" to (statusCode in 200..399),
                "url" to url,
                "finalUrl" to finalUrl,
                "prompt" to prompt,
                "statusCode" to statusCode,
                "contentType" to contentType,
                "contentKind" to kind,
                "content" to content.take(MAX_FETCH_TEXT_LENGTH),
                "renderedHtml" to renderedHtml?.take(MAX_FETCH_TEXT_LENGTH),
                "truncated" to (content.length > MAX_FETCH_TEXT_LENGTH),
                "renderedHtmlTruncated" to ((renderedHtml?.length ?: 0) > MAX_FETCH_TEXT_LENGTH),
                "byteLength" to min(response.contentAsString.length, 1_048_576)
            )
        }
    }

    private fun request(url: String, timeout: Duration): HttpResponse<String> {
        val clientBuilder = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
        buildProxySelector()?.let { clientBuilder.proxy(it) }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .GET()
            .build()
        return clientBuilder.build().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    private fun classifyContent(contentType: String, body: String): String {
        return when {
            contentType.contains("text/html") || body.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) -> "html"
            contentType.contains("application/json") || contentType.contains("+json") -> "json"
            contentType.contains("application/xml") || contentType.contains("text/xml") || contentType.contains("+xml") -> "xml"
            contentType.startsWith("text/") -> "text"
            else -> "binary"
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return "https://$trimmed"
    }

    private fun List<String>.normalizeDomains(): Set<String> {
        return mapNotNull { value ->
            value.trim()
                .lowercase()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .removePrefix("www.")
                .takeIf { it.isNotBlank() }
        }.toSet()
    }

    private fun normalizeText(text: String): String {
        return text.replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun configuredSearchEngines(): List<SearchEngine> {
        val state = PluginState.getInstance().state
        return AgentWebToolSupport.normalizeSearchEngines(state.webSearchEngineConfigs, state.webSearchEngines)
            .map { SearchEngine(it) }
    }

    private fun buildProxySelector(): ProxySelector? {
        val proxyState = PluginState.getInstance().state
        if (!proxyState.webToolProxyEnabled) {
            return null
        }
        val host = proxyState.webToolProxyHost.trim()
        val port = proxyState.webToolProxyPort
        if (host.isBlank() || port !in 1..65535) {
            return null
        }
        val proxyType = AgentProxyType.fromId(proxyState.webToolProxyType)
        val proxy = Proxy(
            if (proxyType == AgentProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            InetSocketAddress(host, port)
        )
        return object : ProxySelector() {
            override fun select(uri: URI): MutableList<Proxy> {
                return mutableListOf(proxy)
            }

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
            }
        }
    }

    private fun applyProxyIfNeeded(options: org.htmlunit.WebClientOptions) {
        val proxyState = PluginState.getInstance().state
        if (!proxyState.webToolProxyEnabled) {
            return
        }
        val host = proxyState.webToolProxyHost.trim()
        val port = proxyState.webToolProxyPort
        if (host.isBlank() || port !in 1..65535) {
            return
        }
        val proxyType = AgentProxyType.fromId(proxyState.webToolProxyType)
        options.proxyConfig = ProxyConfig(
            host,
            port,
            if (proxyType == AgentProxyType.SOCKS) "socks" else "http",
            proxyType == AgentProxyType.SOCKS
        )
    }

    private data class DomainFilters(
        val allowedDomains: Set<String>,
        val blockedDomains: Set<String>,
    ) {
        fun accepts(url: String): Boolean {
            val host = runCatching {
                URI.create(url).host.orEmpty().lowercase().removePrefix("www.")
            }.getOrDefault("")
            if (host.isBlank()) {
                return false
            }
            return (allowedDomains.isEmpty() || allowedDomains.any { host == it || host.endsWith(".$it") }) &&
                blockedDomains.none { host == it || host.endsWith(".$it") }
        }
    }

    private class SearchEngine(val state: AgentWebSearchEngineState) {
        val id: String = state.name
        private val normalizedName = state.name.trim().lowercase()
        fun searchUrl(query: String): String {
            return AgentWebToolSupport.buildSearchUrl(state.address, query)
        }

        fun parse(document: Document): List<SearchResult> {
            return when {
                normalizedName == "bing" || state.address.contains("bing.com", ignoreCase = true) -> parseBing(document)
                normalizedName == "baidu" || state.address.contains("baidu.com", ignoreCase = true) -> parseBaidu(document)
                else -> parseGeneric(document, id)
            }
        }

        private fun parseBing(document: Document): List<SearchResult> {
            val primary = document.select("li.b_algo").mapNotNull { item ->
                val link = item.selectFirst("h2 a[href]") ?: return@mapNotNull null
                SearchResult(
                    title = link.cleanText(),
                    url = link.absUrl("href"),
                    snippet = item.selectFirst(".b_caption p, p")?.cleanText().orEmpty(),
                    source = id
                )
            }
            return primary.ifEmpty { parseGeneric(document, id) }
        }

        private fun parseBaidu(document: Document): List<SearchResult> {
            val primary = document.select("h3 a[href], .result a[href]").mapNotNull { link ->
                SearchResult(
                    title = link.cleanText(),
                    url = link.absUrl("href"),
                    snippet = link.parent()?.parent()?.cleanText().orEmpty().take(300),
                    source = id
                ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
            }
            return primary.ifEmpty { parseGeneric(document, id) }
        }

    }

    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String,
    ) {
        fun toMap(): Map<String, String> {
            return mapOf(
                "title" to title,
                "url" to url,
                "snippet" to snippet,
                "source" to source
            )
        }
    }

    private fun parseGeneric(document: Document, source: String): List<SearchResult> {
        return document.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href")
            val title = link.cleanText()
            if (title.length < 3 || url.isBlank() || isNoiseUrl(url)) {
                return@mapNotNull null
            }
            SearchResult(
                title = title.take(200),
                url = url,
                snippet = link.parent()?.cleanText().orEmpty().take(300),
                source = source
            )
        }
    }

    private fun isNoiseUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "javascript:",
            "mailto:",
            "tel:",
            "https://www.bing.com/search",
            "https://www.baidu.com/s?"
        ).any { lower.startsWith(it) }
    }

    private fun Element.cleanText(): String = normalizeText(text())

}
