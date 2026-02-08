package com.lhstack.tools.plugins

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.annotations.OptionTag
import com.lhstack.tools.converter.JsonConverter
import com.lhstack.tools.agent.AgentSessionState

@Service
@State(name = "data", storages = [Storage("ToolsPluginState.xml")])
class PluginState : PersistentStateComponent<PluginState.State> {

    private var state = State()

    companion object {
        fun getInstance() = service<PluginState>()

    }

    class State {
        //插件保存路径
        var pluginBasePath: String = ""

        var consoleLogEnabled: Boolean = true

        // 智能体 OpenAPI 配置
        var agentOpenApiKey: String = ""
        var agentOpenApiBaseUrl: String = ""
        var agentModel: String = "gpt-4o-mini"
        var agentModels: MutableList<String> = mutableListOf("gpt-4o-mini")
        var agentSessions: MutableList<AgentSessionState> = mutableListOf()
        var agentActiveSessionId: String = ""
        var agentMaxToolIterations: Int = 30
        var agentToolTimeoutMs: Int = 120_000
        var agentMcpEnabled: Boolean = true
        var agentMcpServers: MutableList<com.lhstack.tools.agent.McpServerState> = mutableListOf()

        //插件信息 key=pluginId value=插件信息
        @field:OptionTag(converter = JsonConverter::class)
        var plugins = hashMapOf<String, PluginInfo>()

    }

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        val rawServers = state.agentMcpServers.toMutableList()
        com.lhstack.tools.agent.McpSupport.cleanServers(rawServers)
        state.agentMcpServers = rawServers
        state.agentSessions = state.agentSessions.filterIsInstance<AgentSessionState>().toMutableList()
        this.state = state
    }
}

fun Any.pluginState() = PluginState.getInstance().state
