package com.lhstack.tools.agent.coding

import com.lhstack.tools.agent.model.params.ModelParams
import com.lhstack.tools.agent.model.params.ResolvedModelConfig
import com.lhstack.tools.db.config.AgentRuntimeConfig
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.provider.HistoryTrimmer

/** History is selected only from this session's completed runs of the same scoped subagent. */
internal object SubagentHistorySupport {
    fun select(
        events: List<MessageEventRecord>,
        agentId: String,
        currentTurn: String,
        maxTurns: Int?,
    ): List<Message> {
        val runs =
            events.filter {
                it.eventType == MessageEventType.SUBAGENT &&
                    it.status == MessageEventStatus.COMPLETED &&
                    it.context?.get("agent_id")?.asString == agentId
            }
        val selected = maxTurns?.let { runs.takeLast(it) } ?: runs
        val historyEvents =
            selected.flatMap { run ->
                val context = requireNotNull(run.context)
                val task = context.get("task")?.asString ?: error("SubAgent 历史缺少 task")
                val turn = run.eventId
                val user =
                    run.copy(
                        turnId = turn,
                        eventType = MessageEventType.USER_MESSAGE,
                        context =
                            com.google.gson.JsonObject().apply { addProperty("content", task) },
                    )
                val children =
                    events
                        .filter { it.parentEventId == run.id }
                        .map { event ->
                            event.copy(
                                turnId = turn,
                                context = event.context?.deepCopy()?.apply { remove("extra") },
                            )
                        }
                if (children.any { it.eventType == MessageEventType.MODEL_REPLY })
                    listOf(user) + children
                else {
                    val output =
                        context.getAsJsonObject("result")?.get("output")?.takeUnless {
                            it.isJsonNull
                        } ?: error("SubAgent 历史缺少 output")
                    listOf(
                        user,
                        run.copy(
                            turnId = turn,
                            eventType = MessageEventType.MODEL_REPLY,
                            context =
                                com.google.gson.JsonObject().apply {
                                    addProperty(
                                        "response",
                                        if (output.isJsonPrimitive) output.asString
                                        else output.toString(),
                                    )
                                },
                        ),
                    )
                }
            }
        return MessageHistorySupport.toModelHistory(historyEvents, currentTurn, false, maxTurns)
    }

    fun prepare(
        config: AgentRuntimeConfig,
        events: List<MessageEventRecord>,
        agentId: String,
        currentTurn: String,
        preamble: String,
        prompt: String,
        model: ResolvedModelConfig,
    ): List<Message> {
        if (!config.includeHistory) return emptyList()
        return HistoryTrimmer.trim(
            history = select(events, agentId, currentTurn, config.maxHistoryMessages),
            preamble = preamble,
            prompt = prompt,
            contextWindow = model.params.contextWindow,
            maxTokens = ModelParams.runtimeOutputTokens(model),
            maxHistoryTurns = config.maxHistoryMessages,
            retentionRounds = config.toolCallRetentionRounds,
        )
    }
}
