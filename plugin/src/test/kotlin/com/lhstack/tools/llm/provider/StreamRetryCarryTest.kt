package com.lhstack.tools.llm.provider

import com.google.gson.JsonObject
import com.lhstack.tools.llm.AssistantContent
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ProviderToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamRetryCarryTest {
    @Test
    fun `complete tool call requires id name and json object arguments`() {
        assertNull(StreamRetryCarry.completeToolCall("", "bash", """{"command":"ls"}"""))
        assertNull(StreamRetryCarry.completeToolCall("call_1", "", """{"command":"ls"}"""))
        assertNull(StreamRetryCarry.completeToolCall("call_1", "bash", """{"command":"""))
        val call = StreamRetryCarry.completeToolCall("call_1", "bash", """{"command":"ls"}""")
        assertEquals("bash", call?.name)
        assertEquals("call_1", call?.id)
    }

    @Test
    fun `complete tool treats stream failure as successful round`() {
        val calls = listOf(ProviderToolCall("call_1", "call_1", "bash", JsonObject().apply { addProperty("command", "ls") }))
        val outcome = StreamRetryCarry.outcome(
            IllegalStateException("SSE stream read failed"),
            "先说明一下",
            listOf("思考"),
            calls,
        )
        val completed = assertIs<StreamFailureOutcome.Completed>(outcome)
        assertEquals("先说明一下", completed.round.response)
        assertEquals(1, completed.round.toolCalls.size)
    }

    @Test
    fun `partial reply without tools is retried with assistant text only`() {
        val outcome = StreamRetryCarry.outcome(
            IllegalStateException("SSE stream read failed"),
            "最终回复",
            listOf("思考"),
            emptyList(),
        )
        val retry = assertIs<StreamFailureOutcome.Retry>(outcome)
        val appended = mutableListOf<Message>()
        StreamRetryCarry.appendPartialToRequest(appended::addAll, retry.failure.partial)
        assertEquals(1, appended.size)
        val assistant = appended.single() as Message.Assistant
        assertEquals("最终回复", (assistant.content.single() as AssistantContent.Text).text)
        assertTrue(assistant.content.none { it is AssistantContent.ToolCall })
    }

    @Test
    fun `empty partial retries original error`() {
        val outcome = StreamRetryCarry.outcome(
            IllegalStateException("SSE stream read failed"),
            "",
            emptyList(),
            emptyList(),
        )
        val fatal = assertIs<StreamFailureOutcome.Fatal>(outcome)
        assertEquals("SSE stream read failed", fatal.error.message)
        val appended = mutableListOf<Message>()
        StreamRetryCarry.appendPartialToRequest(appended::addAll, null)
        assertTrue(appended.isEmpty())
    }
}
