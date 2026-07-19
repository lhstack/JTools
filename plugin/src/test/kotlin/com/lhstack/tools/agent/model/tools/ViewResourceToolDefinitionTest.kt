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
}
