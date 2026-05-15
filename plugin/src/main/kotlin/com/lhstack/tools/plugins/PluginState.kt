package com.lhstack.tools.plugins

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.annotations.OptionTag
import com.lhstack.tools.agent.AgentProxyType
import com.lhstack.tools.agent.AgentSystemPromptState
import com.lhstack.tools.agent.AgentSystemPromptSupport
import com.lhstack.tools.agent.AgentWebSearchEngineState
import com.lhstack.tools.agent.AgentWebToolSupport
import com.lhstack.tools.converter.JsonConverter
import com.lhstack.tools.agent.AgentSkillState
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
        var agentModel: String = ""
        var agentProviders: MutableList<com.lhstack.tools.agent.AgentProviderState> = mutableListOf()
        var agentActiveProviderId: String = ""
        var agentSessions: MutableList<AgentSessionState> = mutableListOf()
        var agentSystemPrompts: MutableList<AgentSystemPromptState> = mutableListOf()
        var agentSkills: MutableList<AgentSkillState> = mutableListOf()
        var agentActiveSessionId: String = ""
        var agentActiveSessionIdByProject: MutableMap<String, String> = mutableMapOf()
        var agentMaxToolIterations: Int = 30
        var agentToolTimeoutMs: Int = 120_000
        var agentMcpEnabled: Boolean = true
        var agentMcpServers: MutableList<com.lhstack.tools.agent.McpServerState> = mutableListOf()
        var webToolProxyEnabled: Boolean = false
        var webToolProxyType: String = AgentProxyType.HTTP.id
        var webToolProxyHost: String = ""
        var webToolProxyPort: Int = 0
        var webSearchEngines: MutableList<String> = AgentWebToolSupport.defaultSearchEngineIds.toMutableList()
        var webSearchEngineConfigs: MutableList<AgentWebSearchEngineState> = mutableListOf()

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
        state.agentSystemPrompts = AgentSystemPromptSupport.normalizePrompts(state.agentSystemPrompts)
        state.agentSkills = com.lhstack.tools.agent.AgentSkillSupport.normalizeSkills(state.agentSkills)
        state.webToolProxyType = AgentProxyType.fromId(state.webToolProxyType).id
        state.webToolProxyHost = state.webToolProxyHost.trim()
        if (state.webToolProxyPort !in 1..65535) {
            state.webToolProxyPort = 0
        }
        state.webSearchEngines = AgentWebToolSupport.normalizeSearchEngineIds(state.webSearchEngines)
        state.webSearchEngineConfigs = AgentWebToolSupport.normalizeSearchEngines(
            state.webSearchEngineConfigs,
            state.webSearchEngines
        )
        val providers = state.agentProviders.filterIsInstance<com.lhstack.tools.agent.AgentProviderState>().toMutableList()
        if (providers.isEmpty()) {
            val legacyKey = state.agentOpenApiKey.trim()
            val legacyBaseUrl = state.agentOpenApiBaseUrl.trim()
            val provider = com.lhstack.tools.agent.AgentProviderState().apply {
                name = "OpenAI"
                type = com.lhstack.tools.agent.AgentProviderType.OPENAI.id
                providerType = com.lhstack.tools.agent.AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE
                vendorTemplate = com.lhstack.tools.agent.AgentProviderCatalog.TEMPLATE_OPENAI
                apiKey = legacyKey
                baseUrl = if (legacyBaseUrl.isBlank()) {
                    com.lhstack.tools.agent.AgentProviderSupport.defaultBaseUrl(
                        com.lhstack.tools.agent.AgentProviderType.OPENAI
                    )
                } else {
                    legacyBaseUrl
                }
            }
            providers.add(provider)
        }
        val legacyActiveModel = state.agentModel.trim()
        val legacyTargetId = state.agentActiveProviderId.ifBlank { providers.firstOrNull()?.id.orEmpty() }
        val legacyTarget = providers.firstOrNull { it.id == legacyTargetId } ?: providers.firstOrNull()
        if (legacyTarget != null && legacyTarget.activeModel.isBlank() && legacyActiveModel.isNotBlank()) {
            legacyTarget.activeModel = legacyActiveModel
        }
        providers.forEach { com.lhstack.tools.agent.AgentProviderSupport.normalizeProvider(it) }
        state.agentProviders = providers
        state.agentSessions.forEach { session ->
            AgentSystemPromptSupport.syncSessionSystemPrompt(session, state.agentSystemPrompts)
        }
        if (state.agentActiveProviderId.isBlank() && providers.isNotEmpty()) {
            state.agentActiveProviderId = providers.first().id
        }
        this.state = state
    }
}

fun Any.pluginState() = PluginState.getInstance().state
