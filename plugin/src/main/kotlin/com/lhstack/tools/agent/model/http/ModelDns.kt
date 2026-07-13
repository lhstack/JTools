package com.lhstack.tools.agent.model.http

import okhttp3.Dns
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.Lookup
import org.xbill.DNS.Resolver
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.time.Duration

/** 模型 HTTP 自定义 DNS。读取 model.dns_servers 后，用 dnsjava 对 OkHttp Dns.lookup 发起 A/AAAA 查询。 */
object ModelDns {

    private const val DEFAULT_DNS_PORT = 53
    private val LOOKUP_TYPES = listOf(Type.A, Type.AAAA)

    fun resolverFor(dnsServers: String?): Dns? {
        val servers = parseServers(dnsServers)
        if (servers.isEmpty()) return null
        val resolvers = servers.map { resolverForServer(it) }
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = lookup(hostname, resolvers)
        }
    }

    fun normalizeServers(dnsServers: String?): String? =
        parseServers(dnsServers).joinToString(",") { "${it.address.hostAddress}:${it.port}" }.takeIf { it.isNotBlank() }

    private fun lookup(hostname: String, resolvers: List<Resolver>): List<InetAddress> {
        if (isIpLiteral(hostname)) {
            return listOf(InetAddress.getByName(hostname.trim().removeSurrounding("[", "]")))
        }

        val answers = linkedSetOf<InetAddress>()
        val failures = mutableListOf<String>()
        for (resolver in resolvers) {
            for (type in LOOKUP_TYPES) {
                try {
                    answers.addAll(lookupByType(hostname, resolver, type))
                } catch (e: Throwable) {
                    failures.add("${recordTypeName(type)} ${e.message ?: e.toString()}")
                }
            }
            if (answers.isNotEmpty()) return answers.toList()
        }
        throw UnknownHostException("自定义 DNS 解析 `$hostname` 失败: ${failures.joinToString("; ")}")
    }

    private fun lookupByType(hostname: String, resolver: Resolver, type: Int): List<InetAddress> {
        val lookup = Lookup(hostname.trimEnd('.'), type)
        lookup.setResolver(resolver)
        val records = lookup.run() ?: emptyArray()
        if (lookup.result != Lookup.SUCCESSFUL) {
            return emptyList()
        }
        return records.mapNotNull { record ->
            when (record) {
                is ARecord -> record.address
                is AAAARecord -> record.address
                else -> null
            }
        }
    }

    private fun resolverForServer(server: InetSocketAddress): Resolver =
        SimpleResolver(server.address.hostAddress).apply {
            setPort(server.port)
            setTimeout(Duration.ofSeconds(2))
        }

    private fun parseServers(value: String?): List<InetSocketAddress> =
        value.orEmpty()
            .split(',', '\n')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseServer(it) }
            .toList()

    private fun parseServer(value: String): InetSocketAddress {
        val hostPort = parseHostPort(value)
        require(isIpLiteral(hostPort.first)) { "DNS 服务器地址必须是 IP: $value" }
        val address = InetAddress.getByName(hostPort.first.trim().removeSurrounding("[", "]"))
        return InetSocketAddress(address, hostPort.second)
    }

    private fun isIpLiteral(value: String): Boolean {
        val host = value.trim().removeSurrounding("[", "]")
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return true
        return host.contains(':') && host.matches(Regex("""[0-9a-fA-F:.%]+"""))
    }

    private fun parseHostPort(value: String): Pair<String, Int> {
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            require(end > 0) { "DNS 服务器地址非法: $value" }
            val host = value.substring(1, end)
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: DEFAULT_DNS_PORT
            return host to port
        }
        val colonCount = value.count { it == ':' }
        if (colonCount == 1) {
            val host = value.substringBefore(':')
            val port = value.substringAfter(':').toIntOrNull() ?: DEFAULT_DNS_PORT
            return host to port
        }
        return value to DEFAULT_DNS_PORT
    }

    private fun recordTypeName(type: Int): String = when (type) {
        Type.A -> "A"
        Type.AAAA -> "AAAA"
        else -> type.toString()
    }
}
