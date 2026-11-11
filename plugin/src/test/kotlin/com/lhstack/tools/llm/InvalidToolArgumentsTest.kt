package com.lhstack.tools.llm

import com.google.gson.*
import com.lhstack.tools.agent.model.http.ModelHttpExecutor
import com.lhstack.tools.agent.model.params.ModelRuntimeParams
import com.lhstack.tools.agent.model.params.OpenAiProviderType
import com.lhstack.tools.llm.anthropic.*
import com.lhstack.tools.llm.openai.*
import com.lhstack.tools.llm.provider.*
import kotlin.test.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class InvalidToolArgumentsTest {
    @Test
    fun `completed invalid tool arguments are tool failures across all APIs and stream modes`() {
        for (api in listOf("chat", "responses", "anthropic")) {
            for (stream in listOf(false, true)) verifyFailure(api, stream)
        }
    }

    private fun verifyFailure(api: String, stream: Boolean) {
        var executions = 0
        val tool =
            object : ToolDyn {
                override fun definition(prompt: String) =
                    ToolDefinition("read_file", "Read", JsonObject())

                override fun callJsonBlocking(args: JsonElement): JsonElement {
                    executions++
                    return JsonObject()
                }
            }
        val requests = mutableListOf<JsonObject>()
        val bad = "{\"path\":"
        val first =
            when (api) {
                "chat" ->
                    json(
                        """{"choices":[{"message":{"tool_calls":[{"id":"bad1","function":{"name":"read_file","arguments":${Gson().toJson(bad)}}}]}}]}"""
                    )
                "responses" ->
                    json(
                        """{"output":[{"type":"function_call","id":"item1","call_id":"bad1","name":"read_file","arguments":${Gson().toJson(bad)}}]}"""
                    )
                else ->
                    json(
                        """{"content":[{"type":"tool_use","id":"bad1","name":"read_file","input":${Gson().toJson(bad)}}]}"""
                    )
            }
        val last =
            when (api) {
                "chat" -> """{"choices":[{"message":{"content":"received tool error"}}]}"""
                "responses" ->
                    """{"output":[{"type":"message","content":[{"type":"output_text","text":"received tool error"}]}]}"""
                else -> """{"content":[{"type":"text","text":"received tool error"}]}"""
            }
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val buffer = okio.Buffer()
                    chain.request().body!!.writeTo(buffer)
                    requests += json(buffer.readUtf8())
                    val response =
                        if (!stream) {
                            if (requests.size == 1) first.toString() else last
                        } else
                            when (api) {
                                "chat" ->
                                    if (requests.size == 1) {
                                        val delta =
                                            json(
                                                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"bad1","function":{"name":"read_file","arguments":${Gson().toJson(bad)}}}]}}]}"""
                                            )
                                        "data: $delta\n\ndata: [DONE]\n\n"
                                    } else
                                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"received tool error\"}}]}\n\ndata: [DONE]\n\n"
                                "responses" ->
                                    "data: ${JsonObject().apply {addProperty("type","response.completed")
add("response",(if(requests.size==1) first else json(last)).apply {addProperty("status","completed")})}}\n\n"
                                else ->
                                    if (requests.size == 1) {
                                        "event: content_block_start\ndata: {\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"bad1\",\"name\":\"read_file\",\"input\":{}}}\n\n" +
                                            "event: content_block_delta\ndata: ${JsonObject().apply {addProperty("index",0)
add("delta",JsonObject().apply {addProperty("partial_json",bad)})}}\n\n" +
                                            "event: message_stop\ndata: {}\n\n"
                                    } else
                                        "event: content_block_delta\ndata: {\"index\":0,\"delta\":{\"text\":\"received tool error\"}}\n\nevent: message_stop\ndata: {}\n\n"
                            }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            response.toResponseBody(
                                (if (stream) "text/event-stream" else "application/json")
                                    .toMediaType()
                            )
                        )
                        .build()
                }
                .build()
        val runtime = ToolRuntime(listOf(tool.definition("")), listOf(tool), ToolHook.NOOP, null)
        val params = ModelRuntimeParams()
        val definitions = listOf(tool.definition(""))
        val exec = ModelHttpExecutor(client)
        val openai =
            OpenAiClient(
                OpenAiClientParams(
                    exec,
                    "k",
                    "https://example.test",
                    OpenAiProviderType.COMPATIBLE,
                    httpTrace = null,
                )
            )
        val round =
            when (api) {
                "chat" -> {
                    val request =
                        OpenAiChatRequest.fromRuntime(
                            "m",
                            "s",
                            emptyList(),
                            params,
                            OpenAiProviderType.COMPATIBLE,
                            null,
                            definitions,
                            stream,
                            4,
                            3,
                        )
                    if (stream) openai.stream(request, runtime, null, AppendMessageChannel.NONE)
                    else openai.chat(request, runtime, null, AppendMessageChannel.NONE)
                }
                "responses" ->
                    openai.responses(
                        OpenAiResponsesRequest.fromRuntime(
                            "m",
                            "s",
                            emptyList(),
                            params,
                            OpenAiProviderType.COMPATIBLE,
                            null,
                            definitions,
                            stream,
                            4,
                            3,
                        ),
                        runtime,
                        null,
                        AppendMessageChannel.NONE,
                    )
                else -> {
                    val provider =
                        AnthropicClient(
                            AnthropicClientParams(
                                exec,
                                "k",
                                "https://example.test",
                                null,
                                httpTrace = null,
                            )
                        )
                    val request =
                        AnthropicMessageRequest.fromRuntime(
                            "m",
                            "s",
                            emptyList(),
                            params,
                            null,
                            definitions,
                            stream,
                            4,
                            3,
                        )
                    if (stream) provider.stream(request, runtime, null, AppendMessageChannel.NONE)
                    else provider.chat(request, runtime, null, AppendMessageChannel.NONE)
                }
            }
        assertEquals(0, executions, "$api stream=$stream")
        assertEquals(2, requests.size, "one follow-up, no retry: $api stream=$stream")
        assertTrue(requests[1].toString().contains("工具调用失败"), "$api stream=$stream")
        assertEquals("received tool error", round.response)
        assertTrue(
            ProviderMessageHistory.toJson(round.providerMessages)
                .toString()
                .contains("raw_arguments")
        )
    }

    @Test
    fun `invalid args stay scoped to one call and history retains the original source`() {
        val raw = "{\"path\":"
        val call = LlmJson.toolCall("bad", "bad", "read", raw)
        assertEquals(raw, call.argsString())
        val message = OpenAiParser.assistantMessage("", listOf(call))
        val restored =
            ProviderMessageHistory.fromJson(ProviderMessageHistory.toJson(listOf(message)))
        val function =
            ((restored.single() as Message.Assistant).content.single() as AssistantContent.ToolCall)
                .toolCall
                .function
        assertEquals(raw, function.rawArguments)
        assertTrue(function.arguments.isJsonNull)
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
}
