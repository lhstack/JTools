package com.lhstack.tools.agent

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

object AgentWebTools {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    fun fetch(url: String, prompt: String): Map<String, Any?> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "idea-tools-agent/1.0")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val body = response.body()
        return mapOf(
            "ok" to true,
            "url" to url,
            "prompt" to prompt,
            "statusCode" to response.statusCode(),
            "content" to stripHtml(body).take(20_000)
        )
    }

    fun search(query: String, allowedDomains: List<String>, blockedDomains: List<String>): Map<String, Any?> {
        val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, StandardCharsets.UTF_8)}"
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "idea-tools-agent/1.0")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val results = parseDuckDuckGo(response.body())
            .filter { result ->
                (allowedDomains.isEmpty() || allowedDomains.any { result["url"].toString().contains(it) }) &&
                    blockedDomains.none { result["url"].toString().contains(it) }
            }
            .take(8)
        return mapOf(
            "ok" to true,
            "query" to query,
            "results" to results,
            "statusCode" to response.statusCode()
        )
    }

    private fun parseDuckDuckGo(body: String): List<Map<String, String>> {
        val regex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""")
        return regex.findAll(body).map { match ->
            mapOf(
                "url" to htmlDecode(match.groupValues[1]),
                "title" to stripHtml(match.groupValues[2]).trim(),
            )
        }.toList()
    }

    private fun stripHtml(text: String): String {
        return htmlDecode(text.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
    }

    private fun htmlDecode(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }
}
