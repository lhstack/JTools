package com.lhstack.tools.agent.coding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MessageEventSupportTest {
    @Test
    fun `summary truncates to 160 characters`() {
        val text = "a".repeat(200)
        assertEquals(160, MessageEventSupport.summary(text).length)
        assertEquals("a".repeat(160), MessageEventSupport.latestSummary(text))
    }

    @Test
    fun `token estimate uses character ratio`() {
        assertEquals(8, MessageEventSupport.estimateHistoryTokens("abcd", 2.0))
        assertFailsWith<IllegalArgumentException> { MessageEventSupport.estimateHistoryTokens("a", 0.0) }
    }

    @Test
    fun `stream round parses reasoning and reply ids`() {
        assertEquals(3, MessageEventSupport.parseStreamRound("model_reasoning", "stream_reasoning_3"))
        assertEquals(12, MessageEventSupport.parseStreamRound("model_reply", "stream_reply_12"))
        assertNull(MessageEventSupport.parseStreamRound("tool_call", "stream_reply_1"))
    }
}
