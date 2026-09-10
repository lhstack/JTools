package com.lhstack.tools.agent.coding

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.lhstack.tools.db.service.MessageStoreService
import com.lhstack.tools.llm.provider.ToolEventSink
import java.util.concurrent.ConcurrentHashMap

/**
 * 对齐 awake-claw UnifiedMessageEventRecorder。
 * 流式 delta 更新同一行事件并广播 revision；工具结果合并进 tool_call context。
 */
class UnifiedMessageEventRecorder(
    private val sessionId: Long,
    private val parentEventId: Long? = null,
    private val broadcaster: (JsonObject) -> Unit,
) {
    private val streamRounds = ConcurrentHashMap<String, Int>()
    private val pendingToolCalls = ConcurrentHashMap<String, MutableSet<String>>()

    fun streamSink(turnId: String): ToolEventSink = object : ToolEventSink {
        override fun onToolCall(call: com.lhstack.tools.llm.ProviderToolCall) {
            recordStreamEvent(turnId, "tool_call", JsonObject().apply {
                addProperty("id", call.id)
                addProperty("call_id", call.callId ?: call.id)
                addProperty("name", call.name)
                add("arguments", call.arguments)
            })
        }

        override fun onToolResult(result: com.lhstack.tools.llm.ToolResult) {
            val text = result.content.filterIsInstance<com.lhstack.tools.llm.ToolResultContent.Text>()
                .joinToString("\n") { it.text }
            recordStreamEvent(turnId, "tool_result", JsonObject().apply {
                addProperty("id", result.id)
                addProperty("call_id", result.callId ?: result.id)
                addProperty("result", text)
                addProperty("status", "completed")
            })
        }

        override fun onStreamEvent(eventType: String, data: JsonElement) {
            recordStreamEvent(turnId, eventType, data)
        }
    }

    fun recordStreamEvent(turnId: String, eventType: String, data: JsonElement) {
        val payload = data.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().apply { add("value", data) }
        val text = payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        if (eventType in setOf("reasoning_delta", "response_delta") && text.isNullOrEmpty()) return
        when (eventType) {
            "reasoning_delta" -> appendModelText(turnId, MessageEventType.MODEL_REASONING, "stream_reasoning_${streamRound(turnId)}", text.orEmpty())
            "response_delta" -> appendModelText(turnId, MessageEventType.MODEL_REPLY, "stream_reply_${streamRound(turnId)}", text.orEmpty())
            "model_round_completed" -> completeModelRound(turnId)
            "model_retry" -> {
                val error = payload.get("error")?.takeIf { it.isJsonPrimitive }?.asString ?: "模型请求失败"
                val id = MessageStoreService.upsertModelRetryEvent(sessionId, turnId, currentStreamRound(turnId), error, parentEventId)
                broadcastEventId(id)
            }
            "append_claimed" -> recordClaimedAppend(turnId, payload)
            "tool_call" -> recordToolCall(turnId, payload)
            "tool_result" -> recordToolResult(turnId, payload)
            else -> Unit
        }
    }

    fun completeTurn(turnId: String) {
        MessageStoreService.completeMessageModelEvents(sessionId, turnId, parentEventId)
        MessageStoreService.messageEventsForTurn(sessionId, turnId, true)
            .filter { it.parentEventId == parentEventId && it.eventType in setOf(MessageEventType.MODEL_REASONING, MessageEventType.MODEL_REPLY) }
            .forEach { broadcastEventId(it.id) }
        broadcastSessionUsage()
    }

    fun recordTurnUsage(turnId: String, usage: JsonElement) {
        val reply = MessageStoreService.messageEventsForTurn(sessionId, turnId, true)
            .lastOrNull { it.eventType == MessageEventType.MODEL_REPLY && it.parentEventId == parentEventId }
            ?: return
        val context = reply.context ?: JsonObject()
        context.add("usage", usage)
        MessageStoreService.upsertMessageEvent(
            NewMessageEvent(
                parentEventId = reply.parentEventId,
                sessionId = reply.sessionId,
                turnId = reply.turnId,
                status = reply.status,
                eventType = reply.eventType,
                eventId = reply.eventId,
                summary = reply.summary,
                context = context,
            ),
        )
        broadcastEventId(reply.id)
    }

    fun recordCancellation(turnId: String) {
        MessageStoreService.finishRunningModelEvents(
            sessionId,
            turnId,
            MessageEventStatus.CANCELLED,
            "cancelled",
            parentEventId,
        )
        val id = MessageStoreService.upsertMessageEvent(
            NewMessageEvent(
                parentEventId = parentEventId,
                sessionId = sessionId,
                turnId = turnId,
                status = MessageEventStatus.CANCELLED,
                eventType = MessageEventType.MODEL_REPLY_CANCELLED,
                eventId = "cancelled",
                summary = "模型回复已取消",
                context = JsonObject().apply { addProperty("outcome", "cancelled") },
            ),
        )
        broadcastEventId(id)
        broadcastSessionUsage()
    }

    fun recordFailure(turnId: String, error: String) {
        val id = MessageStoreService.upsertMessageEvent(
            NewMessageEvent(
                parentEventId = parentEventId,
                sessionId = sessionId,
                turnId = turnId,
                status = MessageEventStatus.FAILED,
                eventType = MessageEventType.TASK_FAILED,
                eventId = "failure",
                summary = "消息任务执行失败",
                context = JsonObject().apply { addProperty("error", error) },
            ),
        )
        broadcastEventId(id)
        broadcastSessionUsage()
    }

    fun broadcastEventId(id: Long) {
        val event = MessageStoreService.messageEvent(id, true) ?: return
        val payload = event.toBrowserJson().apply {
            addProperty("type", "message_event")
        }
        broadcaster(payload)
    }

    private fun recordClaimedAppend(turnId: String, data: JsonObject) {
        val itemId = data.get("id")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: throw IllegalStateException("追加队列项缺少 id")
        val content = data.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val attachments = data.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asLong } ?: emptyList()
        val eventId = MessageStoreService.createClaimedAppendEvent(
            sessionId,
            turnId,
            itemId,
            content,
            attachments,
            parentEventId,
        )
        broadcastEventId(eventId)
    }

    private fun recordToolCall(turnId: String, data: JsonObject) {
        val callId = data.get("call_id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: data.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IllegalStateException("工具调用缺少 call_id")
        pendingToolCalls.getOrPut(turnId) { mutableSetOf() }.add(callId)
        val id = MessageStoreService.upsertMessageEvent(
            NewMessageEvent(
                parentEventId = parentEventId,
                sessionId = sessionId,
                turnId = turnId,
                status = MessageEventStatus.RUNNING,
                eventType = MessageEventType.TOOL_CALL,
                eventId = callId,
                summary = data.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "tool",
                context = data.deepCopy(),
            ),
        )
        broadcastEventId(id)
    }

    private fun recordToolResult(turnId: String, data: JsonObject) {
        val callId = data.get("call_id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: data.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IllegalStateException("工具结果缺少 call_id")
        val status = when (data.get("status")?.takeIf { it.isJsonPrimitive }?.asString) {
            "failed" -> MessageEventStatus.FAILED
            "cancelled" -> MessageEventStatus.CANCELLED
            else -> MessageEventStatus.COMPLETED
        }
        val result = data.get("result") ?: JsonObject()
        val id = MessageStoreService.mergeToolCallResult(
            sessionId,
            turnId,
            callId,
            result,
            status,
            data.get("reason")?.takeIf { it.isJsonPrimitive }?.asString,
            parentEventId,
        )
        val remaining = pendingToolCalls[turnId]
        remaining?.remove(callId)
        if (remaining != null && remaining.isEmpty()) {
            pendingToolCalls.remove(turnId)
            advanceStreamRound(turnId)
        }
        broadcastEventId(id)
    }

    private fun completeModelRound(turnId: String) {
        val round = currentStreamRound(turnId)
        MessageStoreService.completeStreamModelEvent(
            sessionId, turnId, MessageEventType.MODEL_REASONING, "stream_reasoning_$round", parentEventId,
        )?.let(::broadcastEventId)
        MessageStoreService.completeStreamModelEvent(
            sessionId, turnId, MessageEventType.MODEL_REPLY, "stream_reply_$round", parentEventId,
        )?.let(::broadcastEventId)
        val shouldAdvance = pendingToolCalls[turnId].isNullOrEmpty()
        if (shouldAdvance) advanceStreamRound(turnId)
    }

    private fun appendModelText(turnId: String, eventType: MessageEventType, eventId: String, text: String) {
        if (text.isEmpty()) return
        val id = MessageStoreService.appendMessageEventText(
            sessionId, turnId, eventType, eventId, text, parentEventId,
        )
        broadcastEventId(id)
    }

    private fun streamRound(turnId: String): Int {
        streamRounds[turnId]?.let { return it }
        val persisted = MessageStoreService.streamRoundState(sessionId, turnId, parentEventId)?.round ?: 0
        streamRounds[turnId] = persisted
        return persisted
    }

    private fun currentStreamRound(turnId: String): Int {
        streamRounds[turnId]?.let { return it }
        val persisted = MessageStoreService.streamRoundState(sessionId, turnId, parentEventId)
        val round = when {
            persisted == null -> 0
            persisted.running -> persisted.round
            else -> persisted.round + 1
        }
        streamRounds[turnId] = round
        return round
    }

    private fun advanceStreamRound(turnId: String) {
        streamRounds[turnId] = streamRound(turnId) + 1
    }

    private fun broadcastSessionUsage() {
        broadcaster(JsonObject().apply {
            addProperty("type", "session_usage")
            addProperty("session_id", sessionId)
        })
    }
}
