package com.lhstack.tools.agent

import io.agentscope.core.message.ThinkingBlock

object AgentReasoningSupport {
    fun extractThinking(block: ThinkingBlock): String? {
        return block.thinking.takeIf { it.isNotBlank() }
            ?: stringifyReasoningDetails(block.metadata?.get(ThinkingBlock.METADATA_REASONING_DETAILS))
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
