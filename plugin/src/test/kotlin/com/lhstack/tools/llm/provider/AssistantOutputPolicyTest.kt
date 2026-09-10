package com.lhstack.tools.llm.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantOutputPolicyTest {
    @Test
    fun `tool call without result is not output`() {
        assertFalse(AssistantOutputPolicy.hasOutput(structured(call = true, result = false)))
    }

    @Test
    fun `tool call with matching result is output`() {
        assertTrue(AssistantOutputPolicy.hasOutput(structured(call = true, result = true)))
    }

    @Test
    fun `response and reasoning are output independently`() {
        assertTrue(AssistantOutputPolicy.hasOutput(structured(response = "answer")))
        assertTrue(AssistantOutputPolicy.hasOutput(structured(reasoning = "thought")))
    }

    private fun structured(
        response: String = "",
        reasoning: String = "",
        call: Boolean = false,
        result: Boolean = false,
    ) = JsonObject().apply {
        addProperty("response", response)
        add("reasoning", JsonArray().apply { if (reasoning.isNotEmpty()) add(reasoning) })
        add("tool_calls", JsonArray().apply { if (call) add(JsonObject().apply { addProperty("internal_call_id", "1") }) })
        add("tool_results", JsonArray().apply { if (result) add(JsonObject().apply { addProperty("internal_call_id", "1") }) })
    }
}
