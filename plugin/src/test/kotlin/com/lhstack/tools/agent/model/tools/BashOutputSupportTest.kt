package com.lhstack.tools.agent.model.tools

import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.test.*
import org.junit.jupiter.api.Test

class BashOutputSupportTest {
    @Test
    fun `full streams survive overflow with bounded metadata and error status`() {
        val support = BashOutputSupport()
        val capture = support.begin(1024)
        val stdout = "中文😀\n\"quoted\"\\tab\t".repeat(2000)
        val stderr = "warning 错误\n".repeat(1500)
        try {
            Files.writeString(capture.raw("stdout"), stdout)
            Files.writeString(capture.raw("stderr"), stderr)
            val result = support.deliver(capture, Charsets.UTF_8, 7, false, false, 1024)
            assertEquals(7, result["exit_code"].asInt)
            assertFalse(result["success"].asBoolean)
            assertTrue(ToolOutputText.size(result) <= 1024)
            assertTrue(result["summary"].asString.contains("bash"))
            for (removed in listOf("stdout", "stderr", "preview_truncated", "output_id", "read")) assertFalse(result.has(removed))
            for ((stream, expected) in listOf("stdout" to stdout, "stderr" to stderr)) {
                val file = capture.directory.resolve("$stream.txt")
                assertEquals(expected, Files.readString(file))
                val metadata = result["files"].asJsonObject[stream].asJsonObject
                assertEquals(ToolOutputText.count(expected), metadata["chars"].asInt)
                assertEquals(Files.size(file), metadata["bytes"].asLong)
                assertEquals(file.toString(), metadata["path"].asString)
            }
        } finally { support.discard(capture) }
    }

    @Test
    fun `serialized JSON escaping causes spill even below raw text limit`() {
        val support = BashOutputSupport()
        val capture = support.begin(1024)
        try {
            Files.writeString(capture.raw("stdout"), "\"".repeat(900))
            Files.writeString(capture.raw("stderr"), "")
            val result = support.deliver(capture, Charsets.UTF_8, 0, false, false, 1024)
            assertTrue(result.has("files"))
            assertTrue(ToolOutputText.size(result) <= 1024)
        } finally { support.discard(capture) }
    }

    @Test
    fun `native charset is converted to UTF8 without losing file content`() {
        val support = BashOutputSupport()
        val capture = support.begin(1024)
        try {
            val charset = Charset.forName("GBK")
            val text = "中文输出\n".repeat(500)
            Files.write(capture.raw("stdout"), text.toByteArray(charset))
            Files.write(capture.raw("stderr"), byteArrayOf())
            val result = support.deliver(capture, charset, 0, false, false, 1024)
            assertEquals(text, Files.readString(capture.directory.resolve("stdout.txt")))
            assertTrue(result["success"].asBoolean)
        } finally { support.discard(capture) }
    }

    @Test
    fun `small captures clean up and invalid configured limits fail`() {
        val support = BashOutputSupport()
        val capture = support.begin(8000)
        Files.writeString(capture.raw("stdout"), "ok\n")
        Files.writeString(capture.raw("stderr"), "")
        val result = support.deliver(capture, Charsets.UTF_8, 0, false, false, 8000)
        assertEquals("ok\n", result["stdout"].asString)
        assertFalse(result.has("files"))
        assertFalse(Files.exists(capture.directory))
        for (raw in listOf("0", "1023", "abc", "2147483648")) {
            assertFailsWith<IllegalArgumentException> { BashOutputSupport.validateConfiguredLimit(raw) }
        }
        assertEquals(1024, BashOutputSupport.validateConfiguredLimit("1024"))
    }
}
