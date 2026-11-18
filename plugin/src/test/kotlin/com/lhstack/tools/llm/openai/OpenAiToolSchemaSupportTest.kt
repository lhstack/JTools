package com.lhstack.tools.llm.openai

import com.google.gson.JsonParser
import kotlin.test.*

class OpenAiToolSchemaSupportTest {
    @Test
    fun `strict schema rejects nested oneOf and names the tool`() {
        val tools = JsonParser.parseString("""[{"type":"function","name":"plugin_demo","parameters":{"properties":{"items":{"items":{"anyOf":[{"oneOf":[{},{}]}]}}}}}]""").asJsonArray
        val error = assertFailsWith<IllegalArgumentException> {
            OpenAiToolSchemaSupport.validate(tools, responses = true, strictByDefault = true)
        }
        assertContains(error.message.orEmpty(), "plugin_demo")
        assertContains(error.message.orEmpty(), "parameters.properties[items].items.anyOf[0].oneOf")
        OpenAiToolSchemaSupport.validate(tools, responses = true)
        tools[0].asJsonObject.addProperty("strict", false)
        OpenAiToolSchemaSupport.validate(tools, responses = true, strictByDefault = true)
    }

    @Test
    fun `property named oneOf is not a schema keyword and remote rejections resolve names`() {
        val tools = JsonParser.parseString("""[{"type":"function","function":{"name":"plugin_demo","strict":true,"parameters":{"properties":{"oneOf":{"type":"string"}}}}}]""").asJsonArray
        OpenAiToolSchemaSupport.validate(tools, responses = false)
        assertContains(OpenAiToolSchemaSupport.rejectedTool(
            """{"error":{"code":"invalid_json_schema","param":"tools[0].function.parameters"}}""", tools,
        ).orEmpty(), "plugin_demo")
    }
}
