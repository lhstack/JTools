package com.lhstack.tools.agent.model.provider

import com.lhstack.tools.agent.model.llm.AssistantContent
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ToolCall
import com.lhstack.tools.agent.model.llm.ToolFunction
import com.lhstack.tools.agent.model.llm.ToolResult
import com.lhstack.tools.agent.model.llm.ToolResultContent
import com.lhstack.tools.agent.model.llm.UserContent
import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProviderMessageHistoryTest {
    @Test
    fun `exact tool and append message order is restored`() {
        val messages = listOf(
            Message.Assistant(null, listOf(AssistantContent.ToolCall(ToolCall("internal", "call", ToolFunction("tool", JsonObject()), null, null)))),
            Message.User(listOf(UserContent.ToolResult(ToolResult("internal", "call", listOf(ToolResultContent.Text("ok")))))),
            Message.user("append one"),
        )

        val restored = ProviderMessageHistory.fromJson(ProviderMessageHistory.toJson(messages))

        assertEquals(3, restored.size)
        assertIs<AssistantContent.ToolCall>(assertIs<Message.Assistant>(restored[0]).content.single())
        assertIs<UserContent.ToolResult>(assertIs<Message.User>(restored[1]).content.single())
        assertEquals("append one", assertIs<UserContent.Text>(assertIs<Message.User>(restored[2]).content.single()).text)
    }
}
