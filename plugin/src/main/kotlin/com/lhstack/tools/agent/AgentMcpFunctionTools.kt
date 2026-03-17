package com.lhstack.tools.agent

import com.lhstack.tools.plugins.PluginState

data class AgentFunctionResult<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: String? = null,
)

data class AgentMcpAddServerInput(
    val name: String,
    val enabled: Boolean = true,
    val transport: String,
    val stdioCommand: String? = null,
    val stdioArgs: List<String>? = null,
    val stdioEnv: Map<String, String>? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val authType: String? = null,
    val authHeaderName: String? = null,
    val authHeaderValue: String? = null,
    val authUsername: String? = null,
    val authPassword: String? = null,
    val authQueryParam: String? = null,
    val authQueryValue: String? = null,
    val disabledTools: List<String>? = null,
)

data class AgentMcpUpdateServerInput(
    val serverId: String? = null,
    val name: String? = null,
    val enabled: Boolean? = null,
    val transport: String? = null,
    val stdioCommand: String? = null,
    val stdioArgs: List<String>? = null,
    val stdioEnv: Map<String, String>? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val authType: String? = null,
    val authHeaderName: String? = null,
    val authHeaderValue: String? = null,
    val authUsername: String? = null,
    val authPassword: String? = null,
    val authQueryParam: String? = null,
    val authQueryValue: String? = null,
    val disabledTools: List<String>? = null,
)

object AgentMcpFunctionTools {

    fun addServer(state: PluginState.State, input: AgentMcpAddServerInput): AgentFunctionResult<Map<String, Any?>> {
        val normalizedName = input.name.trim()
        if (normalizedName.isBlank()) {
            return AgentFunctionResult(false, error = "name 不能为空")
        }
        if (McpSupport.safeServers(state.agentMcpServers).any { it.name == normalizedName }) {
            return AgentFunctionResult(false, error = "MCP 服务器已存在: $normalizedName")
        }
        val server = McpSupport.normalizeServer(McpServerState().apply {
            name = normalizedName
            enabled = input.enabled
            transport = input.transport
            stdioCommand = input.stdioCommand.orEmpty()
            stdioArgs = input.stdioArgs.orEmpty().toMutableList()
            stdioEnv = input.stdioEnv.orEmpty().toMutableMap()
            url = input.url.orEmpty()
            headers = input.headers.orEmpty().toMutableMap()
            authType = input.authType.orEmpty()
            authHeaderName = input.authHeaderName.orEmpty()
            authHeaderValue = input.authHeaderValue.orEmpty()
            authUsername = input.authUsername.orEmpty()
            authPassword = input.authPassword.orEmpty()
            authQueryParam = input.authQueryParam.orEmpty()
            authQueryValue = input.authQueryValue.orEmpty()
            disabledTools = input.disabledTools.orEmpty().toMutableList()
        })
        if (!validateServer(server)) {
            return AgentFunctionResult(false, error = invalidReason(server))
        }
        state.agentMcpServers.add(server)
        return AgentFunctionResult(true, summarize(server))
    }

    fun updateServer(
        state: PluginState.State,
        input: AgentMcpUpdateServerInput,
        onInvalidate: (String) -> Unit = {},
    ): AgentFunctionResult<Map<String, Any?>> {
        val server = findServer(state, input.serverId, input.name)
            ?: return AgentFunctionResult(false, error = "未找到 MCP 服务器")
        input.name?.let { updated ->
            val normalized = updated.trim()
            if (normalized.isBlank()) {
                return AgentFunctionResult(false, error = "name 不能为空")
            }
            if (McpSupport.safeServers(state.agentMcpServers).any { it.id != server.id && it.name == normalized }) {
                return AgentFunctionResult(false, error = "MCP 服务器已存在: $normalized")
            }
            server.name = normalized
        }
        input.enabled?.let { server.enabled = it }
        input.transport?.let { server.transport = it }
        input.stdioCommand?.let { server.stdioCommand = it }
        input.stdioArgs?.let { server.stdioArgs = it.toMutableList() }
        input.stdioEnv?.let { server.stdioEnv = it.toMutableMap() }
        input.url?.let { server.url = it }
        input.headers?.let { server.headers = it.toMutableMap() }
        input.authType?.let { server.authType = it }
        input.authHeaderName?.let { server.authHeaderName = it }
        input.authHeaderValue?.let { server.authHeaderValue = it }
        input.authUsername?.let { server.authUsername = it }
        input.authPassword?.let { server.authPassword = it }
        input.authQueryParam?.let { server.authQueryParam = it }
        input.authQueryValue?.let { server.authQueryValue = it }
        input.disabledTools?.let { server.disabledTools = it.toMutableList() }
        McpSupport.normalizeServer(server)
        if (!validateServer(server)) {
            return AgentFunctionResult(false, error = invalidReason(server))
        }
        onInvalidate(server.id)
        return AgentFunctionResult(true, summarize(server))
    }

    fun deleteServer(
        state: PluginState.State,
        serverIdOrName: String,
        onInvalidate: (String) -> Unit = {},
    ): AgentFunctionResult<Map<String, Any?>> {
        val server = findServer(state, serverIdOrName, serverIdOrName)
            ?: return AgentFunctionResult(false, error = "未找到 MCP 服务器")
        state.agentMcpServers.removeIf { it.id == server.id }
        onInvalidate(server.id)
        return AgentFunctionResult(true, summarize(server))
    }

    fun setEnabled(
        state: PluginState.State,
        serverIdOrName: String,
        enabled: Boolean,
        onInvalidate: (String) -> Unit = {},
    ): AgentFunctionResult<Map<String, Any?>> {
        val server = findServer(state, serverIdOrName, serverIdOrName)
            ?: return AgentFunctionResult(false, error = "未找到 MCP 服务器")
        server.enabled = enabled
        McpSupport.normalizeServer(server)
        onInvalidate(server.id)
        return AgentFunctionResult(true, summarize(server))
    }

    fun testServer(
        state: PluginState.State,
        serverIdOrName: String,
        tester: (McpServerState) -> Map<String, Any?>,
    ): AgentFunctionResult<Map<String, Any?>> {
        val server = findServer(state, serverIdOrName, serverIdOrName)
            ?: return AgentFunctionResult(false, error = "未找到 MCP 服务器")
        return runCatching { tester(server) }
            .fold(
                onSuccess = { AgentFunctionResult(true, it) },
                onFailure = { AgentFunctionResult(false, error = it.message ?: "MCP 测试失败") }
            )
    }

    private fun findServer(state: PluginState.State, serverId: String?, name: String?): McpServerState? {
        val normalizedId = serverId?.trim().orEmpty()
        val normalizedName = name?.trim().orEmpty()
        return McpSupport.safeServers(state.agentMcpServers).firstOrNull { server ->
            McpSupport.ensureServerId(server)
            (normalizedId.isNotBlank() && server.id == normalizedId) ||
                (normalizedName.isNotBlank() && server.name == normalizedName)
        }
    }

    private fun validateServer(server: McpServerState): Boolean {
        return when (McpTransportType.fromId(server.transport)) {
            McpTransportType.STDIO -> server.stdioCommand.isNotBlank()
            McpTransportType.SSE, McpTransportType.STREAMABLE_HTTP -> server.url.isNotBlank()
        }
    }

    private fun invalidReason(server: McpServerState): String {
        return when (McpTransportType.fromId(server.transport)) {
            McpTransportType.STDIO -> "stdioCommand 不能为空"
            McpTransportType.SSE, McpTransportType.STREAMABLE_HTTP -> "url 不能为空"
        }
    }

    private fun summarize(server: McpServerState): Map<String, Any?> {
        return mapOf(
            "id" to server.id,
            "name" to server.name,
            "enabled" to server.enabled,
            "transport" to server.transport,
            "url" to server.url
        )
    }
}
