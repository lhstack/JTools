package com.lhstack.tools.llm.anthropic

import com.google.gson.JsonObject
import com.lhstack.tools.llm.Message
import com.lhstack.tools.agent.model.params.ModelRuntimeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AnthropicThinkingRequestTest {
    @Test
    fun `model params thinking disabled wins over additional enabled`() {
        val modelParams = JsonObject().apply {
            add("thinking", JsonObject().apply { addProperty("type", "disabled") })
        }
        val additional = JsonObject().apply {
            add("thinking", JsonObject().apply {
                addProperty("type", "enabled")
                addProperty("budget_tokens", 2048)
                addProperty("display", "summarized")
            })
        }
        val request = AnthropicMessageRequest.fromRuntime(
            modelId = "claude-test",
            preamble = "",
            messages = listOf(Message.user("hello")),
            params = ModelRuntimeParams(modelParams = modelParams),
            additionalParams = additional,
            tools = emptyList(),
            stream = true,
            maxToolRounds = 8,
            maxRetries = 0,
        )
        val thinking = request.body().getAsJsonObject("thinking")
        assertEquals("disabled", thinking.get("type").asString)
        assertFalse(thinking.has("budget_tokens"))
        assertFalse(thinking.has("display"))
    }

    @Test
    fun `enabled thinking requires budget and display`() {
        val modelParams = JsonObject().apply {
            add("thinking", JsonObject().apply {
                addProperty("type", "enabled")
                addProperty("budget_tokens", 512)
            })
        }
        val error = assertFailsWith<IllegalArgumentException> {
            AnthropicMessageRequest.fromRuntime(
                modelId = "claude-test",
                preamble = "",
                messages = listOf(Message.user("hello")),
                params = ModelRuntimeParams(modelParams = modelParams),
                additionalParams = null,
                tools = emptyList(),
                stream = true,
                maxToolRounds = 8,
                maxRetries = 0,
            )
        }
        assertEquals("开启 Anthropic thinking 后 budget_tokens 不能小于 1024", error.message)
    }
}
