package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import com.lhstack.tools.llm.AssistantContent
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ToolCall
import com.lhstack.tools.llm.ToolResult
import com.lhstack.tools.llm.ToolResultContent
import com.lhstack.tools.llm.UserContent

/**
 * 对齐 awake-claw message_events_to_model_history 的编码会话子集。
 * 业务历史只从 message_events 重建，不读 model_request_logs。
 */
object MessageHistorySupport {
    fun toModelHistory(
        events: List<MessageEventRecord>,
        currentTurnId: String,
        includeCurrentTurn: Boolean,
        maxHistoryRounds: Int? = null,
    ): List<Message> {
        val currentTurnEvents = mutableListOf<MessageEventRecord>()
        val turns = mutableListOf<Pair<String, MutableList<MessageEventRecord>>>()
        events.forEach { event ->
            if (event.turnId == currentTurnId) {
                if (includeCurrentTurn && isContextual(event)) currentTurnEvents += event
                return@forEach
            }
            if (!isContextual(event)) return@forEach
            val existing = turns.firstOrNull { it.first == event.turnId }
            if (existing != null) existing.second += event else turns += event.turnId to mutableListOf(event)
        }
        val start = maxHistoryRounds?.takeIf { it > 0 }?.let { (turns.size - it).coerceAtLeast(0) } ?: 0
        val selected = turns.drop(start).flatMap { it.second } + currentTurnEvents
        return eventsToMessages(selected.sortedBy { it.id })
    }

    private fun isContextual(event: MessageEventRecord): Boolean {
        if (!isAllowed(event)) return false
        return when (event.eventType) {
            MessageEventType.MODEL_REPLY -> hasNonEmptyModelText(event)
            MessageEventType.TOOL_CALL -> event.status in setOf(
                MessageEventStatus.COMPLETED,
                MessageEventStatus.FAILED,
                MessageEventStatus.CANCELLED,
            )
            MessageEventType.USER_MESSAGE, MessageEventType.APPEND_MESSAGE -> event.status in setOf(
                MessageEventStatus.COMPLETED,
                MessageEventStatus.FAILED,
                MessageEventStatus.CANCELLED,
            )
            else -> false
        }
    }

    private fun isAllowed(event: MessageEventRecord): Boolean = event.eventType in setOf(
        MessageEventType.USER_MESSAGE,
        MessageEventType.APPEND_MESSAGE,
        MessageEventType.TOOL_CALL,
        MessageEventType.MODEL_REPLY,
    ) && event.context?.get("extra")?.takeIf { it.isJsonObject }?.asJsonObject?.get("type")
        ?.takeIf { it.isJsonPrimitive }?.asString != "subagent"

    private fun hasNonEmptyModelText(event: MessageEventRecord): Boolean {
        val context = event.context ?: return false
        return listOf("response", "text", "reasoning", "reasoning_content").any { key ->
            context.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank() == true
        }
    }

    private fun eventsToMessages(events: List<MessageEventRecord>): List<Message> {
        val messages = mutableListOf<Message>()
        events.forEach { event ->
            val context = event.context ?: return@forEach
            when (event.eventType) {
                MessageEventType.USER_MESSAGE, MessageEventType.APPEND_MESSAGE -> {
                    messages += CodingAttachmentPrompt.historicalUserMessageFromEvent(context)
                }
                MessageEventType.MODEL_REPLY -> {
                    val text = context.get("response")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: context.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: ""
                    if (text.isNotBlank()) messages += Message.assistant(text)
                }
                MessageEventType.TOOL_CALL -> {
                    val callId = context.get("call_id")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: context.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: event.eventId
                    val name = context.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "tool"
                    val arguments = context.get("arguments") ?: JsonObject()
                    messages += Message.Assistant(
                        id = null,
                        content = listOf(
                            AssistantContent.ToolCall(
                                ToolCall(
                                    id = callId,
                                    callId = callId,
                                    function = com.lhstack.tools.llm.ToolFunction(name, arguments),
                                    signature = null,
                                    additionalParams = null,
                                ),
                            ),
                        ),
                    )
                    context.get("result")?.let { result ->
                        val text = if (result.isJsonPrimitive && result.asJsonPrimitive.isString) result.asString else result.toString()
                        messages += Message.User(
                            listOf(
                                UserContent.ToolResult(
                                    ToolResult(
                                        id = event.eventId,
                                        callId = event.eventId,
                                        content = listOf(ToolResultContent.Text(text)),
                                    ),
                                ),
                            ),
                        )
                    }
                }
                else -> Unit
            }
        }
        return messages
    }
}
