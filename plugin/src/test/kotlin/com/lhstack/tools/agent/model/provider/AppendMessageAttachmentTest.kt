package com.lhstack.tools.agent.model.provider

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.llm.UserContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppendMessageAttachmentTest {

    @Test
    fun `append message keeps text and attachment content in one user message`() {
        val message = AppendMessage(
            content = "补充说明",
            createdAt = "2026-08-04 13:58:07",
            attachments = listOf(UserContent.Text("[附件文件 demo.txt]\ncontent")),
            attachmentSnapshots = listOf(JsonObject().apply {
                addProperty("id", "attachment-1")
                addProperty("name", "demo.txt")
            }),
        )

        val user = assertIs<com.lhstack.tools.agent.model.llm.Message.User>(message.toUserMessage())
        assertEquals(2, user.content.size)
        assertEquals("补充说明", assertIs<UserContent.Text>(user.content[0]).text)
        assertEquals("[附件文件 demo.txt]\ncontent", assertIs<UserContent.Text>(user.content[1]).text)

        val json = InjectedAppendMessage(message, 2).toJson()
        assertEquals("demo.txt", json.getAsJsonArray("attachments")[0].asJsonObject["name"].asString)
        assertEquals(2, json["injected_round"].asInt)
    }

    @Test
    fun `attachment only append creates a non empty user message`() {
        val message = AppendMessage(
            content = "",
            createdAt = "2026-08-04 13:58:07",
            attachments = listOf(UserContent.Text("attachment")),
        )

        val user = assertIs<com.lhstack.tools.agent.model.llm.Message.User>(message.toUserMessage())
        assertEquals(listOf(UserContent.Text("attachment")), user.content)
    }
}
