package com.lhstack.tools.agent

import io.agentscope.core.message.Msg
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ThinkingBlock

object AgentReasoningSupport {
    fun extractThinking(message: Msg?): String? {
        if (message == null) {
            return null
        }
        return message.getContentBlocks(ThinkingBlock::class.java)
            .mapNotNull { block ->
                block.thinking.takeIf { it.isNotBlank() }
                    ?: stringifyReasoningDetails(block.metadata?.get(ThinkingBlock.METADATA_REASONING_DETAILS))
            }
            .joinToString("\n")
            .trim()
            .ifBlank { null } ?: message.getContentBlocks(TextBlock::class.java).mapNotNull { block ->
            block.text
        }.joinToString("\n")
            .trim()
    }

    private fun stringifyReasoningDetails(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value.trim().ifBlank { null }
            is Iterable<*> -> value.joinToString("\n") { it?.toString().orEmpty() }.trim().ifBlank { null }
            is Array<*> -> value.joinToString("\n") { it?.toString().orEmpty() }.trim().ifBlank { null }
            else -> value.toString().trim().ifBlank { null }
        }
    }
}
