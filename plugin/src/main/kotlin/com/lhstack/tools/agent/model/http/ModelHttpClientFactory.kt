package com.lhstack.tools.agent.model.http

import com.lhstack.tools.db.service.SettingService
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 按 provider base_url + proxy_url 复用 OkHttpClient。
 *
 * provider id 不是正确缓存 key：供应商请求地址可被修改，同一个 id 下继续复用旧 client
 * 会保留旧连接池语义。base_url/proxy_url 任一变化都必须得到新的 client。
 */
object ModelHttpClientFactory {

    private data class ClientKey(val baseUrl: String, val proxyUrl: String?, val dnsServers: String?)

    private val executors = ConcurrentHashMap<ClientKey, ModelHttpExecutor>()

    fun executorFor(baseUrl: String, proxyUrl: String? = null): ModelHttpExecutor {
        val dnsServers = SettingService.setting("model.dns_servers")
        val key = ClientKey(
            normalizeBaseUrl(baseUrl),
            normalizeProxyUrl(proxyUrl),
            ModelDns.normalizeServers(dnsServers),
        )
        return executors.computeIfAbsent(key) {
            var builder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
            key.proxyUrl?.let { builder = builder.proxy(proxyFor(it)) }
            ModelDns.resolverFor(key.dnsServers)?.let { builder = builder.dns(it) }
            ModelHttpExecutor(builder.build())
        }
    }

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
