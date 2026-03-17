package com.lhstack.tools.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object AgentToolCallHistorySupport {

    fun assistantToolCallMessage(
        toolCallId: String,
        name: String,
        arguments: String,
    ): JsonObject {
        return JsonObject().apply {
            addProperty("role", "assistant")
            add("tool_calls", JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("id", toolCallId)
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", name)
                            addProperty("arguments", arguments)
                        })
                    }
                )
            })
        }
    }

    fun toolResultMessage(
        toolCallId: String,
        result: String,
        name: String? = null,
    ): JsonObject {
        return JsonObject().apply {
            addProperty("role", "tool")
            addProperty("tool_call_id", toolCallId)
            name?.takeIf { it.isNotBlank() }?.let { addProperty("name", it) }
            addProperty("content", result)
        }
    }

    fun sanitizeInPlace(messages: MutableList<JsonObject>) {
        if (messages.isEmpty()) {
            return
        }
        val sanitized = mutableListOf<JsonObject>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (!message.has("role")) {
                sanitized.add(message)
                index++
                continue
            }
            val role = message.get("role").asString
            if (role == "assistant" && message.has("tool_calls")) {
                val expectedIds = message.getAsJsonArray("tool_calls")
                    .mapNotNull { element ->
                        element.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
                    }
                    .toSet()
                val toolMessages = mutableListOf<JsonObject>()
                var cursor = index + 1
                while (cursor < messages.size && messages[cursor].get("role")?.asString == "tool") {
                    toolMessages.add(messages[cursor])
                    cursor++
                }
                val toolIds = toolMessages.mapNotNull { toolMessage ->
                    toolMessage.get("tool_call_id")?.takeIf { !it.isJsonNull }?.asString
                }.toSet()
                val validPair = expectedIds.isNotEmpty() && toolIds.containsAll(expectedIds)
                if (validPair) {
                    sanitized.add(message)
                    sanitized.addAll(toolMessages.filter { toolMessage ->
                        toolMessage.get("tool_call_id")?.takeIf { !it.isJsonNull }?.asString in expectedIds
                    })
                } else {
                    keepAssistantTextWithoutToolCalls(message)?.let { sanitized.add(it) }
                }
                index = cursor
                continue
            }
            if (role == "tool") {
                index++
                continue
            }
            sanitized.add(message)
            index++
        }
        messages.clear()
        messages.addAll(sanitized)
    }

    private fun keepAssistantTextWithoutToolCalls(message: JsonObject): JsonObject? {
        val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        if (content.isBlank()) {
            return null
        }
        return JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", content)
        }
    }
}
