package com.lhstack.tools.agent

object AgentConversationModeSupport {
    const val responsesImplemented: Boolean = false

    fun selectorVisible(): Boolean = responsesImplemented

    fun availableModes(responsesEnabledForModel: Boolean): List<AgentConversationMode> {
        val modes = mutableListOf(AgentConversationMode.CHAT)
        if (responsesImplemented && responsesEnabledForModel) {
            modes.add(AgentConversationMode.RESPONSES)
        }
        return modes
    }
}
