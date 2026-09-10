package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import com.lhstack.tools.llm.UserContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodingAttachmentPromptTest {
    @Test
    fun `image without image modality sends metadata only`() {
        val attachment = MessageAttachmentRecord(
            id = 1,
            sessionId = 1,
            fileName = "image.png",
            contentType = "image/png",
            size = 2048,
            path = "/tmp/missing-image.png",
            kind = "image",
            textPreview = null,
            metadata = JsonObject(),
            createdAt = "2026-09-11 02:44:14",
        )
        val message = CodingAttachmentPrompt.currentUserMessage("图片里面有什么", listOf(attachment), emptyList())
        val user = assertIs<com.lhstack.tools.llm.Message.User>(message)
        assertEquals("图片里面有什么", (user.content[0] as UserContent.Text).text)
        val metadata = (user.content[1] as UserContent.Text).text
        assertTrue(metadata.contains("file_name: image.png"))
        assertTrue(metadata.contains("content_type: image/png"))
        assertTrue(metadata.contains("path: /tmp/missing-image.png"))
        assertTrue(user.content.none { it is UserContent.Image })
    }

    @Test
    fun `image always includes metadata even when image modality is enabled`() {
        val attachment = MessageAttachmentRecord(
            id = 1,
            sessionId = 1,
            fileName = "image.png",
            contentType = "image/png",
            size = 2048,
            path = "/tmp/missing-image.png",
            kind = "image",
            textPreview = null,
            metadata = JsonObject(),
            createdAt = "2026-09-11 02:44:14",
        )
        val message = CodingAttachmentPrompt.currentUserMessage("图片里面有什么", listOf(attachment), listOf("image"))
        val user = assertIs<com.lhstack.tools.llm.Message.User>(message)
        val metadata = user.content.filterIsInstance<UserContent.Text>().joinToString("\n") { it.text }
        assertTrue(metadata.contains("file_name: image.png"))
        assertTrue(metadata.contains("path: /tmp/missing-image.png"))
    }
}
