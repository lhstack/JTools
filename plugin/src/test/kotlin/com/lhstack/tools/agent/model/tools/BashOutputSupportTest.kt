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
            val result = support.deliver(capture, 7, false, false, 1024)
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
            val result = support.deliver(capture, 0, false, false, 1024)
            assertTrue(result.has("files"))
            assertTrue(ToolOutputText.size(result) <= 1024)
        } finally { support.discard(capture) }
    }

    @Test
    fun `UTF8 content survives conversion across buffer boundaries`() {
        val support = BashOutputSupport()
        val capture = support.begin(1024)
        try {
            val text = "中文输出😀\n".repeat(2000)
            Files.writeString(capture.raw("stdout"), text, Charsets.UTF_8)
            Files.write(capture.raw("stderr"), byteArrayOf())
            val result = support.deliver(capture, 0, false, false, 1024)
            assertEquals(text, Files.readString(capture.directory.resolve("stdout.txt")))
            assertTrue(result["success"].asBoolean)
        } finally { support.discard(capture) }
    }

    @Test
    fun `GBK streams decode independently from UTF8 and preserve exit status`() {
        val support = BashOutputSupport()
        for (stream in listOf("stdout", "stderr")) {
            val capture = support.begin(8000)
            try {
                Files.writeString(capture.raw("stdout"), "UTF-8 😀\n")
                Files.writeString(capture.raw("stderr"), "UTF-8 😀\n")
                Files.write(capture.raw(stream), "中文错误\n".toByteArray(Charset.forName("GBK")))
                val result = support.deliver(capture, 7, false, false, 8000)
                assertEquals("中文错误\n", result[stream].asString)
                val other = if (stream == "stdout") "stderr" else "stdout"
                assertEquals("UTF-8 😀\n", result[other].asString)
                assertEquals(7, result["exit_code"].asInt)
                assertFalse(result["success"].asBoolean)
                assertFalse(Files.exists(capture.directory))
            } finally { support.discard(capture) }
        }
    }

    @Test
    fun `failed decoding restarts from raw bytes and overwrites partial output`() {
        val support = BashOutputSupport()
        val capture = support.begin(1024)
        try {
            val text = "prefix\n".repeat(5000) + "中文错误\n"
            Files.write(capture.raw("stdout"), text.toByteArray(Charset.forName("GBK")))
            Files.write(capture.raw("stderr"), byteArrayOf())
            val result = support.deliver(capture, 0, false, false, 1024)
            assertEquals(text, Files.readString(capture.directory.resolve("stdout.txt")))
            val metadata = result["files"].asJsonObject["stdout"].asJsonObject
            assertEquals(ToolOutputText.count(text), metadata["chars"].asInt)
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), metadata["bytes"].asLong)
            assertFalse(Files.exists(capture.raw("stdout")))
        } finally { support.discard(capture) }
    }

    @Test
    fun `invalid UTF8 and GBK use lossy UTF8 for either stream`() {
        val support = BashOutputSupport()
        for (stream in listOf("stdout", "stderr")) {
            val capture = support.begin(8000)
            try {
                Files.writeString(capture.raw("stdout"), "")
                Files.writeString(capture.raw("stderr"), "")
                val bytes = "valid UTF-8 😀\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0xff.toByte())
                Files.write(capture.raw(stream), bytes)
                val result = support.deliver(capture, 0, false, false, 8000)
                assertEquals("valid UTF-8 😀\n\uFFFD", result[stream].asString)
                assertTrue(result["success"].asBoolean)
            } finally { support.discard(capture) }
        }
    }

    @Test
    fun `missing output file remains an IO failure`() {
        val support = BashOutputSupport()
        val capture = support.begin(8000)
        assertFailsWith<java.nio.file.NoSuchFileException> {
            support.deliver(capture, 0, false, false, 8000)
        }
        assertFalse(Files.exists(capture.directory))
    }

    @Test
    fun `small captures clean up and invalid configured limits fail`() {
        val support = BashOutputSupport()
        val capture = support.begin(8000)
        Files.writeString(capture.raw("stdout"), "ok\n")
        Files.writeString(capture.raw("stderr"), "")
        val result = support.deliver(capture, 0, false, false, 8000)
        assertEquals("ok\n", result["stdout"].asString)
        assertFalse(result.has("files"))
        assertFalse(Files.exists(capture.directory))
        for (raw in listOf("0", "1023", "abc", "2147483648")) {
            assertFailsWith<IllegalArgumentException> { BashOutputSupport.validateConfiguredLimit(raw) }
        }
        assertEquals(1024, BashOutputSupport.validateConfiguredLimit("1024"))
    }
}
