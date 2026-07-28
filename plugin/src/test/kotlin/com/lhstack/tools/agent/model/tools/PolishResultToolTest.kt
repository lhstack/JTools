package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolishResultToolTest {

    @Test
    fun `schema constrains result count and item text`() {
        val json = JsonParser.parseString(PolishResultTool().definition("").parameters.toString()).asJsonObject
        val results = json.getAsJsonObject("properties").getAsJsonObject("results")

        assertEquals(listOf("results"), json.getAsJsonArray("required").map { it.asString })
        assertEquals(PolishResultTool.MIN_RESULTS, results.get("minItems").asInt)
        assertEquals(PolishResultTool.MAX_RESULTS, results.get("maxItems").asInt)
        assertEquals(1, results.getAsJsonObject("items").get("minLength").asInt)
    }

    @Test
    fun `nothing is submitted before the model calls the tool`() {
        assertNull(PolishResultTool().submittedResults())
    }

    @Test
    fun `submitted results are trimmed and kept in order`() {
        val tool = PolishResultTool()

        tool.callJsonBlocking(JsonParser.parseString("""{"results":["  first ","second","third"]}"""))

        assertEquals(listOf("first", "second", "third"), tool.submittedResults())
    }

    @Test
    fun `too few results are rejected`() {
        val tool = PolishResultTool()

        assertFailsWith<IllegalArgumentException> {
            tool.callJsonBlocking(JsonParser.parseString("""{"results":["only one"]}"""))
        }
        assertNull(tool.submittedResults())
    }

    @Test
    fun `blank result item is rejected`() {
        val tool = PolishResultTool()

        val error = assertFailsWith<ToolException> {
            tool.callJsonBlocking(JsonParser.parseString("""{"results":["first","   ","third"]}"""))
        }

        assertTrue(error.message!!.contains("item 2"))
        assertNull(tool.submittedResults())
    }

    @Test
    fun `missing results argument is rejected`() {
        assertFailsWith<ToolException> {
            PolishResultTool().callJsonBlocking(JsonParser.parseString("{}"))
        }
    }
}
