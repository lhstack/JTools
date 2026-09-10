package com.lhstack.tools.llm.openai

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAiStreamParseTest {
    @Test
    fun `primaryChatDelta uses choice index 0`() {
        val value = JsonParser.parseString(
            """{"choices":[{"index":1,"delta":{"content":"skip"}},{"index":0,"delta":{"content":"keep"}}]}""",
        )
        assertEquals("keep", OpenAiParser.primaryChatDelta(value)?.get("content")?.asString)
    }

    @Test
    fun `chatReasoningDelta reads thinking field`() {
        val delta = JsonParser.parseString("""{"thinking":"step"}""").asJsonObject
        assertEquals("step", OpenAiParser.chatReasoningDelta(delta))
    }

    @Test
    fun `streamReasoningValue reads frame and message fallback`() {
        val frame = JsonParser.parseString("""{"reasoning_content":"frame"}""")
        assertEquals("frame", OpenAiParser.streamReasoningValue(frame))
        val nested = JsonParser.parseString("""{"choices":[{"message":{"reasoning":"nested"}}]}""")
        assertEquals("nested", OpenAiParser.streamReasoningValue(nested))
        assertNull(OpenAiParser.streamReasoningValue(JsonParser.parseString("""{"choices":[]}""")))
    }

    @Test
    fun `responseReasoningText reads thinking`() {
        val value = JsonParser.parseString("""{"thinking":"hidden"}""")
        assertEquals("hidden", OpenAiParser.responseReasoningText(value))
    }
}
