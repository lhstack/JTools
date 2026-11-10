package com.lhstack.tools.llm

import com.google.gson.*
import com.lhstack.tools.agent.model.http.ModelHttpExecutor
import com.lhstack.tools.agent.model.params.*
import com.lhstack.tools.llm.anthropic.*
import com.lhstack.tools.llm.openai.*
import com.lhstack.tools.llm.provider.*
import kotlin.test.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class LazyMultimodalProtocolTest {
    private val picture = UserContent.imageBase64("aW1hZ2U=", ImageMediaType.PNG, null)
    private val tool =
        object : ToolDyn {
            override fun definition(prompt: String) =
                ToolDefinition(
                    "view_image",
                    "read",
                    JsonParser.parseString("""{"type":"object","properties":{}}"""),
                )

            override fun callJsonBlocking(args: JsonElement) = JsonObject()

            override fun executeBlocking(args: JsonElement) =
                ToolExecutionResult(
                    JsonObject().apply { addProperty("type", "resource_read_summary") },
                    listOf(ToolResource("image-v1", listOf(picture))),
                )
        }

    @Test
    fun `cancelled native result never injects resources or successful read summary`() {
        val cancel = com.lhstack.tools.agent.model.http.ModelCancel()
        val cancelTool =
            object : ToolDyn {
                override fun definition(prompt: String) = tool.definition(prompt)

                override fun callJsonBlocking(args: JsonElement) = JsonObject()

                override fun executeBlocking(args: JsonElement): ToolExecutionResult {
                    cancel.cancel()
                    return tool.executeBlocking(args)
                }
            }
        val runtime =
            ToolRuntime(
                listOf(cancelTool.definition("")),
                listOf(cancelTool),
                ToolHook.NOOP,
                null,
                conversationCancel = cancel,
            )
        val batch =
            runtime.executeToolCalls(listOf(ProviderToolCall("c", "c", "view_image", JsonObject())))
        assertEquals(1, batch.currentMessages.size)
        val serialized = ProviderMessageHistory.toJson(batch.historyMessages).toString()
        assertTrue(serialized.contains("取消"))
        assertFalse(serialized.contains("resource_read_summary"))
    }

    private fun runtime() =
        ToolRuntime(listOf(tool.definition("")), listOf(tool), ToolHook.NOOP, null)

    private fun executor(bodies: MutableList<JsonObject>, responses: List<String>) =
        ModelHttpExecutor(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val buffer = Buffer()
                    chain.request().body!!.writeTo(buffer)
                    bodies += JsonParser.parseString(buffer.readUtf8()).asJsonObject
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            responses[bodies.size - 1].toResponseBody(
                                "application/json".toMediaType()
                            )
                        )
                        .build()
                }
                .build()
        )

    @Test
    fun `chat tools complete before media original persists across another loop but history omits bytes`() {
        val bodies = mutableListOf<JsonObject>()
        val exec =
            executor(
                bodies,
                listOf(
                    """{"choices":[{"message":{"tool_calls":[{"id":"c1","type":"function","function":{"name":"view_image","arguments":"{}"}}]}}],"usage":{}}""",
                    """{"choices":[{"message":{"tool_calls":[{"id":"c2","type":"function","function":{"name":"view_image","arguments":"{}"}}]}}],"usage":{}}""",
                    """{"choices":[{"message":{"content":"seen"}}],"usage":{}}""",
                ),
            )
        val client =
            OpenAiClient(
                OpenAiClientParams(
                    exec,
                    "k",
                    "https://example.test/v1",
                    OpenAiProviderType.COMPATIBLE,
                    httpTrace = null,
                )
            )
        val request =
            OpenAiChatRequest.fromRuntime(
                "m",
                "",
                listOf(Message.user("[Attachment] path: image.png")),
                ModelRuntimeParams(),
                OpenAiProviderType.COMPATIBLE,
                null,
                listOf(tool.definition("")),
                false,
                4,
                0,
            )
        val result = client.chat(request, runtime(), null, AppendMessageChannel.NONE)
        assertFalse(bodies[0].toString().contains("aW1hZ2U="))
        val messages = bodies[1].getAsJsonArray("messages")
        assertTrue(messages.any { it.asJsonObject.get("role")?.asString == "tool" })
        assertTrue(
            messages.last().asJsonObject.getAsJsonArray("content").any {
                it.asJsonObject.get("type").asString == "image_url"
            }
        )
        assertTrue(bodies[2].toString().contains("aW1hZ2U="))
        val history = ProviderMessageHistory.toJson(result.historyMessages).toString()
        assertFalse(history.contains("aW1hZ2U="))
        assertTrue(history.contains("resource_read_summary"))
        val next =
            OpenAiChatRequest.fromRuntime(
                "m",
                "",
                result.historyMessages + Message.user("next turn"),
                ModelRuntimeParams(),
                OpenAiProviderType.COMPATIBLE,
                null,
                emptyList(),
                false,
                4,
                0,
            )
        assertFalse(next.body.toString().contains("aW1hZ2U="))
    }

    private fun streamExecutor(bodies: MutableList<JsonObject>, responses: List<String>) =
        ModelHttpExecutor(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val buffer = Buffer()
                    chain.request().body!!.writeTo(buffer)
                    bodies += JsonParser.parseString(buffer.readUtf8()).asJsonObject
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            responses[bodies.size - 1].toResponseBody(
                                "text/event-stream".toMediaType()
                            )
                        )
                        .build()
                }
                .build()
        )

    @Test
    fun `streaming chat also injects native image only after tool and keeps summary history`() {
        val bodies = mutableListOf<JsonObject>()
        val toolDelta =
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"view_image","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        val textDelta =
            """{"choices":[{"index":0,"delta":{"content":"seen"},"finish_reason":"stop"}]}"""
        val exec =
            streamExecutor(
                bodies,
                listOf(
                    "data: $toolDelta\n\ndata: [DONE]\n\n",
                    "data: $textDelta\n\ndata: [DONE]\n\n",
                ),
            )
        val client =
            OpenAiClient(
                OpenAiClientParams(
                    exec,
                    "k",
                    "https://example.test/v1",
                    OpenAiProviderType.COMPATIBLE,
                    httpTrace = null,
                )
            )
        val request =
            OpenAiChatRequest.fromRuntime(
                "m",
                "",
                listOf(Message.user("metadata")),
                ModelRuntimeParams(),
                OpenAiProviderType.COMPATIBLE,
                null,
                listOf(tool.definition("")),
                true,
                4,
                0,
            )
        val result = client.stream(request, runtime(), null, AppendMessageChannel.NONE)
        assertEquals(2, bodies.size)
        assertTrue(bodies[1].toString().contains("image_url"))
        assertFalse(
            ProviderMessageHistory.toJson(result.historyMessages).toString().contains("aW1hZ2U=")
        )
    }

    @Test
    fun `streaming anthropic keeps tool result before image and media out of history`() {
        val bodies = mutableListOf<JsonObject>()
        val toolStart =
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"c1","name":"view_image","input":{}}}"""
        val toolInput =
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}"""
        val textStart =
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        val textDelta =
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"seen"}}"""
        val exec =
            streamExecutor(
                bodies,
                listOf(
                    "data: $toolStart\n\ndata: $toolInput\n\ndata: {\"type\":\"message_stop\"}\n\n",
                    "data: $textStart\n\ndata: $textDelta\n\ndata: {\"type\":\"message_stop\"}\n\n",
                ),
            )
        val client =
            AnthropicClient(
                AnthropicClientParams(exec, "k", "https://example.test", null, httpTrace = null)
            )
        val request =
            AnthropicMessageRequest.fromRuntime(
                "m",
                "",
                listOf(Message.user("metadata")),
                ModelRuntimeParams(),
                null,
                listOf(tool.definition("")),
                true,
                4,
                0,
            )
        val result = client.stream(request, runtime(), null, AppendMessageChannel.NONE)
        assertEquals(2, bodies.size)
        val types =
            bodies[1].getAsJsonArray("messages").flatMap {
                it.asJsonObject.getAsJsonArray("content").map { part ->
                    part.asJsonObject.get("type").asString
                }
            }
        assertTrue(types.indexOf("tool_result") < types.indexOf("image"))
        assertFalse(
            ProviderMessageHistory.toJson(result.historyMessages).toString().contains("aW1hZ2U=")
        )
    }

    @Test
    fun `streaming responses delivers native resources without persisting their bytes`() {
        val bodies = mutableListOf<JsonObject>()
        val call =
            """{"type":"response.completed","response":{"id":"r1","status":"completed","output":[{"type":"function_call","id":"f1","call_id":"c1","name":"view_image","arguments":"{}"}],"usage":{}}}"""
        val answer =
            """{"type":"response.completed","response":{"id":"r2","status":"completed","output":[{"type":"message","id":"a2","role":"assistant","content":[{"type":"output_text","text":"seen"}]}],"usage":{}}}"""
        val exec = streamExecutor(bodies, listOf("data: $call\n\n", "data: $answer\n\n"))
        val client =
            OpenAiClient(
                OpenAiClientParams(
                    exec,
                    "k",
                    "https://example.test/v1",
                    OpenAiProviderType.COMPATIBLE,
                    httpTrace = null,
                )
            )
        val request =
            OpenAiResponsesRequest.fromRuntime(
                "m",
                "",
                listOf(Message.user("metadata")),
                ModelRuntimeParams(),
                OpenAiProviderType.COMPATIBLE,
                null,
                listOf(tool.definition("")),
                true,
                4,
                0,
            )
        val result = client.responses(request, runtime(), null, AppendMessageChannel.NONE)
        assertEquals(2, bodies.size)
        assertTrue(bodies[1].toString().contains("input_image"))
        assertFalse(
            ProviderMessageHistory.toJson(result.historyMessages).toString().contains("aW1hZ2U=")
        )
    }

    @Test
    fun `responses uses function output then native input image and omits it from history`() {
        val bodies = mutableListOf<JsonObject>()
        val exec =
            executor(
                bodies,
                listOf(
                    """{"id":"r1","output":[{"type":"function_call","id":"f1","call_id":"c1","name":"view_image","arguments":"{}"}],"usage":{}}""",
                    """{"id":"r2","output":[{"type":"message","id":"a2","role":"assistant","content":[{"type":"output_text","text":"seen"}]}],"usage":{}}""",
                ),
            )
        val client =
            OpenAiClient(
                OpenAiClientParams(
                    exec,
                    "k",
                    "https://example.test/v1",
                    OpenAiProviderType.COMPATIBLE,
                    httpTrace = null,
                )
            )
        val request =
            OpenAiResponsesRequest.fromRuntime(
                "m",
                "",
                listOf(Message.user("metadata")),
                ModelRuntimeParams(),
                OpenAiProviderType.COMPATIBLE,
                null,
                listOf(tool.definition("")),
                false,
                4,
                0,
            )
        val result = client.responses(request, runtime(), null, AppendMessageChannel.NONE)
        assertFalse(bodies[0].toString().contains("aW1hZ2U="))
        val input = bodies[1].getAsJsonArray("input")
        val output =
            input.indexOfFirst { it.asJsonObject.get("type")?.asString == "function_call_output" }
        val image =
            input.indexOfFirst {
                it.asJsonObject.get("content")?.toString()?.contains("input_image") == true
            }
        assertTrue(output >= 0 && image > output)
        assertFalse(
            ProviderMessageHistory.toJson(result.historyMessages).toString().contains("aW1hZ2U=")
        )
    }

    @Test
    fun `anthropic encodes tool result and native image separately and returns summarized history`() {
        val bodies = mutableListOf<JsonObject>()
        val exec =
            executor(
                bodies,
                listOf(
                    """{"id":"a","type":"message","role":"assistant","content":[{"type":"tool_use","id":"c1","name":"view_image","input":{}}],"stop_reason":"tool_use","usage":{}}""",
                    """{"id":"b","type":"message","role":"assistant","content":[{"type":"text","text":"seen"}],"stop_reason":"end_turn","usage":{}}""",
                ),
            )
        val client =
            AnthropicClient(
                AnthropicClientParams(exec, "k", "https://example.test", null, httpTrace = null)
            )
        val request =
            AnthropicMessageRequest.fromRuntime(
                "m",
                "",
                listOf(Message.user("metadata")),
                ModelRuntimeParams(),
                null,
                listOf(tool.definition("")),
                false,
                4,
                0,
            )
        val result = client.chat(request, runtime(), null, AppendMessageChannel.NONE)
        assertFalse(bodies[0].toString().contains("aW1hZ2U="))
        val types =
            bodies[1].getAsJsonArray("messages").flatMap {
                it.asJsonObject.getAsJsonArray("content").map { part ->
                    part.asJsonObject.get("type").asString
                }
            }
        assertTrue(types.indexOf("tool_result") < types.indexOf("image"))
        assertFalse(
            ProviderMessageHistory.toJson(result.historyMessages).toString().contains("aW1hZ2U=")
        )
    }
}
