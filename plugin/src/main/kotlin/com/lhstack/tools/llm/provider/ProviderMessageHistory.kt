package com.lhstack.tools.llm.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.lhstack.tools.llm.AssistantContent
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.Reasoning
import com.lhstack.tools.llm.ReasoningContent
import com.lhstack.tools.llm.ToolCall
import com.lhstack.tools.llm.ToolFunction
import com.lhstack.tools.llm.ToolResult
import com.lhstack.tools.llm.ToolResultContent
import com.lhstack.tools.llm.UserContent

/** Persists the exact text/tool message sequence emitted during one provider run. */
internal object ProviderMessageHistory {
    fun toJson(messages: List<Message>): JsonArray = JsonArray().apply {
        messages.forEach { add(messageToJson(it)) }
    }

    fun fromJson(value: JsonElement?): List<Message> {
        if (value == null || !value.isJsonArray) return emptyList()
        return value.asJsonArray.mapIndexed { index, element ->
            require(element.isJsonObject) { "provider_messages[$index] must be an object" }
            messageFromJson(element.asJsonObject, index)
        }
    }

    private fun messageToJson(message: Message): JsonObject = JsonObject().apply {
        when (message) {
            is Message.System -> {
                addProperty("role", "system")
                addProperty("content", message.content)
            }
            is Message.User -> {
                addProperty("role", "user")
                add("content", JsonArray().apply { message.content.forEach { add(userContentToJson(it)) } })
            }
            is Message.Assistant -> {
                addProperty("role", "assistant")
                message.id?.let { addProperty("id", it) }
                add("content", JsonArray().apply { message.content.forEach { add(assistantContentToJson(it)) } })
            }
        }
    }

    private fun userContentToJson(content: UserContent): JsonObject = when (content) {
        is UserContent.Text -> typedText("text", content.text)
        is UserContent.ToolResult -> JsonObject().apply {
            addProperty("type", "tool_result")
            addProperty("id", content.toolResult.id)
            content.toolResult.callId?.let { addProperty("call_id", it) }
            add("content", JsonArray().apply {
                content.toolResult.content.forEach { result ->
                    when (result) {
                        is ToolResultContent.Text -> add(typedText("text", result.text))
                        is ToolResultContent.Image -> error("Provider history does not support image tool results")
                    }
                }
            })
        }
        else -> error("Provider history only supports text and tool-result user content")
    }

    private fun assistantContentToJson(content: AssistantContent): JsonObject = when (content) {
        is AssistantContent.Text -> typedText("text", content.text)
        is AssistantContent.Reasoning -> JsonObject().apply {
            addProperty("type", "reasoning")
            content.reasoning.id?.let { addProperty("id", it) }
            add("content", JsonArray().apply {
                content.reasoning.content.forEach { add(reasoningContentToJson(it)) }
            })
        }
            is AssistantContent.ToolCall -> {
                require(content.toolCall.signature == null && content.toolCall.additionalParams == null) {
                    "Provider history cannot persist tool-call metadata"
                }
                JsonObject().apply {
                    val call = content.toolCall
                    addProperty("type", "tool_call")
                    addProperty("id", call.id)
                    call.callId?.let { addProperty("call_id", it) }
                    addProperty("name", call.function.name)
                    add("arguments", call.function.arguments.deepCopy())
                }
            }
    }

    private fun reasoningContentToJson(content: ReasoningContent): JsonObject = when (content) {
        is ReasoningContent.Text -> typedText("text", content.text)
        is ReasoningContent.Summary -> typedText("summary", content.text)
        is ReasoningContent.Redacted -> JsonObject().apply {
            addProperty("type", "redacted")
            addProperty("data", content.data)
        }
        is ReasoningContent.Encrypted -> JsonObject().apply {
            addProperty("type", "encrypted")
            addProperty("data", content.data)
        }
    }

    private fun messageFromJson(value: JsonObject, index: Int): Message = when (requiredString(value, "role")) {
        "system" -> Message.System(requiredString(value, "content"))
        "assistant" -> Message.Assistant(
            id = optionalString(value, "id"),
            content = requiredArray(value, "content").mapIndexed { contentIndex, content ->
                assistantContentFromJson(requiredObject(content, "provider_messages[$index].content[$contentIndex]"))
            },
        )
        "user" -> Message.User(requiredArray(value, "content").mapIndexed { contentIndex, content ->
            userContentFromJson(requiredObject(content, "provider_messages[$index].content[$contentIndex]"))
        })
        else -> error("provider_messages[$index] has unsupported role")
    }

    private fun assistantContentFromJson(value: JsonObject): AssistantContent = when (requiredString(value, "type")) {
        "text" -> AssistantContent.Text(requiredString(value, "text"))
        "reasoning" -> AssistantContent.Reasoning(Reasoning(
            id = optionalString(value, "id"),
            content = requiredArray(value, "content").map { reasoningContentFromJson(requiredObject(it, "reasoning content")) },
        ))
        "tool_call" -> AssistantContent.ToolCall(ToolCall(
            id = requiredString(value, "id"),
            callId = optionalString(value, "call_id"),
            function = ToolFunction(requiredString(value, "name"), requireNotNull(value.get("arguments")) { "tool_call.arguments is required" }.deepCopy()),
            signature = null,
            additionalParams = null,
        ))
        else -> error("Unsupported assistant provider content type")
    }

    private fun userContentFromJson(value: JsonObject): UserContent = when (requiredString(value, "type")) {
        "text" -> UserContent.Text(requiredString(value, "text"))
        "tool_result" -> UserContent.ToolResult(ToolResult(
            id = requiredString(value, "id"),
            callId = optionalString(value, "call_id"),
            content = requiredArray(value, "content").map { result ->
                val resultObject = requiredObject(result, "tool result content")
                require(requiredString(resultObject, "type") == "text") { "Only text tool results are supported" }
                ToolResultContent.Text(requiredString(resultObject, "text"))
            },
        ))
        else -> error("Unsupported user provider content type")
    }

    private fun reasoningContentFromJson(value: JsonObject): ReasoningContent = when (requiredString(value, "type")) {
        "text" -> ReasoningContent.Text(requiredString(value, "text"))
        "summary" -> ReasoningContent.Summary(requiredString(value, "text"))
        "redacted" -> ReasoningContent.Redacted(requiredString(value, "data"))
        "encrypted" -> ReasoningContent.Encrypted(requiredString(value, "data"))
        else -> error("Unsupported reasoning content type")
    }

    private fun typedText(type: String, text: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("text", text)
    }

    private fun requiredArray(value: JsonObject, name: String): JsonArray =
        requireNotNull(value.get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray) { "$name must be an array" }

    private fun requiredObject(value: JsonElement, path: String): JsonObject =
        requireNotNull(value.takeIf(JsonElement::isJsonObject)?.asJsonObject) { "$path must be an object" }

    private fun requiredString(value: JsonObject, name: String): String =
        requireNotNull(optionalString(value, name)) { "$name must be a string" }

    private fun optionalString(value: JsonObject, name: String): String? =
        value.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
