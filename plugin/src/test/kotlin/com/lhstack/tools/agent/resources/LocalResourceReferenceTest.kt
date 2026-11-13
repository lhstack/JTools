package com.lhstack.tools.agent.resources

import java.nio.file.Files
import kotlin.test.*

class LocalResourceReferenceTest {
    @Test
    fun `local preview provides actual decoded image and rejects nonimages`() {
        val root = Files.createTempDirectory("jtools-preview").toFile()
        try {
            val image = root.resolve("sample.png")
            javax.imageio.ImageIO.write(
                java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png",
                image,
            )
            val result = LocalImagePreviewSupport.preview(image)
            val bytes =
                java.util.Base64.getDecoder().decode(result.getValue("url").substringAfter(','))
            assertEquals(4, javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)).width)
            val text = root.resolve("not-image.png").apply { writeText("not an image") }
            assertFailsWith<IllegalStateException> { LocalImagePreviewSupport.preview(text) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolves absolute external files relative paths file URIs and symlinks without workspace membership checks`() {
        val root = Files.createTempDirectory("jtools-ref").toFile()
        val outside = Files.createTempFile("jtools-upload", ".png").toFile()
        try {
            val file = root.resolve("目录 空格+a.kt").apply { writeText("line1\nline2") }
            val relative = LocalResourceReferenceSupport.resolve("目录 空格+a.kt#L2C3", root.path)
            assertEquals(
                file.canonicalFile,
                LocalResourceReferenceSupport.resolve("目录%20空格+a.kt", root.path).file,
            )
            assertEquals(file.canonicalFile, relative.file)
            assertEquals(2, relative.line)
            assertEquals(3, relative.column)
            assertEquals(
                file.canonicalFile,
                LocalResourceReferenceSupport.resolve(
                        file.toURI().toASCIIString() + ":2",
                        root.path,
                    )
                    .file,
            )
            assertEquals(
                outside.canonicalFile,
                LocalResourceReferenceSupport.resolve(outside.path, root.path)
                    .file,
            )
            assertFailsWith<IllegalArgumentException> {
                LocalResourceReferenceSupport.resolve("javascript:alert(1)", root.path)
            }
            assertFailsWith<IllegalArgumentException> {
                LocalResourceReferenceSupport.resolve("目录 空格+a.kt:0", root.path)
            }
            val link = root.resolve("escape")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertEquals(outside.canonicalFile, LocalResourceReferenceSupport.resolve("escape", root.path).file)
            assertEquals(outside.canonicalFile, LocalResourceReferenceSupport.resolve(root.toPath().relativize(outside.toPath()).toString(), root.path).file)
            assertEquals(outside.canonicalFile, LocalResourceReferenceSupport.resolve(outside.toURI().toASCIIString(), root.path).file)
            assertFailsWith<IllegalArgumentException> {
                LocalResourceReferenceSupport.resolve("file://remote-host/example.png", root.path)
            }
            assertFailsWith<IllegalArgumentException> {
                LocalResourceReferenceSupport.resolve(outside.path + ".missing", root.path)
            }
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }
}
