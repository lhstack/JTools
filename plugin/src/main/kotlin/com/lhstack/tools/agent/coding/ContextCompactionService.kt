package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.log.ModelLogContext
import com.lhstack.tools.agent.model.params.ModelResolver
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.CodingEnvironmentService
import com.lhstack.tools.db.service.MessageStoreService
import com.lhstack.tools.db.service.SettingService
import com.lhstack.tools.llm.Message
import com.lhstack.tools.concurrent.AgentExecutors
import com.lhstack.tools.llm.provider.ModelRuntime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * 对齐 awake-claw compact_context：手动压缩当前会话水位后的事件。
 * 压缩 Agent 来自全局 Setting `coding.compaction_agent_id`，未配置直接失败。
 */
object ContextCompactionService {
    const val SETTING_KEY = "coding.compaction_agent_id"
    private val running = ConcurrentHashMap.newKeySet<Long>()

    fun compactionAgentId(): Long? =
        SettingService.setting(SETTING_KEY)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()?.takeIf { it > 0 }

    fun contextStatus(sessionId: Long): JsonObject {
        val status = MessageStoreService.messageContextStatus(sessionId)
        val session = ChatSessionService.sessionById(sessionId)
        val environmentAgent = session?.codingEnvironmentId?.let(CodingEnvironmentService::environmentById)
            ?.let { CodingEnvironmentService.compactionAgentId(it.config) }
        status.addProperty("compaction_agent_configured", environmentAgent != null || compactionAgentId() != null)
        return status
    }

    fun compact(sessionId: Long): JsonObject = start(sessionId)

    fun start(sessionId: Long): JsonObject {
        val session = ChatSessionService.sessionById(sessionId)
            ?: throw IllegalArgumentException("Coding 会话 `$sessionId` 不存在")
        val environment = CodingEnvironmentService.requireEnabled(session.codingEnvironmentId)
        val agentId = CodingEnvironmentService.compactionAgentId(environment.config) ?: compactionAgentId()
            ?: throw IllegalStateException("当前编码环境和全局设置都未配置压缩 Agent")
        require(!MessageStoreService.hasActiveMessageTasks(sessionId)) { "会话正在处理消息，无法压缩" }
        check(running.add(sessionId)) { "当前 Coding 会话正在压缩上下文" }
        try {
            val events = MessageStoreService.codingCompactionEvents(sessionId)
            require(events.isNotEmpty()) { "当前 Coding 会话没有可压缩的消息事件" }
            val estimatedBefore = events.sumOf { event ->
                event.context?.get("estimated_tokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            }
            val compactionId = MessageStoreService.createContextCompaction(sessionId, agentId, estimatedBefore)
            val prompt = CompactionTranscriptSupport.prompt(events)
            MessageProcessor.notifySessionUsage(sessionId)
            AgentExecutors.shared.submit {
                runCatching { runCompaction(session.cwd, sessionId, compactionId, agentId, events, prompt) }
                    .onFailure { error ->
                        val noticeId = runCatching {
                            MessageStoreService.failContextCompaction(compactionId, error.message ?: error.toString())
                        }.getOrDefault(0L)
                        if (noticeId > 0) MessageProcessor.notifyMessageEvent(noticeId)
                    }
                running.remove(sessionId)
                MessageProcessor.notifySessionUsage(sessionId)
            }
            return contextStatus(sessionId)
        } catch (error: Throwable) {
            running.remove(sessionId)
            throw error
        }
    }

    private fun runCompaction(
        cwd: String,
        sessionId: Long,
        compactionId: Long,
        agentId: Long,
        events: List<MessageEventRecord>,
        prompt: String,
    ): JsonObject {
        val output = executeCompactionAgent(agentId, cwd, compactionId, prompt)
        val summary = CompactionTranscriptSupport.persistSummary(output.response, events)
        require(summary.isNotBlank()) { "上下文压缩 Agent 未返回摘要" }
        val estimatedAfter = ceil(summary.length / 2.0).toLong()
        val lastEventId = events.last().id
        val noticeId = MessageStoreService.completeContextCompaction(
            compactionId = compactionId,
            sessionId = sessionId,
            compactedThroughEventId = lastEventId,
            summary = summary,
            estimatedTokensAfter = estimatedAfter,
            modelLogId = output.modelLogId,
            usage = output.usage,
        )
        MessageProcessor.notifyMessageEvent(noticeId)
        return JsonObject().apply {
            addProperty("compaction_id", compactionId)
            addProperty("notice_id", noticeId)
            addProperty("summary", summary)
            add("context", contextStatus(sessionId))
        }
    }

    private data class CompactionOutput(val response: String, val modelLogId: Long, val usage: com.google.gson.JsonElement?)

    private fun executeCompactionAgent(agentId: Long, cwd: String, compactionId: Long, prompt: String): CompactionOutput {
        val agent = AgentService.agentById(agentId)
            ?: throw IllegalArgumentException("上下文压缩 Agent `$agentId` 不存在")
        require(agent.enabled) { "上下文压缩 Agent `$agentId` 已停用" }
        val providerId = agent.providerId ?: throw IllegalStateException("上下文压缩 Agent `$agentId` 未绑定供应商")
        val modelId = agent.modelId ?: throw IllegalStateException("上下文压缩 Agent `$agentId` 未绑定模型")
        val provider = CatalogService.providerById(providerId)
            ?: throw IllegalStateException("供应商 `$providerId` 不存在")
        val modelEntity = CatalogService.modelById(modelId)
            ?: throw IllegalStateException("模型 `$modelId` 不存在")
        val model = ModelResolver.resolveFromStore(provider, modelEntity)
        val result = ModelRuntime.execute(
            model = model,
            agentMaxTurns = 1,
            preamble = agent.extraPrompt.orEmpty(),
            promptMessage = Message.user(prompt),
            history = emptyList(),
            tools = emptyList(),
            logContext = ModelLogContext(
                sourceType = "message",
                sourceId = compactionId.toString(),
                agentId = agentId,
                messageType = "coding_context_compaction",
                requestSnapshot = JsonObject().apply {
                    addProperty("cwd", cwd)
                    addProperty("compaction_id", compactionId)
                },
            ),
            environmentId = null,
            streamed = false,
        )
        val response = result.value.get("response")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        return CompactionOutput(response, result.modelLogId, result.value.get("usage"))
    }
}
