package com.lhstack.tools.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.lhstack.tools.agent.model.llm.AssistantContent
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.UserContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatTurnHistoryMessagesTest {
    @Test
    fun `double encoded object arguments are unwrapped and paired with result`() {
        val structured = history(
            arguments = JsonPrimitive("{\"queries\":[\"AgentChatPanel\"]}"),
            includeResult = true,
        )
        val messages = mutableListOf<Message>()

        ChatTurnHistoryMessages.append(messages, structured)

        val call = assertIs<AssistantContent.ToolCall>(assertIs<Message.Assistant>(messages[0]).content.single())
        assertEquals("AgentChatPanel", call.toolCall.function.arguments.asJsonObject
            .getAsJsonArray("queries")[0].asString)
        assertIs<UserContent.ToolResult>(assertIs<Message.User>(messages[1]).content.single())
    }

    @Test
    fun `call without result is removed from provider history`() {
        val messages = mutableListOf<Message>()
        ChatTurnHistoryMessages.append(messages, history(JsonParser.parseString("{}"), includeResult = false))
        assertEquals(emptyList(), messages)
    }

    @Test
    fun `non object arguments are removed with their result`() {
        val messages = mutableListOf<Message>()
        ChatTurnHistoryMessages.append(messages, history(JsonPrimitive("[]"), includeResult = true))
        assertEquals(emptyList(), messages)
    }

    private fun history(arguments: com.google.gson.JsonElement, includeResult: Boolean): JsonObject = JsonObject().apply {
        add("tool_calls", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tool_name", "find_project_files")
                addProperty("internal_call_id", "internal-1")
                addProperty("tool_call_id", "call-1")
                add("args", arguments)
            })
        })
        add("tool_results", JsonArray().apply {
            if (includeResult) add(JsonObject().apply {
                addProperty("internal_call_id", "internal-1")
                addProperty("tool_call_id", "call-1")
                addProperty("result", "ok")
            })
        })
        addProperty("response", "")
    }
}
