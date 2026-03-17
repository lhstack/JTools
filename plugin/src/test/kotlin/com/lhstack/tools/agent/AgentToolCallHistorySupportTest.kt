package com.lhstack.tools.agent

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolCallHistorySupportTest {

    @Test
    fun `assistant tool call message and tool result use same tool call id`() {
        val assistant = AgentToolCallHistorySupport.assistantToolCallMessage(
            toolCallId = "tool-123",
            name = "read_skill_resource",
            arguments = """{"path":"references/api.md"}"""
        )
        val tool = AgentToolCallHistorySupport.toolResultMessage(
            toolCallId = "tool-123",
            result = "resource content"
        )

        val toolCallId = assistant.getAsJsonArray("tool_calls")
            .get(0).asJsonObject
            .get("id").asString

        assertEquals("tool-123", toolCallId)
        assertEquals("tool-123", tool.get("tool_call_id").asString)
    }

    @Test
    fun `sanitize removes invalid assistant tool exchange from history`() {
        val messages = mutableListOf(
            JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", "hello")
            },
            AgentToolCallHistorySupport.assistantToolCallMessage(
                toolCallId = "wrong-id",
                name = "demo_tool",
                arguments = "{}"
            ),
            AgentToolCallHistorySupport.toolResultMessage(
                toolCallId = "actual-id",
                result = "done"
            ),
            JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", "final answer")
            }
        )

        AgentToolCallHistorySupport.sanitizeInPlace(messages)

        assertEquals(2, messages.size)
        assertEquals("user", messages[0].get("role").asString)
        assertEquals("assistant", messages[1].get("role").asString)
        assertNull(messages[1].get("tool_calls"))
        assertTrue(messages.none { it.get("role")?.asString == "tool" })
    }
}
