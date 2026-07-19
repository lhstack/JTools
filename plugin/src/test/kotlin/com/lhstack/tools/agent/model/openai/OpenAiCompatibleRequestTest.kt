package com.lhstack.tools.agent.model.openai

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.params.ModelRuntimeParams
import com.lhstack.tools.agent.model.params.OpenAiProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiCompatibleRequestTest {
    @Test
    fun `compatible chat keeps reasoning effort and parallel tool calls`() {
        val modelParams = JsonObject().apply {
            addProperty("max_completion_tokens", 4096)
            addProperty("reasoning_effort", "high")
            addProperty("parallel_tool_calls", true)
            add("thinking", JsonObject().apply { addProperty("type", "enabled") })
            add("output_config", JsonObject().apply { addProperty("effort", "high") })
        }

        val request = OpenAiChatRequest.fromRuntime(
            modelId = "compatible-model",
            preamble = "",
            messages = listOf(Message.user("hello")),
            params = ModelRuntimeParams(modelParams = modelParams),
            openaiProviderType = OpenAiProviderType.COMPATIBLE,
            additionalParams = null,
            tools = emptyList(),
            stream = true,
            maxToolRounds = 30,
            maxRetries = 0,
        )

        assertEquals("high", request.body["reasoning_effort"].asString)
        assertTrue(request.body["parallel_tool_calls"].asBoolean)
        assertEquals(4096, request.body["max_completion_tokens"].asInt)
        assertEquals("enabled", request.body["thinking"].asJsonObject["type"].asString)
        assertEquals("high", request.body["output_config"].asJsonObject["effort"].asString)
    }

    @Test
    fun `compatible responses keeps nested reasoning and parallel tool calls`() {
        val modelParams = JsonObject().apply {
            addProperty("max_output_tokens", 4096)
            add("reasoning", JsonObject().apply { addProperty("effort", "medium") })
            addProperty("parallel_tool_calls", false)
            add("thinking", JsonObject().apply { addProperty("type", "enabled") })
        }

        val request = OpenAiResponsesRequest.fromRuntime(
            modelId = "compatible-model",
            preamble = "",
            messages = listOf(Message.user("hello")),
            params = ModelRuntimeParams(modelParams = modelParams),
            openaiProviderType = OpenAiProviderType.COMPATIBLE,
            additionalParams = null,
            tools = emptyList(),
            stream = true,
            maxToolRounds = 30,
            maxRetries = 0,
        )

        assertEquals("medium", request.body["reasoning"].asJsonObject["effort"].asString)
        assertEquals(false, request.body["parallel_tool_calls"].asBoolean)
        assertEquals(4096, request.body["max_output_tokens"].asInt)
        assertEquals("enabled", request.body["thinking"].asJsonObject["type"].asString)
    }
}
