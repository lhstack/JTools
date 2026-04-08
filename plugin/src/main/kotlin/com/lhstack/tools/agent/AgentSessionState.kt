package com.lhstack.tools.agent

import com.intellij.util.xmlb.annotations.Tag

enum class AgentConversationMode(val id: String, val displayName: String) {
    CHAT("chat", "Chat"),
    RESPONSES("responses", "Responses");

    companion object {
        fun fromId(id: String?): AgentConversationMode {
            return entries.firstOrNull { it.id == id } ?: CHAT
        }
    }
}

@Tag("agent-session")
class AgentSessionState {
    var id: String = ""
    var projectKey: String = ""
    var providerId: String = ""
    var systemPromptId: String = ""
    var title: String = ""
    var autoTitle: Boolean = true
    var model: String = ""
    var conversationMode: String = AgentConversationMode.CHAT.id
    var enabledSkillIds: MutableList<String> = mutableListOf()
    var messages: MutableList<String> = mutableListOf()
    var renders: MutableList<AgentRenderState> = mutableListOf()
    var draftAttachments: MutableList<AgentAttachmentState> = mutableListOf()
    var runtime: AgentSessionRuntimeState = AgentSessionRuntimeState()
}

@Tag("render-item")
class AgentRenderState {
    var role: String = ""
    var content: String = ""
    var collapsible: Boolean = false
    var collapsedByDefault: Boolean = false
    var attachments: MutableList<AgentAttachmentState> = mutableListOf()
    var toolEntries: MutableList<AgentToolRenderEntryState> = mutableListOf()
}

@Tag("tool-render-entry")
class AgentToolRenderEntryState {
    var id: String = ""
    var index: Int = 0
    var name: String = ""
    var startedAt: Long = 0L
    var status: String = ""
    var arguments: String = ""
    var result: String = ""
}
