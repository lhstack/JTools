package com.lhstack.tools.agent.coding

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.http.ModelRequestCancelledException
import com.lhstack.tools.agent.model.log.ModelLogContext
import com.lhstack.tools.agent.model.log.ModelRequestException
import com.lhstack.tools.agent.model.params.ModelResolver
import com.lhstack.tools.concurrent.AgentExecutors
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.CodingEnvironmentService
import com.lhstack.tools.db.service.MessageStoreService
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.provider.ModelRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

enum class MessageTaskCancelTarget { TOOL_BATCH, MODEL }

private data class ActiveMessageTask(
    val sessionId: Long,
    val model: ModelCancel,
    val toolSlot: java.util.concurrent.atomic.AtomicReference<ModelCancel>,
    val modelCancelRequested: AtomicBoolean,
)

/**
 * 对齐 awake-claw MessageProcessor 的 coding 路径。
 * 单会话串行，processing 任务启动时 resume，claimed 追加先释放。
 */
object MessageProcessor {
    private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()
    private val activeTasks = ConcurrentHashMap<Long, ActiveMessageTask>()
    private val scheduledTasks = ConcurrentHashMap.newKeySet<Long>()
    private val started = AtomicBoolean(false)

    fun addListener(listener: (JsonObject) -> Unit) {
        listeners += listener
    }

    fun notifySessionUsage(sessionId: Long) {
        broadcast(JsonObject().apply {
            addProperty("type", "session_usage")
            addProperty("session_id", sessionId)
        })
    }

    fun notifyMessageEvent(eventId: Long) {
        if (eventId <= 0) return
        val event = MessageStoreService.messageEvent(eventId, true)?.toBrowserJson() ?: return
        event.addProperty("type", "message_event")
        broadcast(event)
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        recover()
        scan()
    }

    fun recover() {
        MessageStoreService.recoverMessageTasks()
        scan()
    }

    fun triggerScan() = scan()

    fun enqueue(
        sessionId: Long,
        content: String,
        attachments: List<Long> = emptyList(),
        promptId: Long? = null,
    ): com.google.gson.JsonObject {
        val session = ChatSessionService.sessionById(sessionId)
            ?: throw IllegalArgumentException("消息会话 `$sessionId` 不存在")
        require(MessageStoreService.runningContextCompactionId(sessionId) == null) {
            "上下文正在压缩，请等待压缩结果"
        }
        val config = JsonObject().apply {
            addProperty("agent_id", session.agentId)
            addProperty("cwd", session.cwd)
            promptId?.let { addProperty("prompt_id", it) } ?: session.promptId?.let { addProperty("prompt_id", it) }
        }
        val created = MessageStoreService.createMessageTask(
            sessionId = sessionId,
            turnId = java.util.UUID.randomUUID().toString(),
            content = content,
            attachments = attachments,
            executionConfig = config,
        )
        val task = MessageStoreService.sessionTasks(sessionId).first { it.id == created.taskId }
        val json = MessageStoreService.sessionTaskJson(task).apply { addProperty("type", "message_task") }
        broadcast(json.deepCopy())
        scan()
        return json
    }

    data class AppendDelivery(
        val delivery: String,
        val turnId: String,
        val taskId: Long? = null,
        val appendId: Long? = null,
    )

    fun enqueueAppend(sessionId: Long, turnId: String, content: String, attachments: List<Long> = emptyList()): AppendDelivery {
        require(MessageStoreService.runningContextCompactionId(sessionId) == null) {
            "上下文正在压缩，请等待压缩结果"
        }
        val processingTurn = MessageStoreService.processingTurnId(sessionId)
        if (processingTurn == turnId) {
            val id = MessageStoreService.enqueueAppendItem(sessionId, turnId, content, attachments)
            broadcast(JsonObject().apply {
                addProperty("type", "message_task")
                addProperty("session_id", sessionId)
                addProperty("turn_id", turnId)
                addProperty("status", "append_queued")
            })
            return AppendDelivery(delivery = "append", turnId = turnId, appendId = id)
        }
        val created = enqueue(sessionId, content, attachments)
        return AppendDelivery(
            delivery = "task",
            turnId = created.get("turn_id").asString,
            taskId = created.get("id").asLong,
        )
    }

    fun cancelPending(sessionId: Long, taskId: Long): Boolean {
        val deleted = MessageStoreService.cancelPendingTask(sessionId, taskId)
        if (deleted) {
            broadcast(JsonObject().apply {
                addProperty("type", "message_task")
                addProperty("id", taskId)
                addProperty("session_id", sessionId)
                addProperty("status", "cancelled")
                addProperty("deleted", true)
            })
        }
        return deleted
    }

    fun updatePending(sessionId: Long, taskId: Long, content: String, attachments: List<Long> = emptyList()): Boolean {
        val updated = MessageStoreService.updatePendingTask(sessionId, taskId, content, attachments)
        if (updated) {
            broadcast(JsonObject().apply {
                addProperty("type", "message_task")
                addProperty("id", taskId)
                addProperty("session_id", sessionId)
                addProperty("status", "pending")
                addProperty("content", content)
            })
        }
        return updated
    }

    fun deleteTurn(sessionId: Long, turnId: String): Boolean {
        cancelActiveTaskForTurn(sessionId, turnId)
        val deleted = MessageStoreService.deleteMessageTurn(sessionId, turnId)
        if (deleted) {
            broadcast(JsonObject().apply {
                addProperty("type", "message_task")
                addProperty("session_id", sessionId)
                addProperty("turn_id", turnId)
                addProperty("deleted", true)
            })
        }
        return deleted
    }

    private fun cancelActiveTaskForTurn(sessionId: Long, turnId: String) {
        val entry = activeTasks.entries.firstOrNull { it.value.sessionId == sessionId } ?: return
        cancelActiveTask(sessionId, entry.key)
    }

    fun cancelActiveTaskForSession(sessionId: Long): MessageTaskCancelTarget? {
        val entry = activeTasks.entries.firstOrNull { it.value.sessionId == sessionId } ?: return null
        return cancelActiveTask(sessionId, entry.key)
    }

    fun cancelActiveTask(sessionId: Long, taskId: Long): MessageTaskCancelTarget? {
        val active = activeTasks[taskId] ?: return null
        if (active.sessionId != sessionId) return null
        val tool = active.toolSlot.get()
        if (tool != null) {
            tool.cancel()
            return MessageTaskCancelTarget.TOOL_BATCH
        }
        active.modelCancelRequested.set(true)
        active.model.cancel()
        return MessageTaskCancelTarget.MODEL
    }

    private fun scan() {
        MessageStoreService.pendingMessageTasks(5).forEach { task ->
            if (!scheduledTasks.add(task.id)) return@forEach
            AgentExecutors.shared.execute {
                try {
                    processTask(task)
                } catch (error: Throwable) {
                    if (isCancelError(error)) handleTaskCancellation(task) else handleTaskFailure(task, error)
                } finally {
                    scheduledTasks.remove(task.id)
                    scan()
                }
            }
        }
    }

    private fun processTask(task: PendingMessageTask) {
        val active = ActiveMessageTask(
            sessionId = task.sessionId,
            model = ModelCancel(),
            toolSlot = java.util.concurrent.atomic.AtomicReference(null),
            modelCancelRequested = AtomicBoolean(false),
        )
        activeTasks[task.id] = active
        try {
            processCodingTask(task, active)
            if (active.modelCancelRequested.get()) {
                if (discardTurnWithoutModelOutput(task)) return
                if (!finishTaskIfProcessing(task, MessageTaskStatus.CANCELLED, "模型回复已取消")) return
                val recorder = UnifiedMessageEventRecorder(task.sessionId, broadcaster = ::broadcast)
                recorder.recordCancellation(task.turnId)
                broadcastTask(task, MessageTaskStatus.CANCELLED)
                return
            }
            if (discardTurnWithoutModelOutput(task)) return
        } finally {
            activeTasks.remove(task.id)
        }
    }

    private fun processCodingTask(task: PendingMessageTask, active: ActiveMessageTask) {
        if (task.resumed) {
            broadcastTask(task, MessageTaskStatus.PROCESSING)
        } else {
            val claimed = MessageStoreService.claimMessageTask(task.id)
            if (!claimed) {
                val current = MessageStoreService.messageTaskStatus(task.sessionId, task.id)
                if (active.modelCancelRequested.get() || current == null || current?.isTerminal() == true) return
                throw IllegalStateException("消息任务 `${task.id}` 无法从 pending 转为 processing")
            }
            broadcastTask(task, MessageTaskStatus.PROCESSING)
            broadcastClaimedUserEvent(task)
        }
        val session = ChatSessionService.sessionById(task.sessionId)
            ?: throw IllegalStateException("Coding 会话 `${task.sessionId}` 不存在")
        val provider = CatalogService.providerById(session.providerId)
            ?: throw IllegalStateException("供应商 `${session.providerId}` 不存在")
        val snapshot = CodingSessionSupport.applyReasoningOverride(
            session.modelSnapshot,
            session.reasoningLevel,
            session.reasoningConfig,
            provider.kind,
        )
        val model = ModelResolver.resolveFromSnapshot(provider, snapshot)
        val environment = CodingEnvironmentService.requireEnabled(session.codingEnvironmentId)
        val attachments = MessageStoreService.listAttachmentsByIds(task.sessionId, task.attachments)
        require(attachments.size == task.attachments.size) { "Coding 消息包含无效附件" }
        val historyEvents = MessageStoreService.assembledHistoryEvents(task.sessionId, task.turnId, includeCurrentTurn = false)
        val history = MessageHistorySupport.toModelHistory(historyEvents, task.turnId, includeCurrentTurn = false)
        val promptMessage = if (task.resumed) {
            Message.user(MessageEventSupport.RESUMED_PROMPT)
        } else {
            CodingAttachmentPrompt.currentUserMessage(
                content = task.content,
                attachments = attachments,
                modalities = model.params.modalities,
            )
        }
        val promptId = task.executionConfig.get("prompt_id")?.takeIf { it.isJsonPrimitive }?.asLong ?: session.promptId
        val runtime = CodingRuntimeSupport.assemble(
            environment = environment,
            sessionId = task.sessionId,
            cwd = session.cwd,
            promptId = promptId,
            compactionSummary = session.config.get("coding_compaction_summary")?.takeIf { it.isJsonPrimitive }?.asString,
            cancel = ModelCancel(active.toolSlot),
            project = CodingRuntimeSupport.currentIdeProject(),
        )
        val recorder = UnifiedMessageEventRecorder(task.sessionId, broadcaster = ::broadcast)
        val continuation = MessageAppendContinuation(task.sessionId, task.turnId, model.params.modalities)
        val result = ModelRuntime.execute(
            model = model,
            agentMaxTurns = null,
            preamble = runtime.preamble,
            promptMessage = promptMessage,
            history = history,
            tools = runtime.tools,
            logContext = ModelLogContext(
                sourceType = "message",
                sourceId = task.turnId,
                agentId = session.agentId.takeIf { it > 0 },
                messageType = "coding_message_turn",
                requestSnapshot = task.executionConfig,
            ),
            environmentId = null,
            streamed = model.stream,
            eventSink = recorder.streamSink(task.turnId),
            cancel = active.model,
            toolCancel = ModelCancel(active.toolSlot),
            toolCancelSlot = active.toolSlot,
            continuation = continuation,
        )
        if (active.modelCancelRequested.get()) return
        result.value.get("usage")?.let { recorder.recordTurnUsage(task.turnId, it) }
        recorder.completeTurn(task.turnId)
        finishTaskIfProcessing(task, MessageTaskStatus.COMPLETED, null)
        broadcastTask(task, MessageTaskStatus.COMPLETED)
    }

    private fun discardTurnWithoutModelOutput(task: PendingMessageTask): Boolean {
        if (MessageStoreService.turnHasModelOutput(task.sessionId, task.turnId)) return false
        MessageStoreService.deleteMessageTurn(task.sessionId, task.turnId)
        broadcastTask(task, MessageTaskStatus.CANCELLED, deleted = true)
        return true
    }

    private fun handleTaskCancellation(task: PendingMessageTask) {
        val current = MessageStoreService.messageTaskStatus(task.sessionId, task.id)
        if (current == null || current.isTerminal()) return
        if (current != MessageTaskStatus.PROCESSING) return
        if (discardTurnWithoutModelOutput(task)) return
        if (!finishTaskIfProcessing(task, MessageTaskStatus.CANCELLED, "模型回复已取消")) return
        val recorder = UnifiedMessageEventRecorder(task.sessionId, broadcaster = ::broadcast)
        recorder.recordCancellation(task.turnId)
        broadcastTask(task, MessageTaskStatus.CANCELLED)
    }

    private fun isCancelError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is ModelRequestCancelledException || current is java.util.concurrent.CancellationException) return true
            val message = current.message.orEmpty()
            if ("cancelled" in message.lowercase() || "canceled" in message.lowercase()) return true
            current = current.cause
        }
        return false
    }

    private fun handleTaskFailure(task: PendingMessageTask, error: Throwable) {
        val current = MessageStoreService.messageTaskStatus(task.sessionId, task.id)
        if (current == null || current.isTerminal()) return
        if (current != MessageTaskStatus.PROCESSING) return
        if (discardTurnWithoutModelOutput(task)) return
        val message = error.message ?: error.toString()
        if (!finishTaskIfProcessing(task, MessageTaskStatus.FAILED, message)) return
        val recorder = UnifiedMessageEventRecorder(task.sessionId, broadcaster = ::broadcast)
        recorder.recordFailure(task.turnId, message)
        broadcastTask(task, MessageTaskStatus.FAILED)
    }

    private fun finishTaskIfProcessing(task: PendingMessageTask, status: MessageTaskStatus, error: String?): Boolean {
        return try {
            MessageStoreService.finishMessageTask(task.id, status, error)
        } catch (finishError: Throwable) {
            val current = MessageStoreService.messageTaskStatus(task.sessionId, task.id)
            if (current?.isTerminal() == true) false else throw finishError
        }
    }

    private fun broadcastClaimedUserEvent(task: PendingMessageTask) {
        val eventId = MessageStoreService.messageEventIdForTurn(task.sessionId, task.turnId) ?: return
        UnifiedMessageEventRecorder(task.sessionId, broadcaster = ::broadcast).broadcastEventId(eventId)
    }

    private fun broadcastTask(task: PendingMessageTask, status: MessageTaskStatus, deleted: Boolean = false) {
        broadcast(JsonObject().apply {
            addProperty("type", "message_task")
            addProperty("id", task.id)
            addProperty("session_id", task.sessionId)
            addProperty("turn_id", task.turnId)
            addProperty("status", status.value)
            addProperty("content", task.content)
            add("attachments", JsonArray().apply { task.attachments.forEach(::add) })
            add("attachment_items", JsonArray().apply { task.attachmentItems.forEach(::add) })
            addProperty("deleted", deleted)
        })
    }

    private fun broadcast(payload: JsonObject) {
        listeners.forEach { listener -> runCatching { listener(payload) } }
    }
}
