package com.lhstack.tools.agent

class AgentSessionState {
    var id: String = ""
    var projectKey: String = ""
    var title: String = ""
    var autoTitle: Boolean = true
    var model: String = ""
    var messages: MutableList<String> = mutableListOf()
    var renders: MutableList<AgentRenderState> = mutableListOf()
}

class AgentRenderState {
    var role: String = ""
    var content: String = ""
    var collapsible: Boolean = false
    var collapsedByDefault: Boolean = false
}
