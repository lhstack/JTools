package com.lhstack.tools.agent

import java.util.UUID

object McpSupport {
    fun ensureServerId(state: McpServerState?): String? {
        if (state == null) {
            return null
        }
        if (state.id.isBlank()) {
            state.id = UUID.randomUUID().toString()
        }
        return state.id
    }

    fun cleanServers(servers: MutableList<*>) {
        val iterator = servers.listIterator()
        while (iterator.hasNext()) {
            if (iterator.next() == null) {
                iterator.remove()
            }
        }
    }

    fun safeServers(servers: List<*>): List<McpServerState> {
        return servers.filterIsInstance<McpServerState>()
    }

    fun normalizeServer(server: McpServerState): McpServerState {
        ensureServerId(server)
        server.name = server.name.trim().ifBlank { "MCP Server" }
        server.transport = McpTransportType.fromId(server.transport).id
        server.stdioCommand = server.stdioCommand.trim()
        server.stdioArgs = server.stdioArgs.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toMutableList()
        server.stdioEnv = server.stdioEnv
            .mapNotNull { (key, value) -> key.trim().takeIf(String::isNotEmpty)?.let { it to value.trim() } }
            .toMap()
            .toMutableMap()
        server.url = server.url.trim()
        server.headers = server.headers
            .mapNotNull { (key, value) -> key.trim().takeIf(String::isNotEmpty)?.let { it to value.trim() } }
            .toMap()
            .toMutableMap()
        server.authType = McpAuthType.fromId(server.authType).id
        server.authHeaderName = server.authHeaderName.trim().ifBlank { "Authorization" }
        server.authHeaderValue = server.authHeaderValue.trim()
        server.authUsername = server.authUsername.trim()
        server.authPassword = server.authPassword.trim()
        server.authQueryParam = server.authQueryParam.trim().ifBlank { "token" }
        server.authQueryValue = server.authQueryValue.trim()
        server.disabledTools = server.disabledTools.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct().toMutableList()
        return server
    }

    fun parseArgs(text: String): MutableList<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return mutableListOf()
        }
        return if (trimmed.contains('\n')) {
            trimmed.lines().mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() } }.toMutableList()
        } else {
            trimmed.split(Regex("\\s+")).mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() } }.toMutableList()
        }
    }

    fun formatArgs(args: List<String>): String {
        return args.joinToString("\n")
    }

    fun parseKeyValueLines(text: String): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                return@forEach
            }
            val index = trimmed.indexOf('=')
            if (index <= 0) {
                return@forEach
            }
            val key = trimmed.substring(0, index).trim()
            val value = trimmed.substring(index + 1).trim()
            if (key.isNotEmpty()) {
                map[key] = value
            }
        }
        return map
    }

    fun formatKeyValueLines(map: Map<String, String>): String {
        return map.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }
}
