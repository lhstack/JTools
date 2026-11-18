package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonParser
import com.lhstack.tools.llm.DocumentSourceKind
import com.lhstack.tools.llm.Image
import com.lhstack.tools.llm.ImageMediaType
import com.lhstack.tools.llm.UserContent
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class ModelImageEncodingTest {
    @Test
    fun `native view rasterizes both SVG icons in one call and preserves ordinary PNG bytes`() {
        withProject { project ->
            val dimensions = linkedMapOf("add.svg" to 24, "agent_mcp.svg" to 16)
            val originals = dimensions.keys.associateWith { name ->
                requireNotNull(javaClass.getResourceAsStream("/icons/$name")).use { it.readBytes() }
                    .also { project.resolve(name).writeBytes(it) }
            }
            val png = project.resolve("icon.png")
            assertTrue(ImageIO.write(BufferedImage(3, 5, BufferedImage.TYPE_INT_RGB), "png", png))
            val tool = NativeResourceTool(ResourceKind.IMAGE, project.path, null)
            val result = tool.executeBlocking(JsonParser.parseString(
                """{"resources":[{"path":"add.svg"},{"path":"agent_mcp.svg"},{"path":"icon.png"}]}""",
            ))
            val images = result.resources.map { it.content.filterIsInstance<UserContent.Image>().single().image }
            val summaries = result.result.asJsonObject.getAsJsonArray("resources")
            dimensions.entries.forEachIndexed { index, (name, size) ->
                val bitmap = readPng(images[index])
                assertEquals(size, bitmap.width, name)
                assertEquals(size, bitmap.height, name)
                assertEquals(0, bitmap.getRGB(0, 0) ushr 24, name)
                assertTrue((0 until size).any { x -> (0 until size).any { y -> bitmap.getRGB(x, y) ushr 24 != 0 } }, name)
                assertContentEquals(originals.getValue(name), project.resolve(name).readBytes())
                assertEquals("image/svg+xml", summaries[index].asJsonObject["mime_type"].asString)
            }
            assertEquals(ImageMediaType.PNG, images[2].mediaType)
            assertContentEquals(png.readBytes(), bytes(images[2]))
        }
    }

    @Test
    fun `unsupported raster becomes PNG and large SVG has a bounded canvas`() {
        withProject { project ->
            val raster = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 1, Color.RED.rgb) }
            assertTrue(ImageIO.write(raster, "bmp", project.resolve("raster.bmp")))
            project.resolve("large.svg").writeText(
                """<svg xmlns="http://www.w3.org/2000/svg" width="24000" height="12000" viewBox="0 0 20 10"><rect width="20" height="10" fill="red"/></svg>""",
            )
            val bmp = readPng(view(project, "raster.bmp"))
            assertEquals(Color.RED.rgb, bmp.getRGB(1, 1))
            val scaled = readPng(view(project, "large.svg"))
            assertEquals(1568, scaled.width)
            assertEquals(784, scaled.height)
            assertEquals(Color.RED.rgb, scaled.getRGB(50, 50))
        }
    }

    @Test
    fun `SVG renders local shapes without loading external image files`() {
        withProject { project ->
            val external = project.resolve("external.png")
            val red = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).apply {
                for (y in 0 until height) for (x in 0 until width) setRGB(x, y, Color.RED.rgb)
            }
            assertTrue(ImageIO.write(red, "png", external))
            project.resolve("reference.svg").writeText(
                """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="8" height="4">
                    <rect width="4" height="4" fill="blue"/>
                    <image x="4" width="4" height="4" xlink:href="${external.toURI().toASCIIString()}"/>
                </svg>""".trimIndent(),
            )
            val bitmap = readPng(view(project, "reference.svg"))
            assertEquals(Color.BLUE.rgb, bitmap.getRGB(1, 1))
            assertEquals(0, bitmap.getRGB(6, 1) ushr 24)
        }
    }

    @Test
    fun `corrupt PNG and invalid SVG are rejected before a model request`() {
        withProject { project ->
            for (name in listOf("broken.png", "broken.svg")) {
                project.resolve(name).writeText("not an image")
                val error = assertFailsWith<IllegalArgumentException> { view(project, name) }
                assertContains(error.message.orEmpty(), "无法解码或转换图片")
                assertContains(error.message.orEmpty(), name)
            }
        }
    }

    private fun view(project: java.io.File, name: String): Image =
        NativeResourceTool(ResourceKind.IMAGE, project.path, null)
            .executeBlocking(JsonParser.parseString("""{"resources":[{"path":"$name"}]}"""))
            .resources.single().content.filterIsInstance<UserContent.Image>().single().image

    private fun bytes(image: Image): ByteArray = Base64.getDecoder().decode(assertIs<DocumentSourceKind.Base64>(image.data).data)

    private fun readPng(image: Image): BufferedImage {
        assertEquals(ImageMediaType.PNG, image.mediaType)
        assertTrue(image.tryIntoUrl().startsWith("data:image/png;base64,"))
        val bytes = bytes(image)
        assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOf(8))
        return assertNotNull(ImageIO.read(bytes.inputStream()))
    }

    private fun withProject(block: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("jtools-image-encoding-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
