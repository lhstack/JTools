package com.lhstack.tools.agent.model.anthropic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.http.ModelHttpExecutor
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.params.ModelRuntimeParams
import com.lhstack.tools.agent.model.provider.AppendMessage
import com.lhstack.tools.agent.model.provider.AppendMessageChannel
import com.lhstack.tools.agent.model.provider.ToolHook
import com.lhstack.tools.agent.model.provider.ToolRuntime
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicAppendMessageLoopTest {
    @Test
    fun `pending append continues loop when response has no tool call`() {
        val requestBodies = mutableListOf<JsonObject>()
        var responseIndex = 0
        val executor = ModelHttpExecutor(OkHttpClient.Builder().addInterceptor { chain ->
            requestBodies += chain.request().bodyJson()
            responseIndex += 1
            jsonResponse(
                chain.request(),
                """{"content":[{"type":"text","text":"answer-$responseIndex"}],"usage":{}}""",
            )
        }.build())
        val channel = OnePendingAppendChannel()
        val client = AnthropicClient(
            AnthropicClientParams(
                executor = executor,
                apiKey = "test-key",
                baseUrl = "https://example.test",
                anthropicVersion = null,
                customHeaders = emptyMap(),
                httpTrace = null,
            )
        )
        val request = AnthropicMessageRequest.fromRuntime(
            modelId = "test-model",
            preamble = "",
            messages = listOf(Message.user("original")),
            params = ModelRuntimeParams(),
            additionalParams = null,
            tools = emptyList(),
            stream = false,
            maxToolRounds = 30,
            maxRetries = 0,
        )

        val output = client.chat(request, emptyToolRuntime(), null, channel)

        assertEquals(2, requestBodies.size)
        assertEquals("answer-1answer-2", output.response)
        assertEquals(2, channel.terminalChecks)
        assertEquals(1, channel.injectedCount)
        val secondMessages = requestBodies[1].getAsJsonArray("messages")
        assertEquals("assistant", secondMessages[secondMessages.size() - 2].asJsonObject["role"].asString)
        assertEquals("user", secondMessages.last().asJsonObject["role"].asString)
        assertEquals(
            "follow-up",
            secondMessages.last().asJsonObject.getAsJsonArray("content")[0].asJsonObject["text"].asString
        )
    }

    private class OnePendingAppendChannel : AppendMessageChannel {
        var terminalChecks = 0
        var injectedCount = 0

        override fun fetch(): List<AppendMessage> = emptyList()

        override fun fetchPendingOrClose(): List<AppendMessage>? {
            terminalChecks += 1
            return if (terminalChecks == 1) {
                listOf(AppendMessage(content = "follow-up", createdAt = "2026-01-01 00:00:00"))
            } else {
                null
            }
        }

        override fun onInjected(messages: List<AppendMessage>, round: Int) {
            injectedCount += messages.size
        }
    }

    private fun emptyToolRuntime(): ToolRuntime = ToolRuntime(
        definitions = emptyList(),
        tools = emptyList(),
        hook = ToolHook.NOOP,
        eventSink = null,
    )

    private fun okhttp3.Request.bodyJson(): JsonObject {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return JsonParser.parseString(buffer.readUtf8()).asJsonObject
    }

    private fun jsonResponse(request: okhttp3.Request, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}
