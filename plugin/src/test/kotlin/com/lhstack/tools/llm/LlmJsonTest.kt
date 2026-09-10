package com.lhstack.tools.llm

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmJsonTest {
    @Test
    fun `model params overlay additional params recursively`() {
        val additional = JsonObject().apply {
            add("thinking", JsonObject().apply {
                addProperty("type", "enabled")
                addProperty("budget_tokens", 2048)
            })
            addProperty("temperature", 0.2)
        }
        val modelParams = JsonObject().apply {
            add("thinking", JsonObject().apply { addProperty("type", "disabled") })
        }
        val merged = LlmJson.mergedModelAndAdditionalParams(modelParams, additional)
        assertEquals("disabled", merged.getAsJsonObject("thinking").get("type").asString)
        assertEquals(2048, merged.getAsJsonObject("thinking").get("budget_tokens").asInt)
        assertEquals(0.2, merged.get("temperature").asDouble)
    }

    @Test
    fun `named params must be objects`() {
        assertFailsWith<IllegalArgumentException> {
            LlmJson.objectFromNamedParams(JsonPrimitive("x"), "model_params")
        }
    }

    @Test
    fun `parseArgs requires json object`() {
        val args = LlmJson.parseArgs("""{"path":"a.kt"}""")
        assertTrue(args.isJsonObject)
        assertEquals("a.kt", args.asJsonObject.get("path").asString)
        assertFailsWith<IllegalStateException> { LlmJson.parseArgs("not-json") }
        assertFailsWith<IllegalStateException> { LlmJson.parseArgs("""["x"]""") }
        assertFailsWith<IllegalStateException> { LlmJson.parseArgs(""""hello"""") }
    }
}
