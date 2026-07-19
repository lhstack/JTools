package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class AgentAttachmentPresentationSupportTest {

    @Test
    fun `user message preview includes attachment summary`() {
        val preview = AgentAttachmentPresentationSupport.userMessagePreview(
            text = "请分析这张图片",
            attachments = listOf(
                AgentAttachmentState(name = "photo.png", kind = AgentAttachmentKind.IMAGE.id, size = 2048),
                AgentAttachmentState(name = "report.pdf", kind = AgentAttachmentKind.FILE.id, size = 4096),
            )
        )

        assertTrue(preview.contains("请分析这张图片"))
        assertTrue(preview.contains("photo.png"))
        assertTrue(preview.contains("report.pdf"))
    }

    @Test
    fun `attachment only message still has readable preview`() {
        val preview = AgentAttachmentPresentationSupport.userMessagePreview(
            text = "",
            attachments = listOf(AgentAttachmentState(name = "diagram.png", kind = AgentAttachmentKind.IMAGE.id, size = 1024))
        )

        assertTrue(preview.contains("已附加 1 个文件"))
        assertTrue(preview.contains("diagram.png"))
    }

    @Test
    fun `attachment chip title is ellipsized and tooltip keeps full path`() {
        val attachment = AgentAttachmentState(
            name = "this-is-a-very-long-image-file-name-for-preview.png",
            path = "C:\\Users\\lhstack\\Pictures\\very\\deep\\this-is-a-very-long-image-file-name-for-preview.png",
            kind = AgentAttachmentKind.IMAGE.id,
        )

        val title = AgentAttachmentPresentationSupport.chipTitle(attachment, 24)
        val tooltip = AgentAttachmentPresentationSupport.chipTooltip(attachment)

        assertTrue(title.endsWith("..."))
        assertTrue(title.length <= 24)
        assertTrue(tooltip.contains(attachment.name))
        assertTrue(tooltip.contains(attachment.path))
    }
}
