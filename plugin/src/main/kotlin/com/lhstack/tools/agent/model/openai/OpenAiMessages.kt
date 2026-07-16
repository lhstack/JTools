package com.lhstack.tools.agent.model.openai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.lhstack.tools.agent.model.http.ModelHttpSupport
import com.lhstack.tools.agent.model.llm.AssistantContent
import com.lhstack.tools.agent.model.llm.ImageDetail
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.UserContent

/**
 * OpenAI 消息 / 工具的 JSON 转换。完全照抄 awake-claw src/service/model_provider/openai.rs
 * 里的 chat_messages / chat_message / chat_user_messages / chat_assistant_message /
 * chat_content_part / response_input / append_response_* / response_content_part /
 * chat_tools / response_tools。
 */
internal object OpenAiMessages {

    // -------- Chat Completions --------

    /** 照抄 chat_messages：preamble 非空时置顶 system。 */
    fun chatMessages(preamble: String, messages: List<Message>): JsonArray {
        val values = JsonArray()
        if (preamble.isNotBlank()) {
            values.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", preamble)
            })
        }
        messages.forEach { message -> chatMessage(message).forEach { values.add(it) } }
        return values
    }

    /** 照抄 chat_message。 */
    fun chatMessage(message: Message): List<JsonObject> = when (message) {
        is Message.System -> listOf(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", message.content)
        })

        is Message.User -> chatUserMessages(message.content)
        is Message.Assistant -> listOf(chatAssistantMessage(message.content))
    }

    /** 照抄 chat_user_messages：纯 tool_result 拆成多条 role=tool；否则一条 role=user。 */
    private fun chatUserMessages(content: List<UserContent>): List<JsonObject> {
        if (content.all { it is UserContent.ToolResult }) {
            return content.mapNotNull { item ->
                val result = (item as? UserContent.ToolResult)?.toolResult ?: return@mapNotNull null
                JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", result.callId ?: result.id)
                    addProperty("content", formatToolResultContent(result.content))
                }
            }
        }
        return listOf(JsonObject().apply {
            addProperty("role", "user")
            val parts = JsonArray()
            content.forEach { parts.add(chatContentPart(it)) }
            add("content", parts)
        })
    }

    /** 照抄 chat_assistant_message。 */
    private fun chatAssistantMessage(content: List<AssistantContent>): JsonObject {
        val text = StringBuilder()
        val calls = JsonArray()
        for (item in content) {
            when (item) {
                is AssistantContent.Text -> text.append(item.text)
                is AssistantContent.ToolCall -> {
                    val call = item.toolCall
                    calls.add(JsonObject().apply {
                        addProperty("id", call.callId ?: call.id)
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", call.function.name)
                            addProperty("arguments", call.function.arguments.toString())
                        })
                    })
                }

                is AssistantContent.Reasoning -> {}
            }
        }
        return JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text.toString())
            if (calls.size() > 0) {
                add("tool_calls", calls)
            }
        }
    }

    /** 照抄 chat_content_part。 */
    private fun chatContentPart(content: UserContent): JsonObject = when (content) {
        is UserContent.Text -> JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", content.text)
        }

        is UserContent.Image -> JsonObject().apply {
            addProperty("type", "image_url")
            add("image_url", JsonObject().apply {
                addProperty("url", ModelHttpSupport.imageUrl(content.image))
                addProperty(
                    "detail",
                    ModelHttpSupport.imageDetail(content.image.detail ?: ImageDetail.Auto),
                )
            })
        }

        is UserContent.Audio -> JsonObject().apply {
            addProperty("type", "input_audio")
            add("input_audio", JsonObject().apply {
                addProperty("data", ModelHttpSupport.sourceData(content.audio.data))
                content.audio.mediaType?.let { addProperty("format", it.toMimeType()) }
            })
        }

        is UserContent.Document -> JsonObject().apply {
            addProperty("type", "file")
            add("file", JsonObject().apply {
                addProperty("file_data", ModelHttpSupport.sourceData(content.document.data))
                filenameOf(content.document.additionalParams)?.let { addProperty("filename", it) }
            })
        }

        is UserContent.Video -> JsonObject().apply {
            addProperty("type", "input_video")
            add("input_video", JsonObject().apply {
                addProperty("data", ModelHttpSupport.sourceData(content.video.data))
                content.video.mediaType?.let { addProperty("format", it.toMimeType()) }
            })
        }

        is UserContent.ToolResult -> JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", formatToolResultContent(content.toolResult.content))
        }
    }

    // -------- Responses --------

    /** 照抄 response_input。 */
    fun responseInput(messages: List<Message>): JsonArray {
        val items = JsonArray()
        for (message in messages) {
            when (message) {
                is Message.System -> items.add(JsonObject().apply {
                    addProperty("type", "message")
                    addProperty("role", "system")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "input_text")
                            addProperty("text", message.content)
                        })
                    })
                })

                is Message.User -> appendResponseUserInput(items, message.content)
                is Message.Assistant -> appendResponseAssistantInput(items, message.content)
            }
        }
        return items
    }

    /** 照抄 append_response_user_input。 */
    private fun appendResponseUserInput(items: JsonArray, content: List<UserContent>) {
        if (content.all { it is UserContent.ToolResult }) {
            content.forEach { item ->
                val result = (item as? UserContent.ToolResult)?.toolResult ?: return@forEach
                items.add(JsonObject().apply {
                    addProperty("type", "function_call_output")
                    addProperty("call_id", result.callId ?: result.id)
                    addProperty("output", formatToolResultContent(result.content))
                })
            }
            return
        }
        items.add(JsonObject().apply {
            addProperty("type", "message")
            addProperty("role", "user")
            val parts = JsonArray()
            content.forEach { parts.add(responseContentPart(it)) }
            add("content", parts)
        })
    }

    /** 照抄 append_response_assistant_input。 */
    private fun appendResponseAssistantInput(items: JsonArray, content: List<AssistantContent>) {
        val textParts = JsonArray()
        for (item in content) {
            when (item) {
                is AssistantContent.Text -> textParts.add(JsonObject().apply {
                    addProperty("type", "output_text")
                    addProperty("text", item.text)
                })

                is AssistantContent.ToolCall -> {
                    val call = item.toolCall
                    items.add(JsonObject().apply {
                        addProperty("type", "function_call")
                        addProperty("call_id", call.callId ?: call.id)
                        addProperty("name", call.function.name)
                        addProperty("arguments", call.function.arguments.toString())
                    })
                }

                is AssistantContent.Reasoning -> {}
            }
        }
        if (textParts.size() > 0) {
            items.add(JsonObject().apply {
                addProperty("type", "message")
                addProperty("role", "assistant")
                add("content", textParts)
            })
        }
    }

    /** 照抄 response_content_part。 */
    private fun responseContentPart(content: UserContent): JsonObject = when (content) {
        is UserContent.Text -> JsonObject().apply {
            addProperty("type", "input_text")
            addProperty("text", content.text)
        }

        is UserContent.Image -> JsonObject().apply {
            addProperty("type", "input_image")
            addProperty("image_url", ModelHttpSupport.imageUrl(content.image))
            addProperty(
                "detail",
                ModelHttpSupport.imageDetail(content.image.detail ?: ImageDetail.Auto),
            )
        }

        is UserContent.Document -> JsonObject().apply {
            addProperty("type", "input_file")
            addProperty("file_data", ModelHttpSupport.sourceData(content.document.data))
            filenameOf(content.document.additionalParams)?.let { addProperty("filename", it) }
        }

        else -> JsonObject().apply {
            addProperty("type", "input_text")
            addProperty("text", com.google.gson.Gson().toJson(content))
        }
    }

    // -------- tools --------

    /** 照抄 chat_tools。 */
    fun chatTools(tools: List<ToolDefinition>): JsonArray {
        val array = JsonArray()
        tools.forEach { tool ->
            array.add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", tool.name)
                    addProperty("description", tool.description)
                    add("parameters", tool.parameters)
                })
            })
        }
        return array
    }

    /** 照抄 response_tools。 */
    fun responseTools(tools: List<ToolDefinition>): JsonArray {
        val array = JsonArray()
        tools.forEach { tool ->
            array.add(JsonObject().apply {
                addProperty("type", "function")
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                add("parameters", tool.parameters)
            })
        }
        return array
    }

    // -------- helpers --------

    /** 照抄 format_tool_result_content：文本取原文，图片序列化，多个以换行拼接。 */
    fun formatToolResultContent(content: List<com.lhstack.tools.agent.model.llm.ToolResultContent>): String {
        return content.joinToString("\n") { item ->
            when (item) {
                is com.lhstack.tools.agent.model.llm.ToolResultContent.Text -> item.text
                is com.lhstack.tools.agent.model.llm.ToolResultContent.Image ->
                    com.google.gson.Gson().toJson(item.image)
            }
        }
    }

    private fun filenameOf(additionalParams: com.google.gson.JsonElement?): String? {
        val obj = additionalParams?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val filename = obj.get("filename") ?: return null
        return if (filename.isJsonPrimitive && (filename as JsonPrimitive).isString) filename.asString else null
    }
}
