package com.lhstack.tools.plugins

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.OptionTag
import com.lhstack.tools.converter.JsonConverter

@Service
@State(name = "ToolsPluginState", storages = [Storage("ToolsPluginState.xml")])
class PluginState : PersistentStateComponent<PluginState.State> {

    private var state = State()

    companion object {
        fun getInstance() = service<PluginState>()

        fun getInstance(project: Project) = project.service<PluginState>()
    }

    class State {
        //插件保存路径
        var pluginBasePath: String = "${System.getProperty("user.home")}/.ideaTools/plugins"

        //插件信息 key=pluginId value=插件信息
        @field:OptionTag(converter = JsonConverter::class)
        var plugins = hashMapOf<String, PluginInfo>()

        //js插件缓存
        var jsPluginCache = hashMapOf<String, HashMap<String,String>>()

    }

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        this.state = state
    }
}

fun Any.pluginState() = PluginState.getInstance().state

fun Project.projectPluginState() = PluginState.getInstance(this).state