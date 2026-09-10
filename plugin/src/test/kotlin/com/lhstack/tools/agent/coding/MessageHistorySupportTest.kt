package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import com.lhstack.tools.llm.AssistantContent
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.UserContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageHistorySupportTest {
    @Test
    fun `coding history rebuilds user assistant and tool pairs`() {
        val events = listOf(
            event(1, "t1", MessageEventType.USER_MESSAGE, JsonObject().apply { addProperty("content", "hello") }),
            event(2, "t1", MessageEventType.MODEL_REPLY, JsonObject().apply { addProperty("text", "hi") }),
            event(3, "t2", MessageEventType.USER_MESSAGE, JsonObject().apply { addProperty("content", "again") }),
        )
        val messages = MessageHistorySupport.toModelHistory(events, currentTurnId = "t2", includeCurrentTurn = false)
        assertEquals(2, messages.size)
        val user = assertIs<Message.User>(messages[0])
        assertEquals("hello", (user.content[0] as UserContent.Text).text)
        val assistant = assertIs<Message.Assistant>(messages[1])
        assertEquals("hi", (assistant.content[0] as AssistantContent.Text).text)
    }

    @Test
    fun `current turn is excluded unless requested`() {
        val events = listOf(
            event(1, "t1", MessageEventType.USER_MESSAGE, JsonObject().apply { addProperty("content", "old") }),
            event(2, "t2", MessageEventType.USER_MESSAGE, JsonObject().apply { addProperty("content", "new") }),
        )
        val withoutCurrent = MessageHistorySupport.toModelHistory(events, "t2", includeCurrentTurn = false)
        assertEquals(1, withoutCurrent.size)
        val withCurrent = MessageHistorySupport.toModelHistory(events, "t2", includeCurrentTurn = true)
        assertEquals(2, withCurrent.size)
    }

    @Test
    fun `historical attachments become metadata text`() {
        val context = JsonObject().apply {
            addProperty("content", "图片里面有什么")
            add("attachment_items", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("file_name", "image.png")
                    addProperty("content_type", "image/png")
                    addProperty("size", 1234)
                    addProperty("kind", "image")
                    addProperty("path", "/tmp/image.png")
                    addProperty("created_at", "2026-09-11 02:44:14")
                })
            })
        }
        val messages = MessageHistorySupport.toModelHistory(
            listOf(event(1, "t1", MessageEventType.USER_MESSAGE, context)),
            currentTurnId = "t2",
            includeCurrentTurn = false,
        )
        val user = assertIs<Message.User>(messages.single())
        assertEquals("图片里面有什么", (user.content[0] as UserContent.Text).text)
        val metadata = (user.content[1] as UserContent.Text).text
        assertTrue(metadata.contains("file_name: image.png"))
        assertTrue(metadata.contains("content_type: image/png"))
        assertTrue(metadata.contains("path: /tmp/image.png"))
        assertTrue(!metadata.contains("base64"))
    }

    @Test
    fun `resumed prompt is explicit and not empty`() {
        assertTrue(MessageEventSupport.RESUMED_PROMPT.contains("服务重启"))
    }

    private fun event(
        id: Long,
        turnId: String,
        type: MessageEventType,
        context: JsonObject,
    ) = MessageEventRecord(
        id = id,
        parentEventId = null,
        sessionId = 1,
        turnId = turnId,
        status = MessageEventStatus.COMPLETED,
        eventType = type,
        eventId = id.toString(),
        summary = "s",
        context = context,
        revision = 1,
        createdAt = null,
        updatedAt = null,
    )
}
