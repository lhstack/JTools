package com.lhstack.tools.agent

import io.agentscope.core.message.ImageBlock
import io.agentscope.core.message.Base64Source
import io.agentscope.core.message.TextBlock
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentAttachmentSupportTest {

    @Test
    fun `pdf attachment becomes file context entry instead of media block`() {
        val draft = AgentAttachmentState(name = "spec.pdf", mimeType = "application/pdf")

        val mapped = AgentConversationMapper.mapAttachments(listOf(draft))

        assertTrue(mapped.fileContext.isNotEmpty())
        assertTrue(mapped.mediaBlocks.isEmpty())
    }

    @Test
    fun `image attachment becomes media block`() {
        val image = Files.createTempFile("agent-image", ".png")
        val draft = AgentAttachmentState(
            name = "diagram.png",
            path = image.toString(),
            mimeType = "image/png",
        )

        val mapped = AgentConversationMapper.mapAttachments(listOf(draft))

        assertEquals(1, mapped.mediaBlocks.size)
        assertTrue(mapped.mediaBlocks.first() is ImageBlock)
        assertTrue((mapped.mediaBlocks.first() as ImageBlock).source is Base64Source)
        assertTrue(mapped.fileContext.isEmpty())
    }

    @Test
    fun `text attachment extracts readable content into file context`() {
        val markdown = Files.createTempFile("agent-note", ".md")
        markdown.writeText("# Title\nhello attachment")
        val draft = AgentAttachmentState(
            name = "note.md",
            path = markdown.toString(),
            mimeType = "text/markdown",
        )

        val mapped = AgentConversationMapper.mapAttachments(listOf(draft))

        assertEquals(1, mapped.fileContext.size)
        assertTrue(mapped.fileContext.first().content.orEmpty().contains("hello attachment"))
    }

    @Test
    fun `file attachment remains visible to model even when user entered text`() {
        val markdown = Files.createTempFile("agent-visible-note", ".md")
        markdown.writeText("# Title\nhello attachment")
        val draft = AgentAttachmentState(
            name = "note.md",
            path = markdown.toString(),
            mimeType = "text/markdown",
        )

        val mapped = AgentConversationMapper.mapUserMessage("请结合附件回答", listOf(draft))

        assertTrue(mapped.message.getTextContent().contains("请结合附件回答"))
        assertTrue(mapped.message.getTextContent().contains("附件列表"))
        assertTrue(mapped.message.getTextContent().contains("hello attachment"))
    }

    @Test
    fun `mixed image and file attachments preserve file text after image when file is selected after image`() {
        val image = Files.createTempFile("agent-image-mixed", ".png")
        writeLargeImage(image)
        val markdown = Files.createTempFile("agent-mixed-note", ".md")
        markdown.writeText("# Title\nfile attachment content")
        val mapped = AgentConversationMapper.mapUserMessage(
            "请同时分析图片和文件",
            listOf(
                AgentAttachmentState(
                    name = "diagram.png",
                    path = image.toString(),
                    mimeType = "image/png",
                ),
                AgentAttachmentState(
                    name = "note.md",
                    path = markdown.toString(),
                    mimeType = "text/markdown",
                )
            )
        )

        assertEquals(3, mapped.message.content.size)
        assertTrue(mapped.message.content[0] is TextBlock)
        assertTrue(mapped.message.content[1] is ImageBlock)
        assertTrue(mapped.message.content[2] is TextBlock)
        val prompt = (mapped.message.content[0] as TextBlock).text
        val fileText = (mapped.message.content[2] as TextBlock).text
        assertTrue(prompt.contains("请同时分析图片和文件"))
        assertTrue(!prompt.contains("file attachment content"))
        assertTrue(fileText.contains("附件列表"))
        assertTrue(fileText.contains("file attachment content"))
    }

    @Test
    fun `mixed attachments preserve original attachment order`() {
        val firstMarkdown = Files.createTempFile("agent-mixed-first", ".md")
        firstMarkdown.writeText("first file content")
        val image = Files.createTempFile("agent-image-ordered", ".png")
        writeLargeImage(image)
        val secondMarkdown = Files.createTempFile("agent-mixed-second", ".md")
        secondMarkdown.writeText("second file content")

        val mapped = AgentConversationMapper.mapUserMessage(
            "请按顺序分析附件",
            listOf(
                AgentAttachmentState(
                    name = "first.md",
                    path = firstMarkdown.toString(),
                    mimeType = "text/markdown",
                ),
                AgentAttachmentState(
                    name = "diagram.png",
                    path = image.toString(),
                    mimeType = "image/png",
                ),
                AgentAttachmentState(
                    name = "second.md",
                    path = secondMarkdown.toString(),
                    mimeType = "text/markdown",
                )
            )
        )

        assertEquals(3, mapped.message.content.size)
        assertTrue(mapped.message.content[0] is TextBlock)
        assertTrue(mapped.message.content[1] is ImageBlock)
        assertTrue(mapped.message.content[2] is TextBlock)

        val firstText = (mapped.message.content[0] as TextBlock).text
        val secondText = (mapped.message.content[2] as TextBlock).text
        assertTrue(firstText.contains("请按顺序分析附件"))
        assertTrue(firstText.contains("first file content"))
        assertTrue(!firstText.contains("second file content"))
        assertTrue(secondText.contains("second file content"))
        assertTrue(!secondText.contains("first file content"))
    }

    @Test
    fun `oversized image is optimized before upload`() {
        val image = Files.createTempFile("agent-large-image", ".png")
        writeLargeImage(image)
        val originalSize = Files.size(image)
        val draft = AgentAttachmentState(
            name = "wallpaper.png",
            path = image.toString(),
            mimeType = "image/png",
        )

        val optimized = AgentAttachmentSupport.prepareForUpload(draft)

        assertNotNull(optimized)
        assertNotEquals(image.toString(), optimized.path)
        assertTrue(optimized.size > 0)
        assertTrue(optimized.size < originalSize)
    }

    private fun writeLargeImage(path: Path) {
        val image = BufferedImage(3200, 2200, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, Color((x * 31) % 255, (y * 17) % 255, (x + y) % 255).rgb)
            }
        }
        ImageIO.write(image, "png", path.toFile())
    }
}
