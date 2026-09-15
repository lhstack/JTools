package com.lhstack.tools.llm.anthropic

import com.lhstack.tools.llm.LlmJson

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.lhstack.tools.agent.model.http.ModelHttpSupport
import com.lhstack.tools.llm.AssistantContent
import com.lhstack.tools.llm.DocumentSourceKind
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolResultContent
import com.lhstack.tools.llm.UserContent

/**
 * Anthropic 消息转换。完全照抄 awake-claw anthropic.rs 的 messages_from_runtime /
 * user_content / assistant_content / function_tools / format_tool_result_content。
 *
 * serde_json::Value -> Gson JsonElement，system 为 text block 数组，messages 为
 * user/assistant 角色 + content 数组。
 */
internal object AnthropicMessages {

    /**
     * 照抄 messages_from_runtime：返回 (system text blocks, message params)。
     * preamble 非空作为首个 system text block；System 消息也并入 system。
     */
    fun messagesFromRuntime(preamble: String, messages: List<Message>): Pair<JsonArray, List<JsonObject>> {
        val system = JsonArray()
        if (preamble.trim().isNotEmpty()) {
            system.add(textBlock(preamble))
        }
        val values = mutableListOf<JsonObject>()
        for (message in messages) {
            when (message) {
                is Message.System -> system.add(textBlock(message.content))
                is Message.User -> values.add(
                    messageParam("user", JsonArray().apply {
                        message.content.forEach { add(userContent(it)) }
                    })
                )
                is Message.Assistant -> values.add(
                    messageParam("assistant", JsonArray().apply {
                        message.content.forEach { add(assistantContent(it)) }
                    })
                )
            }
        }
        return system to values
    }

    /** 照抄 message_params_from_messages：只取 message params，丢弃 system。 */
    fun messageParamsFromMessages(messages: List<Message>): List<JsonObject> =
        messagesFromRuntime("", messages).second

    /** 照抄 user_content。 */
    private fun userContent(content: UserContent): JsonObject = when (content) {
        is UserContent.Text -> JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", content.text)
        }

        is UserContent.Image -> {
            val (mediaType, data) = LlmJson.imageBase64Parts(content.image)
            JsonObject().apply {
                addProperty("type", "image")
                add("source", JsonObject().apply {
                    addProperty("type", "base64")
                    addProperty("media_type", mediaType)
                    addProperty("data", data)
                })
            }
        }

        is UserContent.Document -> JsonObject().apply {
            addProperty("type", "document")
            add("source", JsonObject().apply {
                val isText = content.document.data is DocumentSourceKind.Str
                addProperty("type", if (isText) "text" else "base64")
                content.document.mediaType?.let { addProperty("media_type", it.toMimeType()) }
                addProperty("data", LlmJson.sourceData(content.document.data))
            })
            val title = objectField(content.document.additionalParams, "title")
            if (title != null) addProperty("title", title)
        }

        is UserContent.ToolResult -> JsonObject().apply {
            addProperty("type", "tool_result")
            addProperty("tool_use_id", content.toolResult.callId ?: content.toolResult.id)
            addProperty("content", formatToolResultContent(content.toolResult.content))
        }

        else -> JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", com.google.gson.Gson().toJson(content))
        }
    }

    /** 照抄 assistant_content。 */
    private fun assistantContent(content: AssistantContent): JsonObject = when (content) {
        is AssistantContent.Text -> JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", content.text)
        }

        is AssistantContent.ToolCall -> JsonObject().apply {
            addProperty("type", "tool_use")
            addProperty("id", content.toolCall.callId ?: content.toolCall.id)
            addProperty("name", content.toolCall.function.name)
            add("input", content.toolCall.function.arguments)
        }

        is AssistantContent.Reasoning -> JsonObject().apply {
            addProperty("type", "thinking")
            addProperty("thinking", content.reasoning.displayText())
        }
    }

    /** 照抄 function_tools。 */
    fun functionTools(tools: List<ToolDefinition>): JsonArray = JsonArray().apply {
        tools.forEach { tool ->
            add(JsonObject().apply {
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                add("input_schema", tool.parameters)
            })
        }
    }

    /** 照抄 format_tool_result_content：文本直接取，图片序列化，多项换行拼接。 */
    fun formatToolResultContent(content: List<ToolResultContent>): String {
        val values = content.map { item ->
            when (item) {
                is ToolResultContent.Text -> item.text
                is ToolResultContent.Image -> com.google.gson.Gson().toJson(item.image)
            }
        }
        return values.joinToString("\n")
    }

    private fun textBlock(text: String): JsonObject = JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
    }

    private fun messageParam(role: String, content: JsonArray): JsonObject = JsonObject().apply {
        addProperty("role", role)
        add("content", content)
    }

    private fun objectField(element: JsonElement?, key: String): String? {
        val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val value = obj.get(key) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }
}
