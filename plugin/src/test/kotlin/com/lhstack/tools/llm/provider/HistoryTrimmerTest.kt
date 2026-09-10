package com.lhstack.tools.llm.provider

import com.google.gson.JsonObject
import com.lhstack.tools.llm.AssistantContent
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ToolCall
import com.lhstack.tools.llm.ToolFunction
import com.lhstack.tools.llm.ToolResult
import com.lhstack.tools.llm.ToolResultContent
import com.lhstack.tools.llm.UserContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryTrimmerTest {
    @Test
    fun `max history is counted by user turns`() {
        val history = listOf(
            Message.user("one"), Message.assistant("answer-one"),
            Message.user("two"), Message.assistant("answer-two"),
            Message.user("three"), Message.assistant("answer-three"),
        )

        val trimmed = HistoryTrimmer.trim(history, "", "next", null, null, 2, null)

        assertEquals(4, trimmed.size)
        assertEquals("two", (trimmed[0] as Message.User).content.filterIsInstance<UserContent.Text>().single().text)
        assertEquals("three", (trimmed[2] as Message.User).content.filterIsInstance<UserContent.Text>().single().text)
    }

    @Test
    fun `tool retention is independent from history turn retention`() {
        val history = listOf(
            Message.user("one"), toolCallMessage("call-one"), toolResultMessage("call-one"), Message.assistant("answer-one"),
            Message.user("two"), toolCallMessage("call-two"), toolResultMessage("call-two"), Message.assistant("answer-two"),
        )

        val trimmed = HistoryTrimmer.trim(history, "", "next", null, null, 2, 1)

        val calls = trimmed.filterIsInstance<Message.Assistant>()
            .flatMap { it.content.filterIsInstance<AssistantContent.ToolCall>() }
        assertEquals(1, calls.size)
        assertEquals("call-two", calls.single().toolCall.id)
        assertTrue(trimmed.filterIsInstance<Message.User>().any { message ->
            message.content.filterIsInstance<UserContent.Text>().any { it.text == "one" }
        })
    }

    private fun toolCallMessage(id: String): Message = Message.Assistant(
        id = null,
        content = listOf(AssistantContent.ToolCall(ToolCall(id, id, ToolFunction("test", JsonObject()), null, null))),
    )

    private fun toolResultMessage(id: String): Message = Message.User(
        listOf(UserContent.ToolResult(ToolResult(id, id, listOf(ToolResultContent.Text("ok"))))),
    )
}
