package com.lhstack.tools.agent

import io.agentscope.core.message.Msg
import io.agentscope.core.message.ThinkingBlock

object AgentReasoningSupport {
    fun extractThinking(message: Msg?): String? {
        if (message == null) {
            return null
        }
        return message.getContentBlocks(ThinkingBlock::class.java)
            .joinToString("\n") { it.thinking }
            .trim()
            .ifBlank { null }
    }
}
