package com.lhstack.tools.db

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageEventTokenSupportTest {
    @Test
    fun `event estimate uses model visible user text and attachment metadata`() {
        val context = JsonParser.parseString(
            """{"content":"hello","extra":{"ignored":"${"x".repeat(1000)}"},"attachment_items":[{"file_name":"a.png","content_type":"image/png","size":10,"kind":"image","path":"/tmp/a.png","created_at":"now"}]}""",
        ).asJsonObject

        val result = MessageEventTokenSupport.reestimate(context, "user_message", 0.4)

        assertEquals(
            MessageEventTokenSupport.reestimate(context, "user_message", 0.4)["estimated_tokens"],
            result["estimated_tokens"],
        )
        assertTrue(result["estimated_tokens"].asLong < 500)
        assertFalse(result["estimated_tokens"].asLong >= context.toString().length)
    }

    @Test
    fun `tool estimate uses one argument field and truncates result`() {
        val context = JsonParser.parseString(
            """{"name":"bash","arguments":{"command":"printf ok"},"args":{"command":"duplicate"},"result":"${"x".repeat(20000)}"}""",
        ).asJsonObject

        val result = MessageEventTokenSupport.reestimate(context, "tool_call", 0.4)

        assertTrue(result["estimated_tokens"].asLong < 8000)
    }

    @Test
    fun `retry and failure do not retain an estimate`() {
        val context = JsonParser.parseString("""{"estimated_tokens":100,"error":"failed"}""").asJsonObject

        assertFalse(MessageEventTokenSupport.reestimate(context, "model_retry", 0.4).has("estimated_tokens"))
        assertFalse(MessageEventTokenSupport.reestimate(context, "task_failed", 0.4).has("estimated_tokens"))
    }
}
