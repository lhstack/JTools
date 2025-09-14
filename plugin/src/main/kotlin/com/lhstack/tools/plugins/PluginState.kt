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