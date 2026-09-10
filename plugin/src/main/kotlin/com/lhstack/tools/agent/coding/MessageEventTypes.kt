package com.lhstack.tools.agent.coding

import com.google.gson.JsonElement
import com.google.gson.JsonObject

enum class MessageSessionKind(val value: String) {
    COMMON("common"),
    CODING("coding");

    companion object {
        fun from(value: String): MessageSessionKind = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("消息会话类型仅支持 common、coding")
    }
}

enum class MessageEventStatus(val value: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun from(value: String): MessageEventStatus = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("消息事件状态 `$value` 无效")
    }
}

enum class MessageEventType(val value: String) {
    USER_MESSAGE("user_message"),
    MODEL_REASONING("model_reasoning"),
    MODEL_REPLY("model_reply"),
    MODEL_RETRY("model_retry"),
    TOOL_CALL("tool_call"),
    MODEL_REPLY_CANCELLED("model_reply_cancelled"),
    TASK_FAILED("task_failed"),
    APPEND_MESSAGE("append_message"),
    COMPACTION_NOTICE("compaction_notice");

    companion object {
        fun from(value: String): MessageEventType = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("消息事件类型 `$value` 无效")
    }
}

enum class MessageTaskStatus(val value: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED || this == CANCELLED

    companion object {
        fun from(value: String): MessageTaskStatus = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("消息任务状态 `$value` 无效")
    }
}

enum class MessageAppendStatus(val value: String) {
    PENDING("pending"),
    CLAIMED("claimed"),
    DELIVERED("delivered");

    companion object {
        fun from(value: String): MessageAppendStatus = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("追加消息状态 `$value` 无效")
    }
}

data class NewMessageEvent(
    val parentEventId: Long? = null,
    val sessionId: Long,
    val turnId: String,
    val status: MessageEventStatus,
    val eventType: MessageEventType,
    val eventId: String,
    val summary: String,
    val context: JsonObject,
)

data class MessageEventRecord(
    val id: Long,
    val parentEventId: Long?,
    val sessionId: Long,
    val turnId: String,
    val status: MessageEventStatus,
    val eventType: MessageEventType,
    val eventId: String,
    val summary: String,
    val context: JsonObject?,
    val revision: Int,
    val createdAt: String?,
    val updatedAt: String?,
)

data class PendingMessageTask(
    val id: Long,
    val sessionId: Long,
    val turnId: String,
    val sessionType: MessageSessionKind,
    val executionConfig: JsonObject,
    val content: String,
    val attachments: List<Long>,
    val attachmentItems: List<JsonElement>,
    val resumed: Boolean,
)

data class MessageAttachmentRecord(
    val id: Long,
    val sessionId: Long,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val path: String,
    val kind: String,
    val textPreview: String?,
    val metadata: JsonObject,
    val createdAt: String?,
)

data class ClaimedAppendItem(
    val id: Long,
    val content: String,
    val attachments: List<Long>,
)

object MessageEventSupport {
    const val DEFAULT_HISTORY_TOKEN_RATIO = 2.0
    const val RESUMED_PROMPT = "继续当前未完成的回复。未完成的工具调用已因服务重启失败，请根据现有上下文继续处理。"
    const val RESTART_TOOL_REASON = "服务重启时工具调用未完成"

    fun summary(value: String): String = value.take(160)

    fun latestSummary(value: String): String = value.takeLast(160)

    fun estimateHistoryTokens(text: String, ratio: Double = DEFAULT_HISTORY_TOKEN_RATIO): Long {
        require(ratio.isFinite() && ratio > 0.0) { "全局消息历史 Token 比例必须大于 0" }
        return kotlin.math.ceil(text.length * ratio).toLong()
    }

    fun parseStreamRound(eventType: String, eventId: String): Int? {
        val prefix = when (eventType) {
            MessageEventType.MODEL_REASONING.value -> "stream_reasoning_"
            MessageEventType.MODEL_REPLY.value -> "stream_reply_"
            else -> return null
        }
        if (!eventId.startsWith(prefix)) return null
        return eventId.removePrefix(prefix).toIntOrNull()
    }
}


fun MessageEventRecord.toBrowserJson(): JsonObject = JsonObject().apply {
    addProperty("id", id)
    if (parentEventId == null) add("parent_event_id", com.google.gson.JsonNull.INSTANCE) else addProperty("parent_event_id", parentEventId)
    addProperty("session_id", sessionId)
    addProperty("turn_id", turnId)
    addProperty("status", status.value)
    addProperty("event_type", eventType.value)
    addProperty("event_id", eventId)
    addProperty("summary", summary)
    addProperty("revision", revision)
    context?.let { add("context", it) }
    createdAt?.let { addProperty("created_at", it) }
    updatedAt?.let { addProperty("updated_at", it) }
}
