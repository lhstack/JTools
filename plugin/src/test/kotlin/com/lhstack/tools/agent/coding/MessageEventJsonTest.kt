package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MessageEventJsonTest {
    @Test
    fun `browser json uses snake case event fields`() {
        val event = MessageEventRecord(
            id = 9,
            parentEventId = null,
            sessionId = 3,
            turnId = "turn-a",
            status = MessageEventStatus.COMPLETED,
            eventType = MessageEventType.USER_MESSAGE,
            eventId = "1",
            summary = "hello",
            context = JsonObject().apply { addProperty("content", "hello") },
            revision = 2,
            createdAt = "2026-09-10 00:00:00",
            updatedAt = "2026-09-10 00:00:01",
        )
        val json = event.toBrowserJson()
        assertEquals(9, json.get("id").asLong)
        assertEquals("user_message", json.get("event_type").asString)
        assertEquals("turn-a", json.get("turn_id").asString)
        assertFalse(json.get("parent_event_id").isJsonPrimitive)
        assertEquals("hello", json.getAsJsonObject("context").get("content").asString)
    }
}
