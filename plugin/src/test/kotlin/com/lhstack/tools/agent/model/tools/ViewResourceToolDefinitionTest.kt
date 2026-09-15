package com.lhstack.tools.agent.model.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewResourceToolDefinitionTest {
    @Test
    fun `resource view prompt is required`() {
        val schema = ViewResourceTool::class.java.getDeclaredField("DEFINITION_JSON").let { field ->
            field.isAccessible = true
            field.get(ViewResourceTool.Companion) as String
        }
        val json = com.google.gson.JsonParser.parseString(schema).asJsonObject

        assertEquals(listOf("prompt"), json.getAsJsonArray("required").map { it.asString })
        assertEquals(1, json.getAsJsonObject("properties").getAsJsonObject("prompt").get("minLength").asInt)
        assertTrue(json.getAsJsonObject("properties").getAsJsonObject("prompt").get("description").asString.contains("必填"))
    }

    @Test
    fun `resource paths accept workspace absolute and protocol path descriptions`() {
        val properties = com.google.gson.JsonParser.parseString(schema()).asJsonObject
            .getAsJsonObject("properties")
        assertTrue(properties.getAsJsonObject("path").get("description").asString.contains("协议路径"))
        assertTrue(properties.getAsJsonObject("paths").get("description").asString.contains("普通绝对路径"))
    }

    private fun schema(): String = ViewResourceTool::class.java.getDeclaredField("DEFINITION_JSON").let { field ->
        field.isAccessible = true
        field.get(ViewResourceTool.Companion) as String
    }
}
