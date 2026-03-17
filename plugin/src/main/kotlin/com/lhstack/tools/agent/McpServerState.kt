package com.lhstack.tools.agent

import com.intellij.util.xmlb.annotations.Tag

@Tag("mcp-server")
class McpServerState {
    var id: String = ""
    var name: String = "MCP Server"
    var enabled: Boolean = true
    var transport: String = McpTransportType.STDIO.id
    var stdioCommand: String = ""
    var stdioArgs: MutableList<String> = mutableListOf()
    var stdioEnv: MutableMap<String, String> = mutableMapOf()
    var url: String = ""
    var headers: MutableMap<String, String> = mutableMapOf()
    var authType: String = McpAuthType.NONE.id
    var authHeaderName: String = "Authorization"
    var authHeaderValue: String = ""
    var authUsername: String = ""
    var authPassword: String = ""
    var authQueryParam: String = "token"
    var authQueryValue: String = ""
    var disabledTools: MutableList<String> = mutableListOf()
}

enum class McpTransportType(val id: String) {
    STDIO("stdio"),
    SSE("sse"),
    STREAMABLE_HTTP("streamable-http");

    companion object {
        fun fromId(id: String?): McpTransportType {
            val normalized = id?.trim()?.lowercase().orEmpty()
            return when (normalized) {
                "sse" -> SSE
                "streamablehttp", "streamable_http", "streamable-http", "streamable" -> STREAMABLE_HTTP
                "stdio" -> STDIO
                else -> STDIO
            }
        }
    }
}

enum class McpAuthType(val id: String) {
    NONE("none"),
    HEADER("header"),
    BASIC("basic"),
    QUERY("query");

    companion object {
        fun fromId(id: String?): McpAuthType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NONE
        }
    }
}
