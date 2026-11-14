package com.lhstack.tools.llm.openai

import com.lhstack.tools.agent.model.http.ModelHttpExecutor
import com.lhstack.tools.agent.model.params.ModelRuntimeParams
import com.lhstack.tools.agent.model.params.OpenAiProviderType
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ProviderRound
import com.lhstack.tools.llm.provider.AppendMessageChannel
import com.lhstack.tools.llm.provider.ToolHook
import com.lhstack.tools.llm.provider.ToolRuntime
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiStreamErrorTest {
    @Test
    fun `Responses top-level error preserves provider message instead of null pointer`() {
        val failure = streamFailure("""{"type":"error","code":"server_error","message":"upstream connection closed"}""")
        assertTrue(failure.message.orEmpty().contains("upstream connection closed"), failure.message)
        assertFalse(failure.message.orEmpty().contains("isJsonObject"))
    }

    @Test
    fun `Responses error without message is reported explicitly`() {
        val failure = streamFailure("""{"type":"error","code":"server_error"}""")
        assertTrue(failure.message.orEmpty().contains("server_error"), failure.message)
        assertFalse(failure.message.orEmpty().contains("isJsonObject"))
    }

    @Test
    fun `nested error format retains provider type and message`() {
        val failure = streamFailure("""{"error":{"type":"rate_limit_error","message":"too many requests"}}""")
        assertTrue(failure.message.orEmpty().contains("rate_limit_error: too many requests"), failure.message)
    }

    @Test
    fun `null nested error still preserves top-level error details`() {
        val failure = streamFailure("""{"type":"error","error":null,"message":"upstream failed"}""")
        assertTrue(failure.message.orEmpty().contains("upstream failed"), failure.message)
    }

    @Test
    fun `normal Responses events without error field complete successfully`() {
        val round = runStream(listOf(
            """{"type":"response.output_text.delta","delta":"hello"}""",
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hello"}]}]}}""",
        ))
        assertEquals("hello", round.response)
    }

    private fun streamFailure(event: String): IllegalStateException =
        assertFailsWith { runStream(listOf(event)) }

    private fun runStream(events: List<String>): ProviderRound {
        val body = events.joinToString("") { "data: $it\n\n" }
        val executor = ModelHttpExecutor(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build())
        val client = OpenAiClient(OpenAiClientParams(executor, "", "https://fixture.invalid/v1", OpenAiProviderType.COMPATIBLE, httpTrace = null))
        val request = OpenAiResponsesRequest.fromRuntime(
            modelId = "test", preamble = "", messages = listOf(Message.user("hello")),
            params = ModelRuntimeParams(), openaiProviderType = OpenAiProviderType.COMPATIBLE,
            additionalParams = null, tools = emptyList(), stream = true, maxToolRounds = 1, maxRetries = 0,
        )
        return client.responses(request, ToolRuntime(emptyList(), emptyList(), ToolHook.NOOP, null), null, AppendMessageChannel.NONE)
    }
}
