package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class McpCallOutputSupportTest {
    @Test
    fun `oversized error result returns summary and complete file without preview or reader protocol`() {
        val result = JsonObject().apply {
            addProperty("isError", true)
            add("content", JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", "中文😀\n\"quote\"".repeat(3000))
            }) })
            add("structuredContent", JsonObject().apply { addProperty("detail", "data".repeat(4000)) })
        }
        val receipt = McpCallOutputSupport().deliver(result, 1024).asJsonObject
        val file = Path.of(receipt["path"].asString)
        try {
            assertTrue(receipt["isError"].asBoolean)
            assertTrue(receipt["summary"].asString.contains("bash"))
            assertTrue(ToolOutputText.size(receipt) <= 1024)
            assertEquals(result, JsonParser.parseString(Files.readString(file)))
            assertEquals(Files.size(file), receipt["file_bytes"].asLong)
            assertEquals(ToolOutputText.count(Files.readString(file)), receipt["file_chars"].asInt)
            for (removed in listOf("preview", "preview_truncated", "read", "output_id")) assertFalse(receipt.has(removed))
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(file.parent) }
    }

    @Test
    fun `small results unchanged and invalid limits rejected`() {
        val support = McpCallOutputSupport()
        val result = JsonObject().apply { addProperty("ok", true) }
        assertSame(result, support.deliver(result, 8000))
        for (raw in listOf("1", "1023", "0", "-1", "1.5", "invalid", "2147483648")) {
            assertFailsWith<IllegalArgumentException> { McpCallOutputSupport.validateConfiguredLimit(raw) }
        }
        assertEquals(1024, McpCallOutputSupport.validateConfiguredLimit("1024"))
    }
}
