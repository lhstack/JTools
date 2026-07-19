package com.lhstack.tools.agent.model.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Defines persisted assistant output: text, reasoning, or a complete tool call/result pair. */
internal object AssistantOutputPolicy {
    fun hasOutput(value: JsonObject): Boolean {
        val structured = value.get("structured_response")
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: value
        if (stringValue(structured.get("response")).isNotBlank()) return true
        if (structured.get("reasoning")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                ?.any { stringValue(it).isNotBlank() } == true
        ) return true
        return hasCompleteToolExchange(structured)
    }

    fun hasCompleteToolExchange(structured: JsonObject): Boolean {
        val resultIds = structured.get("tool_results")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.mapNotNull(::toolRecordId)
            ?.toSet()
            .orEmpty()
        if (resultIds.isEmpty()) return false
        return structured.get("tool_calls")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.any { toolRecordId(it) in resultIds } == true
    }

    private fun toolRecordId(value: JsonElement): String? {
        val record = value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        return stringValue(record.get("internal_call_id"))
            .ifBlank { stringValue(record.get("tool_call_id")) }
            .takeIf(String::isNotBlank)
    }

    private fun stringValue(value: JsonElement?): String =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
}
