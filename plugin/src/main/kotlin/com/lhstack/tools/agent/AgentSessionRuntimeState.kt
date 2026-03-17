package com.lhstack.tools.agent

import com.intellij.util.xmlb.annotations.Tag
import io.agentscope.core.memory.LongTermMemoryMode

enum class AgentShortTermMemoryType(val id: String) {
    AUTO_CONTEXT("auto_context"),
    IN_MEMORY("in_memory");

    companion object {
        fun fromId(id: String?): AgentShortTermMemoryType {
            return entries.firstOrNull { it.id == id } ?: AUTO_CONTEXT
        }
    }
}

enum class AgentLongTermMemoryModeType(val id: String, val sdkMode: LongTermMemoryMode?) {
    DISABLED("disabled", null),
    AGENT_CONTROL("agent_control", LongTermMemoryMode.AGENT_CONTROL),
    STATIC_CONTROL("static_control", LongTermMemoryMode.STATIC_CONTROL),
    BOTH("both", LongTermMemoryMode.BOTH);

    companion object {
        fun fromId(id: String?): AgentLongTermMemoryModeType {
            return entries.firstOrNull { it.id == id } ?: DISABLED
        }
    }
}

@Tag("session-runtime")
class AgentSessionRuntimeState {
    var shortTermMemoryType: String = AgentShortTermMemoryType.AUTO_CONTEXT.id
    var planModeEnabled: Boolean = false
    var longTermMemoryMode: String = AgentLongTermMemoryModeType.DISABLED.id
    var stateMemoryManaged: Boolean = true
    var stateToolkitManaged: Boolean = true
    var statePlanNotebookManaged: Boolean = true
    var statefulToolsManaged: Boolean = true
}
