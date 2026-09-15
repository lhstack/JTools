package com.lhstack.tools.agent.model.http

import com.lhstack.tools.db.service.SettingService
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 按 provider base_url + proxy_url + HTTP 运行时配置复用 OkHttpClient。
 * 超时、连接池、DNS 变化后必须拿到新 client，不能继续复用旧连接池。
 */
object ModelHttpClientFactory {

    private data class ClientKey(
        val baseUrl: String,
        val proxyUrl: String?,
        val dnsServers: String?,
        val requestTimeoutSecs: Long,
        val poolIdleTimeoutSecs: Long,
        val poolMaxIdlePerHost: Int,
    )

    private val executors = ConcurrentHashMap<ClientKey, ModelHttpExecutor>()

    fun executorFor(baseUrl: String, proxyUrl: String? = null): ModelHttpExecutor {
        val dnsServers = SettingService.setting("model.dns_servers")
        val requestTimeoutSecs = nonNegativeLong("model.http_request_timeout_secs", 0)
        val poolIdleTimeoutSecs = nonNegativeLong("model.http_pool_idle_timeout_secs", 120)
        val poolMaxIdlePerHost = nonNegativeInt("model.http_pool_max_idle_per_host", 32).coerceAtLeast(1)
        val key = ClientKey(
            normalizeBaseUrl(baseUrl),
            normalizeProxyUrl(proxyUrl),
            ModelDns.normalizeServers(dnsServers),
            requestTimeoutSecs,
            poolIdleTimeoutSecs,
            poolMaxIdlePerHost,
        )
        return executors.computeIfAbsent(key) {
            var builder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.MINUTES)
                .callTimeout(if (requestTimeoutSecs > 0) requestTimeoutSecs else 10 * 60, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(poolMaxIdlePerHost, poolIdleTimeoutSecs, TimeUnit.SECONDS))
                .retryOnConnectionFailure(true)
            key.proxyUrl?.let { builder = builder.proxy(proxyFor(it)) }
            ModelDns.resolverFor(key.dnsServers)?.let { builder = builder.dns(it) }
            ModelHttpExecutor(builder.build())
        }
    }

    private fun nonNegativeLong(key: String, default: Long): Long {
        val raw = SettingService.setting(key)?.trim().orEmpty()
        if (raw.isEmpty()) return default
        return raw.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("配置 `$key` 必须是非负整数")
    }

    private fun nonNegativeInt(key: String, default: Int): Int = nonNegativeLong(key, default.toLong()).toInt()

    private fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("供应商请求地址不能为空")

    private fun normalizeProxyUrl(proxyUrl: String?): String? =
        proxyUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    private fun proxyFor(proxyUrl: String): Proxy {
        val uri = URI(proxyUrl)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: throw IllegalArgumentException("代理地址缺少 host")
        val port = if (uri.port > 0) uri.port else defaultPort(scheme)
        return when (scheme) {
            "http", "https" -> Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
            "socks5", "socks5h" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
            else -> throw IllegalArgumentException("不支持的代理协议 `$scheme`，仅支持 http、https、socks5、socks5h")
        }
    }

    private fun defaultPort(scheme: String?): Int = when (scheme) {
        "http" -> 80
        "https" -> 443
        "socks5", "socks5h" -> 1080
        else -> throw IllegalArgumentException("代理地址缺少端口")
    }
}
