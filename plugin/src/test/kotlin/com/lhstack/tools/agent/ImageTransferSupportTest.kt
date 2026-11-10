package com.lhstack.tools.agent

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class ImageTransferSupportTest {
    private fun imageBytes(): ByteArray =
        ByteArrayOutputStream().use { output ->
            val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB)
            image.setRGB(2, 1, 0x12AB34)
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }

    @Test
    fun `local image download retains original bytes and clipboard carries an image`() {
        val root = Files.createTempDirectory("jtools-image-transfer").toFile()
        try {
            val bytes = imageBytes()
            val file = root.resolve("示例 图片.png").apply { writeBytes(bytes) }
            val data = ImageTransferSupport.fromFile(file)
            assertContentEquals(bytes, data.bytes)
            assertEquals(file.name, data.filename)
            val clipboard = data.transferable()
            assertTrue(clipboard.isDataFlavorSupported(DataFlavor.imageFlavor))
            assertFalse(clipboard.isDataFlavorSupported(DataFlavor.stringFlavor))
            val image = clipboard.getTransferData(DataFlavor.imageFlavor) as BufferedImage
            assertEquals(4, image.width)
            assertEquals(0x12AB34, image.getRGB(2, 1) and 0xFFFFFF)
            assertFailsWith<UnsupportedFlavorException> {
                clipboard.getTransferData(DataFlavor.stringFlavor)
            }
            assertFailsWith<IllegalArgumentException> {
                ImageTransferSupport.fromFile(
                    root.resolve("invalid.png").apply { writeText("not an image") }
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `inline image preserves bytes sanitizes filename and rejects unsupported source schemes`() {
        val bytes = imageBytes()
        val source = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        val data = ImageTransferSupport.fromUrl(source, "../image.png")
        assertContentEquals(bytes, data.bytes)
        assertEquals("image.png", data.filename)
        for (invalid in
            listOf(
                "file:///tmp/test.png",
                "javascript:alert(1)",
                "data:text/plain;base64,SGVsbG8=",
                "https://user:pass@example.test/image.png",
            )) {
            assertFailsWith<IllegalArgumentException> {
                ImageTransferSupport.fromUrl(invalid, "image.png")
            }
        }
    }
}
