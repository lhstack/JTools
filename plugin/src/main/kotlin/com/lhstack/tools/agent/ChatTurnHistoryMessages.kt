package com.lhstack.tools.agent

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.llm.AssistantContent
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ToolCall
import com.lhstack.tools.agent.model.llm.ToolFunction
import com.lhstack.tools.agent.model.llm.ToolResult
import com.lhstack.tools.agent.model.llm.ToolResultContent
import com.lhstack.tools.agent.model.llm.UserContent

/** Restores only complete, valid tool-call/result pairs from persisted history. */
internal object ChatTurnHistoryMessages {
    fun append(messages: MutableList<Message>, structured: JsonObject?) {
        if (structured == null) return
        val pairs = validToolPairs(structured)
        if (pairs.isNotEmpty()) {
            messages.add(Message.Assistant(id = null, content = pairs.map { it.call }))
            messages.add(Message.User(pairs.map { it.result }))
        }
        stringValue(structured.get("response")).takeIf(String::isNotBlank)?.let {
            messages.add(Message.assistant(it))
        }
    }

    private fun validToolPairs(structured: JsonObject): List<ToolPair> {
        val results = buildMap {
            arrayObjects(structured, "tool_results").mapNotNull(::resultRecord).forEach { result ->
                put(result.internalId, result)
                result.externalId?.let { put(it, result) }
            }
        }
        return arrayObjects(structured, "tool_calls").mapNotNull { call ->
            val internalId = stringValue(call.get("internal_call_id"))
                .ifBlank { stringValue(call.get("tool_call_id")) }
            val toolName = stringValue(call.get("tool_name"))
            val arguments = normalizeArguments(call.get("args")) ?: return@mapNotNull null
            if (internalId.isBlank() || toolName.isBlank()) return@mapNotNull null
            val externalId = stringValue(call.get("tool_call_id")).takeIf(String::isNotBlank)
            val result = results[internalId] ?: externalId?.let(results::get) ?: return@mapNotNull null
            ToolPair(
                AssistantContent.ToolCall(
                    ToolCall(
                        id = internalId,
                        callId = externalId,
                        function = ToolFunction(toolName, arguments),
                        signature = null,
                        additionalParams = null,
                    ),
                ),
                UserContent.ToolResult(
                    ToolResult(
                        id = internalId,
                        callId = externalId ?: result.externalId,
                        content = listOf(ToolResultContent.Text(result.content)),
                    ),
                ),
            )
        }
    }

    /** Arguments must be an object. Unwrap historical double-encoded JSON strings. */
    internal fun normalizeArguments(value: JsonElement?): JsonObject? {
        var current = value ?: return null
        repeat(2) {
            if (current.isJsonObject) return current.asJsonObject.deepCopy()
            if (!current.isJsonPrimitive || !current.asJsonPrimitive.isString) return null
            current = runCatching { JsonParser.parseString(current.asString) }.getOrNull() ?: return null
        }
        return current.takeIf(JsonElement::isJsonObject)?.asJsonObject?.deepCopy()
    }

    private fun resultRecord(value: JsonObject): ResultRecord? {
        val internalId = stringValue(value.get("internal_call_id"))
            .ifBlank { stringValue(value.get("tool_call_id")) }
        if (internalId.isBlank()) return null
        return ResultRecord(
            internalId,
            stringValue(value.get("tool_call_id")).takeIf(String::isNotBlank),
            stringValue(value.get("result")),
        )
    }

    private fun arrayObjects(value: JsonObject, name: String): List<JsonObject> =
        value.get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .orEmpty()

    private fun stringValue(value: JsonElement?): String =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

    private data class ToolPair(val call: AssistantContent.ToolCall, val result: UserContent.ToolResult)
    private data class ResultRecord(val internalId: String, val externalId: String?, val content: String)
}
