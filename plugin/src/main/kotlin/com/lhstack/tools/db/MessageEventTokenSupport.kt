package com.lhstack.tools.db

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.sql.Connection
import kotlin.math.ceil

/**
 * message_events 的上下文 Token 估算。
 * 估算对象必须是实际投影给模型的内容，不能直接序列化整个事件 JSON。
 */
internal object MessageEventTokenSupport {
    private const val MAX_TOOL_RESULT_CHARS = 16_384
    private const val DEFAULT_CONFIGURED_RATIO = 2.0

    fun reestimate(context: JsonObject, eventType: String, ratio: Double): JsonObject {
        require(ratio.isFinite() && ratio > 0.0) { "全局消息历史 Token 比例必须大于 0" }
        val normalized = context.deepCopy()
        normalized.remove("estimated_tokens")
        if (eventType == "model_retry" || eventType == "task_failed") return normalized
        normalized.addProperty("estimated_tokens", estimate(projectedText(eventType, normalized), ratio))
        return normalized
    }

    fun projectedText(eventType: String, context: JsonObject): String {
        val payload = context.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: context
        return when (eventType) {
            "user_message", "append_message" -> historicalUserText(payload)
            "model_reply", "subagent" -> buildString {
                if (context.has("message")) append(nestedToolText(payload))
                append(firstText(payload, "response", "text", "content"))
            }
            "tool_call" -> toolText(context)
            else -> ""
        }
    }

    /** 将已有数据库中的旧估算值重算为当前投影规则。 */
    fun recalculateExistingEvents(connection: Connection, ratio: Double): Int {
        require(ratio.isFinite() && ratio > 0.0) { "全局消息历史 Token 比例必须大于 0" }
        val events = connection.createStatement().use { query ->
            query.executeQuery("select id, event_type, context from message_events order by id").use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            ExistingEvent(
                                id = rows.getLong("id"),
                                eventType = rows.getString("event_type"),
                                rawContext = rows.getString("context"),
                            ),
                        )
                    }
                }
            }
        }
        var changed = 0
        connection.prepareStatement("update message_events set context=? where id=?").use { update ->
            events.forEach { event ->
                val context = JsonParser.parseString(event.rawContext).asJsonObject
                val normalized = reestimate(context, event.eventType, ratio).toString()
                if (normalized == event.rawContext) return@forEach
                update.setString(1, normalized)
                update.setLong(2, event.id)
                changed += update.executeUpdate()
            }
        }
        return changed
    }

    private data class ExistingEvent(
        val id: Long,
        val eventType: String,
        val rawContext: String,
    )

    fun configuredRatio(connection: Connection): Double {
        val raw = connection.prepareStatement(
            "select value from global_config where key='message.history_token_ratio' limit 1",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) else null
            }
        }
        if (raw.isNullOrBlank()) return DEFAULT_CONFIGURED_RATIO
        val ratio = raw.toDoubleOrNull() ?: throw IllegalStateException("全局消息历史 Token 比例必须是正数")
        require(ratio.isFinite() && ratio > 0.0) { "全局消息历史 Token 比例必须大于 0" }
        return ratio
    }

    private fun historicalUserText(payload: JsonObject): String {
        val attachments = payload.get("attachment_items")?.takeIf { it.isJsonArray }?.asJsonArray
        if (attachments == null || attachments.size() == 0) {
            return stringValue(payload, "content")
        }
        return buildString {
            append(stringValue(payload, "content"))
            attachments.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val item = element.asJsonObject
                append("[Attachment]\n")
                append("file_name: ").append(stringValue(item, "file_name")).append('\n')
                append("content_type: ").append(stringValue(item, "content_type")).append('\n')
                append("size: ").append(longValue(item, "size")).append(" bytes\n")
                append("kind: ").append(stringValue(item, "kind")).append('\n')
                append("path: ").append(stringValue(item, "path")).append('\n')
                append("uploaded_at: ")
                    .append(stringValue(item, "created_at").ifBlank { stringValue(item, "uploaded_at") })
                    .append('\n')
            }
        }
    }

    private fun toolText(context: JsonObject): String {
        val name = stringValue(context, "name").ifBlank { stringValue(context, "tool_name") }.ifBlank { "tool" }
        val arguments = context.get("arguments") ?: context.get("args")
        val argumentsText = arguments?.toString() ?: "{}"
        val result = context.get("result")?.let(::jsonText).orEmpty()
        return name + argumentsText + truncate(result)
    }

    private fun nestedToolText(message: JsonObject): String {
        val calls = message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        val results = message.get("tool_results")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        val resultById = mutableMapOf<String, JsonObject>()
        results.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val result = element.asJsonObject
            historyIds(result).forEach { resultById[it] = result }
        }
        return buildString {
            calls.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val call = element.asJsonObject
                val result = historyIds(call).firstNotNullOfOrNull(resultById::get) ?: return@forEach
                val name = stringValue(call, "tool_name").ifBlank { stringValue(call, "name") }.ifBlank { return@forEach }
                append(name)
                append((call.get("args") ?: call.get("arguments"))?.toString() ?: "{}")
                append(truncate(result.get("result")?.let(::jsonText).orEmpty()))
            }
        }
    }

    private fun historyIds(value: JsonObject): List<String> = listOf("tool_call_id", "internal_call_id")
        .mapNotNull { key -> value.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
        .filter { it.isNotBlank() }

    private fun firstText(value: JsonObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            value.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
        }.orEmpty()

    private fun stringValue(value: JsonObject, key: String): String =
        value.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun longValue(value: JsonObject, key: String): Long =
        value.get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private fun jsonText(value: com.google.gson.JsonElement): String =
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()

    private fun truncate(value: String): String {
        if (value.length <= MAX_TOOL_RESULT_CHARS) return value
        return value.take(MAX_TOOL_RESULT_CHARS) + "\n...[truncated to $MAX_TOOL_RESULT_CHARS characters]"
    }

    private fun estimate(text: String, ratio: Double): Long = ceil(text.length * ratio).toLong()
}
