package com.lhstack.tools.plugins

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.annotations.OptionTag
import com.lhstack.tools.converter.JsonConverter

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
        var agentMaxToolIterations: Int = 30
        var agentToolTimeoutMs: Int = 120_000

        //插件信息 key=pluginId value=插件信息
        @field:OptionTag(converter = JsonConverter::class)
        var plugins = hashMapOf<String, PluginInfo>()

    }

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        this.state = state
    }
}

fun Any.pluginState() = PluginState.getInstance().state
