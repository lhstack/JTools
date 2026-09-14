package com.lhstack.tools.db.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.coding.MessageEventSupport
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.AgentEventEntity
import com.lhstack.tools.db.mapper.AgentEventMapper
import com.lhstack.tools.llm.AssistantContent
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ToolCall
import com.lhstack.tools.llm.ToolFunction
import com.lhstack.tools.llm.ToolResult
import com.lhstack.tools.llm.ToolResultContent
import com.lhstack.tools.llm.UserContent

/** 对齐 awake `agent_events`：Agent 自身历史，不读会话 message_events。 */
object AgentEventStoreService {

    fun recordExecution(
        agentId: Long,
        turnId: String,
        prompt: String,
        response: JsonObject,
        historyTokenRatio: Double,
    ) {
        AgentDatabase.execute { session ->
            val mapper = session.getMapper(AgentEventMapper::class.java)
            insert(
                mapper, agentId, turnId, "user_message", "user", prompt,
                JsonObject().apply {
                    addProperty("content", prompt)
                    addProperty("estimated_tokens", MessageEventSupport.estimateHistoryTokens(prompt, historyTokenRatio))
                },
            )
            val reasoning = response.get("reasoning")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asString }
                ?.joinToString("")
                .orEmpty()
            if (reasoning.isNotBlank()) {
                insert(
                    mapper, agentId, turnId, "model_reasoning", "reasoning", reasoning,
                    JsonObject().apply {
                        addProperty("text", reasoning)
                        addProperty("estimated_tokens", MessageEventSupport.estimateHistoryTokens(reasoning, historyTokenRatio))
                    },
                )
            }
            val calls = response.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            val results = response.get("tool_results")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            if (calls.size() > 0 || results.size() > 0) {
                val serialized = JsonObject().apply {
                    add("tool_calls", calls)
                    add("tool_results", results)
                }.toString()
                insert(
                    mapper, agentId, turnId, "tool_call", "tools", serialized,
                    JsonObject().apply {
                        add("tool_calls", calls)
                        add("tool_results", results)
                        addProperty("estimated_tokens", MessageEventSupport.estimateHistoryTokens(serialized, historyTokenRatio))
                    },
                )
            }
            val output = response.get("response")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (output.isNotBlank()) {
                insert(
                    mapper, agentId, turnId, "model_reply", "response", output,
                    JsonObject().apply {
                        addProperty("response", output)
                        response.get("usage")?.let { add("usage", it) }
                        addProperty("estimated_tokens", MessageEventSupport.estimateHistoryTokens(output, historyTokenRatio))
                    },
                )
            }
        }
    }

    fun recordFailure(agentId: Long, turnId: String, error: String) {
        AgentDatabase.execute { session ->
            insert(
                session.getMapper(AgentEventMapper::class.java),
                agentId,
                turnId,
                "task_failed",
                "error",
                error,
                JsonObject().apply { addProperty("error", error) },
            )
        }
    }

    fun loadHistory(agentId: Long, includeHistory: Boolean, maxTurns: Int?): List<Message> {
        if (!includeHistory) return emptyList()
        val events = AgentDatabase.execute { session ->
            session.getMapper(AgentEventMapper::class.java).selectList(
                QueryWrapper<AgentEventEntity>()
                    .eq("agent_id", agentId)
                    .orderByAsc("id"),
            )
        }
        val turnIds = linkedSetOf<String>()
        if (maxTurns != null) {
            for (event in events.asReversed()) {
                turnIds.add(event.turnId)
                if (turnIds.size >= maxTurns) break
            }
        }
        val selected = if (maxTurns == null) events else events.filter { it.turnId in turnIds }
        return eventsToMessages(selected)
    }

    private fun insert(
        mapper: AgentEventMapper,
        agentId: Long,
        turnId: String,
        eventType: String,
        eventId: String,
        summary: String,
        context: JsonObject,
    ) {
        mapper.insert(
            AgentEventEntity().apply {
                this.agentId = agentId
                this.turnId = turnId
                this.status = "completed"
                this.eventType = eventType
                this.eventId = eventId
                this.summary = MessageEventSupport.latestSummary(summary)
                this.context = context.toString()
            },
        )
    }

    private fun eventsToMessages(events: List<AgentEventEntity>): List<Message> {
        val messages = mutableListOf<Message>()
        events.forEach { event ->
            val context = runCatching { JsonParser.parseString(event.context).asJsonObject }.getOrNull() ?: return@forEach
            when (event.eventType) {
                "user_message" -> context.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                    messages += Message.user(it)
                }
                "model_reply" -> context.get("response")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let { messages += Message.assistant(it) }
                "tool_call" -> messages += toolHistoryMessages(context)
                else -> Unit
            }
        }
        return messages
    }

    private fun toolHistoryMessages(context: JsonObject): List<Message> {
        val calls = context.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val results = context.get("tool_results")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        if (calls.size() == 0 && results.size() == 0) return emptyList()
        val resultById = linkedMapOf<String, JsonObject>()
        results.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }.forEach { result ->
            toolIds(result).forEach { id -> resultById[id] = result }
        }
        val messages = mutableListOf<Message>()
        calls.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }.forEach { call ->
            val ids = toolIds(call)
            if (ids.isEmpty()) return@forEach
            val name = call.get("tool_name")?.takeIf { it.isJsonPrimitive }?.asString
                ?: call.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                ?: return@forEach
            val result = ids.firstNotNullOfOrNull { resultById[it] } ?: return@forEach
            val callId = ids.first()
            val arguments = call.get("args") ?: call.get("arguments") ?: JsonObject()
            messages += Message.Assistant(
                id = null,
                content = listOf(
                    AssistantContent.ToolCall(
                        ToolCall(
                            id = callId,
                            callId = callId,
                            function = ToolFunction(name, arguments),
                            signature = null,
                            additionalParams = null,
                        ),
                    ),
                ),
            )
            val resultText = result.get("result")?.let { item ->
                if (item.isJsonPrimitive && item.asJsonPrimitive.isString) item.asString else item.toString()
            }.orEmpty()
            messages += Message.User(
                listOf(
                    UserContent.ToolResult(
                        ToolResult(
                            id = callId,
                            callId = callId,
                            content = listOf(ToolResultContent.Text(resultText)),
                        ),
                    ),
                ),
            )
        }
        return messages
    }

    private fun toolIds(value: JsonObject): List<String> = listOf("internal_call_id", "tool_call_id", "call_id", "id")
        .mapNotNull { key -> value.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }
        .distinct()
}
