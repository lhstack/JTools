package com.lhstack.tools.llm.openai

import com.google.gson.JsonPrimitive
import com.lhstack.tools.llm.AssistantContent
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ToolCall
import com.lhstack.tools.llm.ToolFunction
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiToolHistoryValidationTest {
    @Test
    fun `chat history rejects non object tool arguments before HTTP request`() {
        val message = Message.Assistant(null, listOf(AssistantContent.ToolCall(
            ToolCall("internal", "call", ToolFunction("find_project_files", JsonPrimitive("{}")), null, null),
        )))

        val error = assertFailsWith<IllegalArgumentException> {
            OpenAiMessages.chatMessage(message)
        }
        assertTrue(error.message.orEmpty().contains("arguments 必须是 JSON 对象"))
    }
}
