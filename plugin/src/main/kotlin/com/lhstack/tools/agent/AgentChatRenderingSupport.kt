package com.lhstack.tools.agent

object AgentChatRenderingSupport {
    fun shouldRenderFinalAssistantContent(
        assistantStarted: Boolean,
        assistantContent: String?,
    ): Boolean {
        return !assistantStarted && !assistantContent.isNullOrBlank()
    }
}
