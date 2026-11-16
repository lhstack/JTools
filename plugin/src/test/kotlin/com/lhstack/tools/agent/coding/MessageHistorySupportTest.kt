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
    fun `max history rounds keeps latest turns`() {
        val events = listOf(
            event(1, "t1", MessageEventType.USER_MESSAGE, JsonObject().apply { addProperty("content", "one") }),
            event(2, "t1", MessageEventType.MODEL_REPLY, JsonObject().apply { addProperty("text", "a") }),
            event(3, "t2", MessageEventType.USER_MESSAGE, JsonObject().apply { addProperty("content", "two") }),
            event(4, "t2", MessageEventType.MODEL_REPLY, JsonObject().apply { addProperty("text", "b") }),
            event(5, "t3", MessageEventType.USER_MESSAGE, JsonObject().apply { addProperty("content", "three") }),
            event(6, "t3", MessageEventType.MODEL_REPLY, JsonObject().apply { addProperty("text", "c") }),
        )
        val messages = MessageHistorySupport.toModelHistory(
            events,
            currentTurnId = "t4",
            includeCurrentTurn = false,
            maxHistoryRounds = 2,
        )
        assertEquals(4, messages.size)
        val first = assertIs<Message.User>(messages.first())
        assertEquals("two", (first.content[0] as UserContent.Text).text)
    }

    @Test
    fun `resumed prompt is explicit and not empty`() {
        assertTrue(MessageEventSupport.RESUMED_PROMPT.contains("服务重启"))
    }

    @Test
    fun `invalid tool events do not consume history rounds or enter any protocol request`() {
        val valid = event(1, "valid", MessageEventType.TOOL_CALL, JsonObject().apply {
            addProperty("name", "cli"); addProperty("call_id", "valid-call")
            add("arguments", JsonObject()); addProperty("result", "business error")
        })
        val invalidArguments = listOf("null", "[]", "1", "false", "\"{}\"")
        val invalid = invalidArguments.mapIndexed { index, value ->
            event(index + 2L, "invalid-$index", MessageEventType.TOOL_CALL, JsonObject().apply {
                addProperty("name", "cli"); addProperty("call_id", "invalid-$index")
                add("arguments", com.google.gson.JsonParser.parseString(value))
                addProperty("result", "invalid arguments")
            })
        } + event(8, "raw", MessageEventType.TOOL_CALL, JsonObject().apply {
            addProperty("name", "cli"); addProperty("call_id", "invalid-raw")
            add("arguments", JsonObject()); addProperty("raw_arguments", "{invalid")
            addProperty("result", "invalid arguments")
        }) + event(9, "missing", MessageEventType.TOOL_CALL, JsonObject().apply {
            addProperty("name", "cli"); addProperty("call_id", "invalid-missing")
            addProperty("result", "invalid arguments")
        })
        val expected = MessageHistorySupport.toModelHistory(listOf(valid), "current", false)
        val history = MessageHistorySupport.toModelHistory(listOf(valid) + invalid, "current", false, 1)
        assertEquals(expected, history)
        assertEquals(2, history.size)
        assertEquals(com.lhstack.tools.llm.openai.OpenAiMessages.chatMessages("", expected),
            com.lhstack.tools.llm.openai.OpenAiMessages.chatMessages("", history))
        assertEquals(com.lhstack.tools.llm.openai.OpenAiMessages.responseInput(expected),
            com.lhstack.tools.llm.openai.OpenAiMessages.responseInput(history))
        assertEquals(com.lhstack.tools.llm.anthropic.AnthropicMessages.messagesFromRuntime("", expected),
            com.lhstack.tools.llm.anthropic.AnthropicMessages.messagesFromRuntime("", history))
        assertEquals(7, invalid.size)
        val resumed = MessageHistorySupport.toModelHistory(
            listOf(valid) + invalid.map { it.copy(turnId = "current") }, "current", true, 1,
        )
        assertEquals(expected, resumed)
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
