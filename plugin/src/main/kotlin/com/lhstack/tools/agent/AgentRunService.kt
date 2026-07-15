package com.lhstack.tools.agent

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ProviderToolCall
import com.lhstack.tools.agent.model.llm.ToolResult
import com.lhstack.tools.agent.model.llm.ToolResultContent
import com.lhstack.tools.agent.model.log.ModelLogService
import com.lhstack.tools.agent.model.log.ModelRequestException
import com.lhstack.tools.agent.model.provider.AgentRuntime
import com.lhstack.tools.agent.model.provider.ModelStreamSink
import com.lhstack.tools.agent.model.provider.ToolEventSink
import com.lhstack.tools.concurrent.AgentExecutors
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.ResourceConfigService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class AgentRunReceiver(val value: String) {
    USER("user"), AI("ai");

    fun isAssistantMessage(): Boolean = this == USER

    fun requiresQueueDelivery(): Boolean = this == AI

    companion object {
        fun from(value: String): AgentRunReceiver = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("接收方仅支持 user、ai")
    }
}

internal data class AgentRunStartRequest(
    val agentId: Long,
    val prompt: String,
    val projectPath: String,
    val sessionId: Long,
    val receiver: AgentRunReceiver,
)

internal data class AgentRunDelivery(
    val runId: String,
    val projectPath: String,
    val sessionId: Long,
    val content: String,
)

internal data class AgentRunSnapshot(
    val runId: String,
    val agentId: Long,
    val agentName: String,
    val projectPath: String?,
    val sessionId: Long?,
    val receiver: String,
    val prompt: String,
    val status: String,
    val response: String,
    val reasoning: String,
    val tools: List<AgentBrowserTool>,
    val error: String?,
    val logId: Long?,
    val createdAt: String? = null,
)

/** Project-bound asynchronous Agent execution registry used by the CLI tool and chat panels. */
internal object AgentRunService {
    private data class RunState(
        val runId: String,
        val agentId: Long,
        val agentName: String,
        val projectPath: String,
        val sessionId: Long,
        val receiver: AgentRunReceiver,
        val prompt: String,
        val createdAt: String = java.time.LocalDateTime.now().toString().replace('T', ' '),
        val cancel: ModelCancel = ModelCancel(),
        var status: String = "running",
        var response: String = "",
        var reasoning: String = "",
        var error: String? = null,
        var logId: Long? = null,
        var deliveryDispatched: Boolean = false,
        val tools: LinkedHashMap<String, AgentToolItem> = linkedMapOf(),
    )

    private val runs = ConcurrentHashMap<String, RunState>()
    private val deletedRuns = ConcurrentHashMap.newKeySet<String>()
    private val deliveredRunIds = ConcurrentHashMap.newKeySet<String>()
    private val listeners = ConcurrentHashMap<String, MutableSet<(AgentRunSnapshot) -> Unit>>()
    private val deliveryListeners = ConcurrentHashMap<String, MutableSet<(AgentRunDelivery) -> Unit>>()
    private val pendingPublishes = ConcurrentHashMap<String, AgentRunSnapshot>()
    private val scheduledPublishes = ConcurrentHashMap.newKeySet<String>()

    fun start(request: AgentRunStartRequest): AgentRunSnapshot {
        val project = resolveProject(request.projectPath)
        val projectPath = ChatSessionService.normalizeProjectPath(project.basePath ?: error("项目没有路径"))
        val agent = AgentService.agentById(request.agentId)
            ?: throw IllegalArgumentException("Agent `${request.agentId}` 不存在")
        val session = ChatSessionService.visibleSessionById(request.sessionId, projectPath)
            ?: throw IllegalArgumentException("投递会话 `${request.sessionId}` 不存在或在目标项目不可见")
        if (request.receiver.requiresQueueDelivery()) {
            val targetAgentId = session.agentId
                ?: throw IllegalArgumentException("投递会话 `${request.sessionId}` 未绑定 Agent，无法将结果加入用户消息队列")
            val targetAgent = AgentService.agentById(targetAgentId)
                ?: throw IllegalArgumentException("投递会话 `${request.sessionId}` 绑定的 Agent 不存在")
            require(targetAgent.enabled) { "投递会话 `${request.sessionId}` 绑定的 Agent 已停用" }
        }
        val run = RunState(UUID.randomUUID().toString(), request.agentId, agent.name, projectPath, session.id, request.receiver, request.prompt)
        runs[run.runId] = run
        publish(run)
        AgentExecutors.shared.submit { execute(project, request, run) }
        return snapshot(run)
    }

    fun get(runId: String): AgentRunSnapshot {
        runs[runId]?.let { return snapshot(it) }
        return historyByRunId(runId)
            ?: throw IllegalArgumentException("Agent 运行记录 `$runId` 不存在")
    }

    fun list(sessionId: Long): List<AgentRunSnapshot> = runs.values
        .filter { it.sessionId == sessionId }
        .sortedBy { it.runId }
        .map(::snapshot)


    private fun historyByRunId(runId: String): AgentRunSnapshot? {
        val log = ModelLogService.agentRunLogByRunId(runId) ?: return null
        val request = log.requestData.getAsJsonObject("request_snapshot")
        val structured = log.responseData.getAsJsonObject("structured_response")
        return AgentRunSnapshot(
            runId = runId,
            agentId = log.agentId ?: 0,
            agentName = AgentService.agentById(log.agentId ?: 0)?.name ?: "Agent",
            projectPath = request?.get("agent_run_project")?.asString,
            sessionId = request?.get("agent_run_session_id")?.asLong,
            receiver = request?.get("agent_run_receiver")?.asString ?: "user",
            prompt = request?.get("prompt_message")?.asString.orEmpty(),
            status = log.status,
            response = structured?.get("response")?.asString.orEmpty(),
            reasoning = structured?.getAsJsonArray("reasoning")?.joinToString("\n\n") { it.asString }.orEmpty(),
            tools = historyTools(structured),
            error = log.errorData,
            logId = log.id,
            createdAt = log.createdAt,
        )
    }

    fun history(sessionId: Long): List<AgentRunSnapshot> {
        val session = ChatSessionService.sessionById(sessionId) ?: return emptyList()
        val sourceId = ChatSessionService.sessionSourceId(session.agentId, session.id)
        return ModelLogService.listAgentRunLogs(sourceId).map { log ->
            val request = log.requestData.getAsJsonObject("request_snapshot")
            val structured = log.responseData.getAsJsonObject("structured_response")
            AgentRunSnapshot(
                runId = request?.get("agent_run_id")?.asString ?: "log-${log.id}",
                agentId = log.agentId ?: 0,
                agentName = AgentService.agentById(log.agentId ?: 0)?.name ?: "Agent",
                projectPath = request?.get("agent_run_project")?.asString,
                sessionId = sessionId,
                receiver = request?.get("agent_run_receiver")?.asString ?: "user",
                prompt = request?.get("prompt_message")?.asString.orEmpty(),
                status = log.status,
                response = structured?.get("response")?.asString.orEmpty(),
                reasoning = structured?.getAsJsonArray("reasoning")?.joinToString("\n\n") { it.asString }.orEmpty(),
                tools = historyTools(structured),
                error = log.errorData,
                logId = log.id,
                createdAt = log.createdAt,
            )
        }
    }

    fun delete(runId: String, logId: Long?) {
        pendingPublishes.remove(runId)
        val running = runs.remove(runId)
        if (running != null) {
            deletedRuns.add(runId)
            running.cancel.cancel()
        }
        val persistedLogId = logId ?: ModelLogService.agentRunLogByRunId(runId)?.id
        persistedLogId?.let(ModelLogService::deleteModelLog)
    }

    fun subscribe(sessionId: Long, listener: (AgentRunSnapshot) -> Unit): AutoCloseable {
        val values = listeners.computeIfAbsent(sessionId.toString()) { ConcurrentHashMap.newKeySet() }
        values.add(listener)
        return AutoCloseable {
            values.remove(listener)
            if (values.isEmpty()) listeners.remove(sessionId.toString(), values)
        }
    }

    fun subscribeDeliveries(projectPath: String, listener: (AgentRunDelivery) -> Unit): AutoCloseable {
        val key = ChatSessionService.normalizeProjectPath(projectPath)
        val values = deliveryListeners.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }
        values.add(listener)
        runs.values
            .filter { run -> run.projectPath == key && run.status == "completed" &&
                run.receiver.requiresQueueDelivery() && !run.deliveryDispatched && run.response.isNotBlank()
            }
            .forEach(::dispatchDelivery)
        dispatchPersistedDeliveries(key)
        return AutoCloseable {
            values.remove(listener)
            if (values.isEmpty()) deliveryListeners.remove(key, values)
        }
    }

    fun markDeliveryAccepted(runId: String) {
        deliveredRunIds.remove(runId)
    }

    private fun execute(project: Project, request: AgentRunStartRequest, run: RunState) {
        try {
            val session = ChatSessionService.sessionById(run.sessionId)
                ?: throw IllegalStateException("投递会话 `${run.sessionId}` 已不存在")
            val history = buildHistory(session)
            val result = AgentRuntime.execute(
                AgentRuntime.Request(
                    agentId = request.agentId,
                    prompt = request.prompt,
                    triggerType = "agent_run",
                    triggerId = run.runId,
                    workspace = run.projectPath,
                    sessionId = run.sessionId,
                    skillsRootDir = ResourceConfigService.skillsRootDir(),
                    history = history,
                    streamSink = streamSink(run),
                    eventSink = eventSink(run),
                    cancel = run.cancel,
                    toolCancel = run.cancel,
                    logSourceType = ChatSessionService.SESSION_SOURCE_TYPE,
                    logSourceId = ChatSessionService.sessionSourceId(session.agentId, run.sessionId),
                    logMessageType = "agent_run",
                    logPromptMessage = request.prompt,
                    project = project,
                    requestMetadata = JsonObject().apply {
                        addProperty("agent_run_id", run.runId)
                        addProperty("agent_run_receiver", run.receiver.value)
                        addProperty("agent_run_project", run.projectPath)
                        addProperty("agent_run_session_id", run.sessionId)
                    },
                )
            )
            synchronized(run) {
                run.response = result.output.ifBlank { run.response }
                run.logId = result.logId
                run.status = "completed"
            }
        } catch (error: Throwable) {
            synchronized(run) {
                run.status = if (run.cancel.isCancelled()) "cancelled" else "failed"
                run.error = error.message ?: error.toString()
                run.logId = (error as? ModelRequestException)?.logId
            }
        }
        if (deletedRuns.remove(run.runId)) {
            run.logId?.let(ModelLogService::deleteModelLog)
            runs.remove(run.runId)
            return
        }
        publish(run)
        dispatchDelivery(run)
        runs.remove(run.runId, run)
    }

    private fun dispatchDelivery(run: RunState) {
        val listener = deliveryListeners[run.projectPath]?.firstOrNull() ?: return
        val delivery = synchronized(run) {
            if (run.status != "completed" || !run.receiver.requiresQueueDelivery() ||
                run.response.isBlank() || run.deliveryDispatched ||
                ModelLogService.hasAgentRunDelivery(run.runId)
            ) {
                return
            }
            run.deliveryDispatched = true
            deliveredRunIds.add(run.runId)
            AgentRunDelivery(run.runId, run.projectPath, run.sessionId, run.response)
        }
        ApplicationManager.getApplication().invokeLater { listener(delivery) }
    }

    private fun dispatchPersistedDeliveries(projectPath: String) {
        val listener = deliveryListeners[projectPath]?.firstOrNull() ?: return
        ChatSessionService.listVisibleSessions(projectPath)
            .asSequence()
            .flatMap { session -> history(session.id).asSequence() }
            .filter { run -> run.status == "completed" && AgentRunReceiver.from(run.receiver).requiresQueueDelivery() }
            .filter { run -> run.projectPath?.let(ChatSessionService::normalizeProjectPath) == projectPath }
            .filter { run -> run.sessionId != null && run.response.isNotBlank() }
            .filterNot { run -> ModelLogService.hasAgentRunDelivery(run.runId) }
            .filter { run -> deliveredRunIds.add(run.runId) }
            .forEach { run ->
                val delivery = AgentRunDelivery(run.runId, projectPath, requireNotNull(run.sessionId), run.response)
                ApplicationManager.getApplication().invokeLater { listener(delivery) }
            }
    }

    private fun streamSink(run: RunState) = object : ModelStreamSink {
        override fun onResponseDelta(text: String) {
            synchronized(run) { run.response += text }
            if (run.runId !in deletedRuns) publish(run)
        }

        override fun onReasoningDelta(text: String) {
            synchronized(run) { run.reasoning += text }
            if (run.runId !in deletedRuns) publish(run)
        }
    }

    private fun eventSink(run: RunState) = object : ToolEventSink {
        override fun onToolCall(call: ProviderToolCall) {
            synchronized(run) { run.tools[call.id] = AgentToolItem(call.id, call.name, call.argsString()) }
            if (run.runId !in deletedRuns) publish(run)
        }

        override fun onToolResult(result: ToolResult) {
            val text = result.content.filterIsInstance<ToolResultContent.Text>().joinToString("\n") { it.text }
            synchronized(run) {
                run.tools[result.id]?.apply {
                    this.result = text
                    finished = true
                    failed = text.startsWith("工具调用失败:") || text == "用户手动取消"
                }
            }
            if (run.runId !in deletedRuns) publish(run)
        }
    }

    private fun historyTools(structured: JsonObject?): List<AgentBrowserTool> {
        if (structured == null) return emptyList()
        val results = structured.getAsJsonArray("tool_results")?.associate { value ->
            val item = value.asJsonObject
            item.get("internal_call_id")?.asString.orEmpty() to item.get("result")?.asString.orEmpty()
        }.orEmpty()
        return structured.getAsJsonArray("tool_calls")?.mapIndexed { index, value ->
            val item = value.asJsonObject
            val id = item.get("internal_call_id")?.asString ?: "tool-$index"
            val result = results[id]
            AgentBrowserTool(
                id = id,
                name = item.get("tool_name")?.asString.orEmpty(),
                finished = result != null,
                failed = result?.startsWith("工具调用失败:") == true,
            )
        }.orEmpty()
    }

    fun toolDetail(runId: String, callId: String): AgentBrowserToolDetail? {
        runs[runId]?.let { run ->
            return synchronized(run) {
                run.tools[callId]?.let { AgentBrowserToolDetail(it.args, it.result) }
            }
        }
        val structured = ModelLogService.agentRunLogByRunId(runId)
            ?.responseData
            ?.getAsJsonObject("structured_response")
            ?: return null
        return persistedToolDetail(structured, callId)
    }

    private fun persistedToolDetail(structured: JsonObject, callId: String): AgentBrowserToolDetail? {
        val call = structured.getAsJsonArray("tool_calls")
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            ?.firstOrNull { it.get("internal_call_id")?.asString == callId }
            ?: return null
        val result = structured.getAsJsonArray("tool_results")
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            ?.firstOrNull { it.get("internal_call_id")?.asString == callId }
        val args = call.get("args")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()
        return AgentBrowserToolDetail(args, result?.get("result")?.asString.orEmpty())
    }

    private fun buildHistory(session: com.lhstack.tools.db.service.ChatSessionRecord): List<Message> =
        ModelLogService.listChatTurnsForSession(session.id)
            .filter { it.status == "completed" || it.status == "cancelled" }
            .flatMap { turn ->
                val request = turn.requestData.getAsJsonObject("request_snapshot")
                val structured = turn.responseData.getAsJsonObject("structured_response")
                val response = structured?.get("response")?.asString.orEmpty()
                if (turn.messageType == "agent_run") {
                    return@flatMap if (request?.get("agent_run_receiver")?.asString?.let(AgentRunReceiver::from)?.isAssistantMessage() == true && response.isNotBlank()) {
                        listOf(Message.assistant(response))
                    } else {
                        emptyList()
                    }
                }
                val prompt = request?.get("prompt_message")?.asString.orEmpty()
                buildList {
                    if (prompt.isNotBlank()) add(Message.user(prompt))
                    if (response.isNotBlank()) add(Message.assistant(response))
                }
            }

    private fun resolveProject(projectPath: String): Project {
        val normalized = ChatSessionService.normalizeProjectPath(projectPath)
        return ProjectManager.getInstance().openProjects.firstOrNull {
            it.basePath?.let(ChatSessionService::normalizeProjectPath) == normalized
        } ?: throw IllegalArgumentException("项目 `$projectPath` 当前未打开")
    }

    private fun publish(run: RunState) {
        pendingPublishes[run.runId] = snapshot(run)
        schedulePublish(run.runId)
    }

    private fun schedulePublish(runId: String) {
        if (!scheduledPublishes.add(runId)) return
        ApplicationManager.getApplication().invokeLater {
            val value = pendingPublishes.remove(runId)
            if (value != null && runId !in deletedRuns) {
                listeners[value.sessionId.toString()]?.toList()?.forEach { listener -> listener(value) }
            }
            scheduledPublishes.remove(runId)
            if (pendingPublishes.containsKey(runId)) schedulePublish(runId)
        }
    }

    private fun snapshot(run: RunState): AgentRunSnapshot = synchronized(run) {
        AgentRunSnapshot(
            run.runId, run.agentId, run.agentName, run.projectPath, run.sessionId, run.receiver.value, run.prompt, run.status,
            run.response, run.reasoning,
            run.tools.values.map { AgentBrowserTool(it.id, it.name, it.finished, it.failed) },
            run.error, run.logId, run.createdAt,
        )
    }
}
