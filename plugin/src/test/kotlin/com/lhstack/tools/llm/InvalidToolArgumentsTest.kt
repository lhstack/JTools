package com.lhstack.tools.llm

import com.google.gson.*
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.http.ModelHttpExecutor
import com.lhstack.tools.agent.model.http.ModelRequestCancelledException
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
    fun `invalid arguments retry unchanged request across APIs and stream modes`() = forEachMode { api, stream ->
        for (bad in listOf("{\"path\":", "[]", "null", "{\"path\":1} trailing")) {
            val fixture = Fixture(
                api, stream, listOf(
                    Reply("discarded response", listOf(Call("candidate", "{}"), Call("bad1", bad))),
                    Reply(calls = listOf(Call("good1", "{}"))),
                    Reply("done"),
                )
            )
            val round = fixture.run()
            assertEquals(3, fixture.requests.size, "$api stream=$stream bad=$bad")
            assertEquals(fixture.requests[0], fixture.requests[1], "retry must not append failed response")
            assertEquals(1, fixture.executions)
            assertEquals(1, fixture.retries)
            assertEquals(listOf("good1"), fixture.toolEvents)
            assertEquals("done", round.response)
            val history = ProviderMessageHistory.toJson(round.historyMessages).toString()
            assertFalse(history.contains("bad1"))
            assertFalse(history.contains("candidate"))
            assertFalse(history.contains("discarded response"))
            assertFalse(history.contains("raw_arguments"))
            assertFalse(ProviderMessageHistory.toJson(round.providerMessages).toString().contains("bad1"))
        }
    }

    @Test
    fun `retry preserves preceding completed tools without executing them again`() = forEachMode { api, stream ->
        val fixture = Fixture(
            api, stream, listOf(
                Reply(calls = listOf(Call("before", "{}"))),
                Reply(calls = listOf(Call("bad1", "{"))),
                Reply(calls = listOf(Call("after", "{}"))),
                Reply("done"),
            )
        )
        val round = fixture.run()
        assertEquals(4, fixture.requests.size)
        assertEquals(fixture.requests[1], fixture.requests[2])
        assertTrue(fixture.requests[2].toString().contains("before"))
        assertFalse(fixture.requests[2].toString().contains("bad1"))
        assertEquals(2, fixture.executions)
        assertEquals(listOf("before", "after"), fixture.toolEvents)
        assertEquals(2, round.toolCalls.size)
    }

    @Test
    fun `persistent parse failure stops at retry limit without executing tools`() = forEachMode { api, stream ->
        val fixture = Fixture(api, stream, List(2) { Reply(calls = listOf(Call("bad1", "{"))) })
        val error = assertFailsWith<IllegalStateException> { fixture.run() }
        assertTrue(error.message.orEmpty().contains("参数解析失败"))
        assertEquals(2, fixture.requests.size)
        assertEquals(fixture.requests[0], fixture.requests[1])
        assertEquals(1, fixture.retries)
        assertEquals(0, fixture.executions)
        assertTrue(fixture.toolEvents.isEmpty())
    }

    @Test
    fun `cancellation during parse retry does not send another request`() = forEachMode { api, stream ->
        val fixture = Fixture(api, stream, listOf(Reply(calls = listOf(Call("bad1", "{")))))
        fixture.cancelOnRetry = true
        assertFailsWith<ModelRequestCancelledException> { fixture.run() }
        assertEquals(1, fixture.requests.size)
        assertEquals(0, fixture.executions)
        assertTrue(fixture.toolEvents.isEmpty())
    }

    @Test
    fun `execution failure remains a tool result instead of request retry`() = forEachMode { api, stream ->
        val fixture = Fixture(
            api, stream, listOf(
                Reply(calls = listOf(Call("good1", "{}"))), Reply("done"),
            )
        )
        fixture.failExecution = true
        fixture.run()
        assertEquals(2, fixture.requests.size)
        assertEquals(1, fixture.executions)
        assertEquals(0, fixture.retries)
        assertTrue(fixture.requests[1].toString().contains("工具调用失败: execution failed"))
    }

    @Test
    fun `invalid arguments throw instead of constructing a tool call`() {
        for (raw in listOf("{", "[]", "null")) {
            assertFailsWith<ToolCallParseException> { LlmJson.toolCall("bad", "bad", "read", raw) }
        }
    }

    @Test
    fun `runtime validates entire batch before events hooks or execution`() {
        var effects = 0
        val hook = object : ToolHook {
            override fun onToolCall(name: String, callId: String?, internalId: String, args: String) {
                effects++
            }

            override fun onToolResult(name: String, callId: String?, internalId: String, args: String, result: String, status: ToolExecutionStatus) {
                effects++
            }
        }
        val sink = object : ToolEventSink {
            override fun onToolCall(call: ProviderToolCall) {
                effects++
            }

            override fun onToolResult(result: ToolResult) {
                effects++
            }
        }
        val tool = object : ToolDyn {
            override fun definition(prompt: String) = ToolDefinition("read_file", "Read", JsonObject())
            override fun callJsonBlocking(args: JsonElement): JsonElement {
                effects++; return JsonObject()
            }
        }
        val runtime = ToolRuntime(listOf(tool.definition("")), listOf(tool), hook, sink)
        val valid = ProviderToolCall("good", "good", "read_file", JsonObject())
        for (invalid in listOf(
            ProviderToolCall("bad", "bad", "read_file", JsonNull.INSTANCE, "{"),
            ProviderToolCall("bad", "bad", "read_file", JsonArray()),
        )) {
            assertFailsWith<ToolCallParseException> { runtime.executeToolCalls(listOf(valid, invalid)) }
            assertEquals(0, effects)
        }
    }

    private fun forEachMode(verify: (String, Boolean) -> Unit) {
        for (api in listOf("chat", "responses", "anthropic")) {
            for (stream in listOf(false, true)) verify(api, stream)
        }
    }

    private data class Call(val id: String, val arguments: String)
    private data class Reply(val text: String = "", val calls: List<Call> = emptyList())

    private class Fixture(val api: String, val stream: Boolean, val replies: List<Reply>) {
        val requests = mutableListOf<JsonObject>()
        val toolEvents = mutableListOf<String>()
        val cancel = ModelCancel()
        var executions = 0
        var retries = 0
        var cancelOnRetry = false
        var failExecution = false
        private val sink = object : ToolEventSink {
            override fun onToolCall(call: ProviderToolCall) {
                toolEvents += call.id
            }

            override fun onToolResult(result: ToolResult) {}
            override fun onModelRetry(error: String) {
                retries++
                if (cancelOnRetry) cancel.cancel()
            }
        }
        private val tool = object : ToolDyn {
            override fun definition(prompt: String) = ToolDefinition("read_file", "Read", JsonObject())
            override fun callJsonBlocking(args: JsonElement): JsonElement {
                executions++
                if (failExecution) error("execution failed")
                return JsonObject()
            }
        }

        fun run(): ProviderRound {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = okio.Buffer()
                chain.request().body!!.writeTo(buffer)
                requests += JsonParser.parseString(buffer.readUtf8()).asJsonObject
                val reply = replies[requests.lastIndex]
                val response = if (stream) streamResponse(reply) else response(reply).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(response.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType()))
                    .build()
            }.build()
            val executor = ModelHttpExecutor(client)
            val runtime = ToolRuntime(listOf(tool.definition("")), listOf(tool), ToolHook.NOOP, sink)
            val params = ModelRuntimeParams()
            val definitions = listOf(tool.definition(""))
            val openai = OpenAiClient(
                OpenAiClientParams(
                    executor, "k", "https://example.test", OpenAiProviderType.COMPATIBLE,
                    httpTrace = null, eventSink = sink,
                )
            )
            return when (api) {
                "chat" -> {
                    val request = OpenAiChatRequest.fromRuntime(
                        "m", "s", emptyList(), params, OpenAiProviderType.COMPATIBLE,
                        null, definitions, stream, 4, 1,
                    )
                    if (stream) openai.stream(request, runtime, cancel, AppendMessageChannel.NONE)
                    else openai.chat(request, runtime, cancel, AppendMessageChannel.NONE)
                }

                "responses" -> openai.responses(
                    OpenAiResponsesRequest.fromRuntime(
                        "m", "s", emptyList(), params, OpenAiProviderType.COMPATIBLE,
                        null, definitions, stream, 4, 1,
                    ), runtime, cancel, AppendMessageChannel.NONE
                )

                else -> {
                    val anthropic = AnthropicClient(
                        AnthropicClientParams(
                            executor, "k", "https://example.test", null, httpTrace = null, eventSink = sink,
                        )
                    )
                    val request = AnthropicMessageRequest.fromRuntime(
                        "m", "s", emptyList(), params, null, definitions, stream, 4, 1,
                    )
                    if (stream) anthropic.stream(request, runtime, cancel, AppendMessageChannel.NONE)
                    else anthropic.chat(request, runtime, cancel, AppendMessageChannel.NONE)
                }
            }
        }

        private fun response(reply: Reply): JsonObject = when (api) {
            "chat" -> obj("choices" to arr(obj("message" to chatMessage(reply))))
            "responses" -> obj("output" to JsonArray().apply {
                if (reply.text.isNotEmpty()) add(
                    obj(
                        "type" to "message",
                        "content" to arr(obj("type" to "output_text", "text" to reply.text))
                    )
                )
                reply.calls.forEach {
                    add(
                        obj(
                            "type" to "function_call",
                            "id" to it.id,
                            "call_id" to it.id,
                            "name" to "read_file",
                            "arguments" to it.arguments
                        )
                    )
                }
            })

            else -> obj("content" to JsonArray().apply {
                if (reply.text.isNotEmpty()) add(obj("type" to "text", "text" to reply.text))
                reply.calls.forEach {
                    add(
                        obj(
                            "type" to "tool_use",
                            "id" to it.id,
                            "name" to "read_file",
                            "input" to it.arguments
                        )
                    )
                }
            })
        }

        private fun chatMessage(reply: Reply) = obj("content" to reply.text, "tool_calls" to JsonArray().apply {
            reply.calls.forEachIndexed { index, call ->
                add(
                    obj(
                        "index" to index,
                        "id" to call.id,
                        "function" to obj("name" to "read_file", "arguments" to call.arguments)
                    )
                )
            }
        })

        private fun streamResponse(reply: Reply): String = when (api) {
            "chat" -> "data: ${
                obj(
                    "choices" to arr(
                        obj(
                            "index" to 0,
                            "delta" to chatMessage(reply)
                        )
                    )
                )
            }\n\ndata: [DONE]\n\n"

            "responses" -> "data: ${
                obj(
                    "type" to "response.completed",
                    "response" to response(reply).apply { addProperty("status", "completed") })
            }\n\n"

            else -> buildString {
                if (reply.text.isNotEmpty()) append(
                    "event: content_block_delta\ndata: ${
                        obj(
                            "index" to 99,
                            "delta" to obj("text" to reply.text)
                        )
                    }\n\n"
                )
                reply.calls.forEachIndexed { index, call ->
                    append(
                        "event: content_block_start\ndata: ${
                            obj(
                                "index" to index,
                                "content_block" to obj(
                                    "type" to "tool_use",
                                    "id" to call.id,
                                    "name" to "read_file",
                                    "input" to JsonObject()
                                )
                            )
                        }\n\n"
                    )
                    append(
                        "event: content_block_delta\ndata: ${
                            obj(
                                "index" to index,
                                "delta" to obj("partial_json" to call.arguments)
                            )
                        }\n\n"
                    )
                }
                append("event: message_stop\ndata: {}\n\n")
            }
        }

        private fun obj(vararg entries: Pair<String, Any>): JsonObject = JsonObject().apply {
            for ((key, value) in entries) when (value) {
                is JsonElement -> add(key, value)
                is Number -> addProperty(key, value)
                is String -> addProperty(key, value)
                else -> error("unsupported fixture value")
            }
        }

        private fun arr(value: JsonElement) = JsonArray().apply { add(value) }
    }
}
