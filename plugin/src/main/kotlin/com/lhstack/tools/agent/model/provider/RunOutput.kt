package com.lhstack.tools.agent.model.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.lhstack.tools.agent.model.llm.AssistantContent
import com.lhstack.tools.agent.model.llm.LlmSupport
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ToolCall
import com.lhstack.tools.agent.model.llm.Usage
import com.lhstack.tools.agent.model.llm.UserContent

/**
 * 模型运行输出。完全照抄 awake-claw common/output.rs 的 RunOutput + structured_output。
 *
 * output：最终回复文本；usage：token 用量；messages：完整消息列表（非流式取 reasoning）；
 * roundMessages：本轮 provider 产生的消息（assistant + tool result）；reasoning：流式推理文本。
 *
 * structuredValue 产出 model_log 的 response 结构：reasoning / response / tool_calls /
 * tool_results / usage。tool_calls/tool_results 优先取 hook 事件（events），为空时回退到
 * round_messages，与 awake 一致。
 */
class RunOutput(
    val output: String,
    val usage: Usage,
    val messages: List<Message>,
    val roundMessages: List<Message>,
    val reasoning: List<String>,
    val appendMessages: List<InjectedAppendMessage> = emptyList(),
    val cancelled: Boolean = false,
    val maxTurnsReached: Boolean = false,
) {

    /** 照抄 structured_value。 */
    fun structuredValue(events: List<TraceEvent>): JsonObject {
        val value = structuredOutput(events)
        if (cancelled || maxTurnsReached) {
            value.addProperty("cancelled", cancelled)
            value.addProperty("max_turns_reached", maxTurnsReached)
        }
        return value
    }

    /** 照抄 structured_output。 */
    private fun structuredOutput(events: List<TraceEvent>): JsonObject = JsonObject().apply {
        add("reasoning", JsonArray().apply { collectDisplayReasoning().forEach { add(it) } })
        addProperty("response", output)
        add("tool_calls", collectStructuredToolCalls(events))
        add("tool_results", collectStructuredToolResults(events))
        add("append_messages", JsonArray().apply { appendMessages.forEach { add(it.toJson()) } })
        add("provider_messages", ProviderMessageHistory.toJson(roundMessages))
        add("usage", usage.toJson())
    }

    /** 照抄 collect_structured_tool_calls：hook 优先，回退 round_messages。 */
    private fun collectStructuredToolCalls(events: List<TraceEvent>): JsonArray {
        val calls = JsonArray()
        for (event in events) {
            if (event is TraceEvent.ToolCall) {
                calls.add(JsonObject().apply {
                    addProperty("source", "hook")
                    addProperty("tool_name", event.toolName)
                    add("tool_call_id", event.toolCallId?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                    add("internal_call_id", JsonPrimitive(event.internalCallId))
                    add("args", parseJsonOrString(event.args))
                })
            }
        }
        if (calls.size() > 0) {
            return calls
        }
        for (call in collectToolCalls(roundMessages)) {
            calls.add(JsonObject().apply {
                addProperty("source", "round")
                addProperty("tool_name", call.function.name)
                add("tool_call_id", call.callId?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                add("internal_call_id", JsonPrimitive(call.id))
                add("args", call.function.arguments)
            })
        }
        return calls
    }

    /** 照抄 collect_structured_tool_results：hook 优先，回退 round_messages。 */
    private fun collectStructuredToolResults(events: List<TraceEvent>): JsonArray {
        val results = JsonArray()
        for (event in events) {
            if (event is TraceEvent.ToolResult) {
                results.add(JsonObject().apply {
                    addProperty("source", "hook")
                    add("tool_name", JsonPrimitive(event.toolName))
                    add("tool_call_id", event.toolCallId?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                    add("internal_call_id", JsonPrimitive(event.internalCallId))
                    add("args", parseJsonOrString(event.args))
                    addProperty("result", event.result)
                })
            }
        }
        if (results.size() > 0) {
            return results
        }
        for (result in collectToolResults(roundMessages)) {
            results.add(JsonObject().apply {
                addProperty("source", "round")
                add("tool_name", JsonNull.INSTANCE)
                add("tool_call_id", result.callId?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                add("internal_call_id", JsonPrimitive(result.id))
                add("args", JsonNull.INSTANCE)
                addProperty("result", result.content)
            })
        }
        return results
    }

    /** 照抄 collect_display_reasoning：流式取 reasoning，否则从 messages 抽取；去重去空。 */
    private fun collectDisplayReasoning(): List<String> {
        val source = if (reasoning.isNotEmpty()) {
            reasoning
        } else {
            collectReasoning(messages)
        }
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (text in source) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue
            if (seen.add(trimmed)) {
                result.add(trimmed)
            }
        }
        return result
    }

    companion object {
        private fun parseJsonOrString(value: String): JsonElement =
            try {
                JsonParser.parseString(value)
            } catch (_: Throwable) {
                JsonPrimitive(value)
            }

        /** 照抄 collect_tool_calls：从 assistant 消息收集 ToolCall。 */
        private fun collectToolCalls(messages: List<Message>): List<ToolCall> {
            val calls = mutableListOf<ToolCall>()
            for (message in messages) {
                if (message is Message.Assistant) {
                    for (item in message.content) {
                        if (item is AssistantContent.ToolCall) {
                            calls.add(item.toolCall)
                        }
                    }
                }
            }
            return calls
        }

        private data class DisplayToolResult(
            val id: String,
            val callId: String?,
            val content: String,
        )

        /** 照抄 collect_tool_results：从 user 消息收集 ToolResult。 */
        private fun collectToolResults(messages: List<Message>): List<DisplayToolResult> {
            val results = mutableListOf<DisplayToolResult>()
            for (message in messages) {
                if (message is Message.User) {
                    for (item in message.content) {
                        if (item is UserContent.ToolResult) {
                            val result = item.toolResult
                            results.add(
                                DisplayToolResult(
                                    id = result.id,
                                    callId = result.callId,
                                    content = LlmSupport.formatToolResultContent(result.content),
                                )
                            )
                        }
                    }
                }
            }
            return results
        }

        /** 照抄 collect_reasoning：从 assistant 消息抽 Reasoning 并格式化。 */
        private fun collectReasoning(messages: List<Message>): List<String> {
            val result = mutableListOf<String>()
            for (message in messages) {
                if (message is Message.Assistant) {
                    for (item in message.content) {
                        if (item is AssistantContent.Reasoning) {
                            val text = LlmSupport.formatReasoning(item.reasoning)
                            if (text.trim().isNotEmpty()) {
                                result.add(text)
                            }
                        }
                    }
                }
            }
            return result
        }
    }
}
