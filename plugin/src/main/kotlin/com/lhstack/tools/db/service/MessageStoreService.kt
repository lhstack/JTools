package com.lhstack.tools.db.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.coding.ClaimedAppendItem
import com.lhstack.tools.agent.coding.MessageAppendStatus
import com.lhstack.tools.agent.coding.MessageAttachmentRecord
import com.lhstack.tools.agent.coding.MessageEventRecord
import com.lhstack.tools.agent.coding.MessageEventStatus
import com.lhstack.tools.agent.coding.MessageEventSupport
import com.lhstack.tools.agent.coding.MessageEventType
import com.lhstack.tools.agent.coding.MessageSessionKind
import com.lhstack.tools.agent.coding.MessageTaskStatus
import com.lhstack.tools.agent.coding.NewMessageEvent
import com.lhstack.tools.agent.coding.PendingMessageTask
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.ChatSessionEntity
import com.lhstack.tools.db.entity.ContextCompactionEntity
import com.lhstack.tools.db.entity.MessageAppendItemEntity
import com.lhstack.tools.db.entity.MessageAttachmentEntity
import com.lhstack.tools.db.entity.MessageEventEntity
import com.lhstack.tools.db.entity.MessageProcessingTaskEntity
import com.lhstack.tools.db.mapper.ChatSessionMapper
import com.lhstack.tools.db.mapper.ContextCompactionMapper
import com.lhstack.tools.db.mapper.MessageAppendItemMapper
import com.lhstack.tools.db.mapper.MessageAttachmentMapper
import com.lhstack.tools.db.mapper.MessageEventMapper
import com.lhstack.tools.db.mapper.MessageProcessingTaskMapper
import org.apache.ibatis.session.SqlSession
import java.time.LocalDateTime

/**
 * 对齐 awake-claw repository/message_event.rs 的编码会话子集：
 * 任务入队、claim、追加、流式事件、恢复。业务历史只认 message_events。
 */
object MessageStoreService {
    data class CreatedMessageTask(val sessionId: Long, val turnId: String, val taskId: Long)
    data class StreamRoundState(val round: Int, val running: Boolean)


    fun sessionTaskJson(record: SessionTaskRecord): JsonObject = JsonObject().apply {
        addProperty("id", record.id)
        addProperty("session_id", record.sessionId)
        addProperty("turn_id", record.turnId)
        addProperty("status", record.status.value)
        addProperty("content", record.content)
        add("attachments", jsonArrayOf(record.attachments))
        add("attachment_items", JsonArray().apply { record.attachmentItems.forEach(::add) })
        record.error?.let { addProperty("error", it) }
        record.createdAt?.let { addProperty("created_at", it) }
        record.claimedAt?.let { addProperty("claimed_at", it) }
        record.completedAt?.let { addProperty("completed_at", it) }
    }

    fun createMessageTask(
        sessionId: Long,
        turnId: String,
        content: String,
        attachments: List<Long>,
        executionConfig: JsonObject,
    ): CreatedMessageTask = AgentDatabase.execute { session ->
        require(turnId.isNotBlank()) { "Turn ID 不能为空" }
        require(content.isNotBlank() || attachments.isNotEmpty()) { "消息内容和附件不能同时为空" }
        val sessionEntity = session.getMapper(ChatSessionMapper::class.java).selectById(sessionId)
            ?: throw IllegalArgumentException("消息会话 `$sessionId` 不存在")
        MessageSessionKind.from(sessionEntity.sessionType)
        require(attachments.isEmpty() || countAttachments(session, sessionId, attachments) == attachments.size.toLong()) {
            "消息包含无效附件"
        }
        val config = taskExecutionConfig(session, sessionId, content, attachments, executionConfig)
        val entity = MessageProcessingTaskEntity().apply {
            this.sessionId = sessionId
            this.turnId = turnId
            this.status = MessageTaskStatus.PENDING.value
            this.executionConfig = config.toString()
        }
        session.getMapper(MessageProcessingTaskMapper::class.java).insert(entity)
        val taskId = entity.id ?: throw IllegalStateException("message_processing_tasks 写入后未获得 id")
        CreatedMessageTask(sessionId, turnId, taskId)
    }

    fun pendingMessageTasks(limit: Int = 5): List<PendingMessageTask> = AgentDatabase.execute { session ->
        require(limit in 1..100) { "任务分页 limit 必须在 1..=100" }
        val connection = session.connection
        val sql = """
            select t.id,t.session_id,t.turn_id,s.session_type,t.execution_config,t.status
            from message_processing_tasks t
            join message_sessions s on s.id=t.session_id
            where t.status in ('pending','processing')
              and not exists (
                  select 1 from message_processing_tasks active
                  where active.session_id=t.session_id
                    and active.status='processing'
                    and t.status='pending'
              )
              and not exists (
                  select 1 from message_processing_tasks earlier
                  where earlier.session_id=t.session_id
                    and earlier.status='pending'
                    and earlier.id < t.id
                    and t.status='pending'
              )
            order by t.id limit ?
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                val tasks = mutableListOf<PendingMessageTask>()
                while (rows.next()) {
                    val config = JsonParser.parseString(rows.getString(5)).asJsonObject
                    val message = config.get("_message")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: throw IllegalStateException("消息任务缺少待消费消息")
                    val attachments = message.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asLong }
                        ?: emptyList()
                    val attachmentItems = message.get("attachment_items")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.toList() ?: emptyList()
                    val status = MessageTaskStatus.from(rows.getString(6))
                    tasks += PendingMessageTask(
                        id = rows.getLong(1),
                        sessionId = rows.getLong(2),
                        turnId = rows.getString(3),
                        sessionType = MessageSessionKind.from(rows.getString(4)),
                        executionConfig = config,
                        content = message.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                        attachments = attachments,
                        attachmentItems = attachmentItems,
                        resumed = status == MessageTaskStatus.PROCESSING,
                    )
                }
                tasks
            }
        }
    }

    fun claimMessageTask(id: Long): Boolean = AgentDatabase.execute { session ->
        val mapper = session.getMapper(MessageProcessingTaskMapper::class.java)
        val task = mapper.selectById(id) ?: return@execute false
        if (task.status != MessageTaskStatus.PENDING.value) return@execute false
        val config = JsonParser.parseString(task.executionConfig).asJsonObject
        val message = config.get("_message")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("消息任务缺少待消费消息")
        val content = message.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val attachments = message.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asLong } ?: emptyList()
        val attachmentItems = message.get("attachment_items") ?: JsonArray()
        val context = eventContextWithTokenBudget(
            session,
            MessageEventType.USER_MESSAGE,
            JsonObject().apply {
                addProperty("content", content)
                add("attachments", jsonArrayOf(attachments))
                add("attachment_items", attachmentItems)
            },
        )
        insertEvent(
            session,
            NewMessageEvent(
                sessionId = task.sessionId,
                turnId = task.turnId,
                status = MessageEventStatus.COMPLETED,
                eventType = MessageEventType.USER_MESSAGE,
                eventId = "1",
                summary = MessageEventSupport.summary(content),
                context = context,
            ),
        )
        task.status = MessageTaskStatus.PROCESSING.value
        task.claimedAt = nowText()
        task.error = null
        mapper.updateById(task) == 1
    }

    fun finishMessageTask(id: Long, status: MessageTaskStatus, error: String?): Boolean = AgentDatabase.execute { session ->
        require(status.isTerminal()) { "任务必须转换到终态" }
        val mapper = session.getMapper(MessageProcessingTaskMapper::class.java)
        val changed = mapper.update(
            null,
            UpdateWrapper<MessageProcessingTaskEntity>()
                .eq("id", id)
                .eq("status", MessageTaskStatus.PROCESSING.value)
                .set("status", status.value)
                .set("error", error)
                .set("completed_at", nowText()),
        )
        if (changed == 1) return@execute true
        val current = mapper.selectById(id)?.status
            ?.let(MessageTaskStatus::from)
        if (current?.isTerminal() == true) return@execute false
        throw IllegalStateException("任务不存在或不在 processing 状态")
    }

    /**
     * 读取任务当前状态供取消命令处理竞态。
     * 取消请求到达时任务可能已经由 Worker 收尾，调用方必须区分终态而不是重复抛错。
     */
    fun messageTaskStatus(sessionId: Long, taskId: Long): MessageTaskStatus? = AgentDatabase.execute { session ->
        val task = session.getMapper(MessageProcessingTaskMapper::class.java).selectById(taskId)
            ?: return@execute null
        if (task.sessionId != sessionId) return@execute null
        MessageTaskStatus.from(task.status)
    }

    fun recoverMessageTasks(): Int = AgentDatabase.execute { session ->
        recoverContextCompactions(session)
        val appendMapper = session.getMapper(MessageAppendItemMapper::class.java)
        val claimed = appendMapper.selectList(
            QueryWrapper<MessageAppendItemEntity>().eq("status", MessageAppendStatus.CLAIMED.value),
        )
        claimed.forEach { item ->
            item.status = MessageAppendStatus.PENDING.value
            item.claimedAt = null
            appendMapper.updateById(item)
        }
        val eventMapper = session.getMapper(MessageEventMapper::class.java)
        eventMapper.selectList(
            QueryWrapper<MessageEventEntity>()
                .eq("event_type", MessageEventType.TOOL_CALL.value)
                .eq("status", MessageEventStatus.RUNNING.value),
        ).forEach { event ->
            val context = parseObject(event.context)
            context.addProperty("outcome", "failed")
            context.addProperty("result", MessageEventSupport.RESTART_TOOL_REASON)
            context.addProperty("reason", MessageEventSupport.RESTART_TOOL_REASON)
            context.addProperty("result_status", "failed")
            event.status = MessageEventStatus.FAILED.value
            event.summary = MessageEventSupport.RESTART_TOOL_REASON
            event.context = context.toString()
            event.revision += 1
            eventMapper.updateById(event)
        }
        eventMapper.selectList(
            QueryWrapper<MessageEventEntity>()
                .`in`("event_type", MessageEventType.MODEL_REASONING.value, MessageEventType.MODEL_REPLY.value)
                .eq("status", MessageEventStatus.RUNNING.value),
        ).forEach { event ->
            val context = parseObject(event.context)
            context.addProperty("streaming", false)
            event.status = MessageEventStatus.COMPLETED.value
            event.context = context.toString()
            event.revision += 1
            eventMapper.updateById(event)
        }
        session.getMapper(MessageProcessingTaskMapper::class.java).selectCount(
            QueryWrapper<MessageProcessingTaskEntity>().eq("status", MessageTaskStatus.PROCESSING.value),
        ).toInt()
    }

    fun processingTurnId(sessionId: Long): String? = AgentDatabase.execute { session ->
        session.getMapper(MessageProcessingTaskMapper::class.java).selectOne(
            QueryWrapper<MessageProcessingTaskEntity>()
                .eq("session_id", sessionId)
                .eq("status", MessageTaskStatus.PROCESSING.value)
                .last("limit 1"),
        )?.turnId
    }

    fun enqueueAppendItem(sessionId: Long, turnId: String, content: String, attachments: List<Long>): Long =
        AgentDatabase.execute { session ->
            require(content.isNotEmpty() || attachments.isNotEmpty()) { "追加消息不能为空" }
            require(attachments.isEmpty() || countAttachments(session, sessionId, attachments) == attachments.size.toLong()) {
                "消息包含无效附件"
            }
            val processingTurn = session.getMapper(MessageProcessingTaskMapper::class.java).selectOne(
                QueryWrapper<MessageProcessingTaskEntity>()
                    .eq("session_id", sessionId)
                    .eq("status", MessageTaskStatus.PROCESSING.value)
                    .last("limit 1"),
            )?.turnId
            require(processingTurn == turnId) { "当前会话没有进行中的消息任务，无法追加消息" }
            val nextSequence = (session.getMapper(MessageAppendItemMapper::class.java).selectList(
                QueryWrapper<MessageAppendItemEntity>()
                    .eq("session_id", sessionId)
                    .eq("turn_id", turnId),
            ).maxOfOrNull { it.sequence } ?: 0) + 1
            val entity = MessageAppendItemEntity().apply {
                this.sessionId = sessionId
                this.turnId = turnId
                this.sequence = nextSequence
                this.content = content
                this.attachments = jsonArrayOf(attachments).toString()
                this.status = MessageAppendStatus.PENDING.value
            }
            session.getMapper(MessageAppendItemMapper::class.java).insert(entity)
            touchSession(session, sessionId)
            entity.id ?: throw IllegalStateException("message_append_items 写入后未获得 id")
        }

    fun claimAppendItems(sessionId: Long, turnId: String, sealIfEmpty: Boolean): List<ClaimedAppendItem> =
        AgentDatabase.execute { session ->
            val mapper = session.getMapper(MessageAppendItemMapper::class.java)
            val pending = mapper.selectList(
                QueryWrapper<MessageAppendItemEntity>()
                    .eq("session_id", sessionId)
                    .eq("turn_id", turnId)
                    .eq("status", MessageAppendStatus.PENDING.value)
                    .orderByAsc("sequence", "id"),
            )
            if (pending.isEmpty()) {
                if (sealIfEmpty) {
                    val taskMapper = session.getMapper(MessageProcessingTaskMapper::class.java)
                    val changed = taskMapper.update(
                        null,
                        UpdateWrapper<MessageProcessingTaskEntity>()
                            .eq("session_id", sessionId)
                            .eq("turn_id", turnId)
                            .eq("status", MessageTaskStatus.PROCESSING.value)
                            .set("status", MessageTaskStatus.COMPLETED.value)
                            .set("error", null)
                            .set("completed_at", nowText()),
                    )
                    if (changed == 0) {
                        val current = taskMapper.selectOne(
                            QueryWrapper<MessageProcessingTaskEntity>()
                                .eq("session_id", sessionId)
                                .eq("turn_id", turnId)
                        )?.status?.let(MessageTaskStatus::from)
                        if (current == null || !current.isTerminal()) {
                            throw IllegalStateException("任务不存在或不在 processing 状态")
                        }
                    }
                }
                return@execute emptyList()
            }
            pending.map { item ->
                item.status = MessageAppendStatus.CLAIMED.value
                item.claimedAt = nowText()
                mapper.updateById(item)
                ClaimedAppendItem(item.id!!, item.content, parseLongArray(item.attachments))
            }
        }

    fun createClaimedAppendEvent(
        sessionId: Long,
        turnId: String,
        itemId: Long?,
        content: String,
        attachments: List<Long>,
        parentEventId: Long?,
    ): Long = AgentDatabase.execute { session ->
        val attachmentItems = attachmentItemsForIds(session, sessionId, attachments)
        val context = eventContextWithTokenBudget(
            session,
            MessageEventType.APPEND_MESSAGE,
            JsonObject().apply {
                addProperty("content", content)
                add("attachments", jsonArrayOf(attachments))
                add("attachment_items", attachmentItems)
                itemId?.let { addProperty("append_item_id", it) }
            },
        )
        val eventId = insertEvent(
            session,
            NewMessageEvent(
                parentEventId = parentEventId,
                sessionId = sessionId,
                turnId = turnId,
                status = MessageEventStatus.COMPLETED,
                eventType = MessageEventType.APPEND_MESSAGE,
                eventId = itemId?.toString() ?: "append",
                summary = MessageEventSupport.summary(content),
                context = context,
            ),
        )
        if (itemId != null) {
            val mapper = session.getMapper(MessageAppendItemMapper::class.java)
            val item = mapper.selectById(itemId) ?: throw IllegalArgumentException("追加消息 `$itemId` 不存在")
            item.messageEventId = eventId
            mapper.updateById(item)
        }
        eventId
    }

    fun deliverAppendItems(ids: List<Long>) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(MessageAppendItemMapper::class.java)
        ids.forEach { id ->
            val item = mapper.selectById(id) ?: throw IllegalStateException("追加消息 `$id` 不存在")
            require(item.status == MessageAppendStatus.CLAIMED.value && item.messageEventId != null) {
                "追加消息 `$id` 尚未写入对话记录，不能标记为已投递"
            }
            item.status = MessageAppendStatus.DELIVERED.value
            item.deliveredAt = nowText()
            mapper.updateById(item)
        }
    }

    fun releaseAppendItems(ids: List<Long>) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(MessageAppendItemMapper::class.java)
        ids.forEach { id ->
            val item = mapper.selectById(id) ?: return@forEach
            if (item.status == MessageAppendStatus.CLAIMED.value) {
                item.status = MessageAppendStatus.PENDING.value
                item.claimedAt = null
                mapper.updateById(item)
            }
        }
    }

    fun upsertMessageEvent(event: NewMessageEvent): Long = AgentDatabase.execute { session ->
        insertEvent(session, event)
    }

    fun appendMessageEventText(
        sessionId: Long,
        turnId: String,
        eventType: MessageEventType,
        eventId: String,
        text: String,
        parentEventId: Long?,
    ): Long = AgentDatabase.execute { session ->
        require(eventType == MessageEventType.MODEL_REASONING || eventType == MessageEventType.MODEL_REPLY) {
            "只能累加模型推理或模型回复事件"
        }
        val mapper = session.getMapper(MessageEventMapper::class.java)
        val existing = findEvent(session, sessionId, turnId, parentEventId, eventType, eventId)
        val context = if (existing == null) {
            JsonObject()
        } else {
            require(existing.status == MessageEventStatus.RUNNING.value) { "模型事件 `$eventId` 已结束，不能继续写入" }
            parseObject(existing.context)
        }
        val previous = context.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        context.addProperty("text", previous + text)
        context.addProperty("streaming", true)
        val budgeted = eventContextWithTokenBudget(session, eventType, context)
        if (existing == null) {
            insertEvent(
                session,
                NewMessageEvent(
                    parentEventId = parentEventId,
                    sessionId = sessionId,
                    turnId = turnId,
                    status = MessageEventStatus.RUNNING,
                    eventType = eventType,
                    eventId = eventId,
                    summary = MessageEventSupport.latestSummary(previous + text),
                    context = budgeted,
                ),
            )
        } else {
            existing.summary = MessageEventSupport.latestSummary(previous + text)
            existing.context = budgeted.toString()
            existing.revision += 1
            mapper.updateById(existing)
            touchSession(session, sessionId)
            existing.id!!
        }
    }

    fun completeStreamModelEvent(
        sessionId: Long,
        turnId: String,
        eventType: MessageEventType,
        eventId: String,
        parentEventId: Long?,
    ): Long? = AgentDatabase.execute { session ->
        val existing = findEvent(session, sessionId, turnId, parentEventId, eventType, eventId)
            ?: return@execute null
        if (existing.status != MessageEventStatus.RUNNING.value) return@execute null
        val context = parseObject(existing.context)
        context.addProperty("streaming", false)
        existing.status = MessageEventStatus.COMPLETED.value
        existing.context = eventContextWithTokenBudget(session, eventType, context).toString()
        existing.revision += 1
        session.getMapper(MessageEventMapper::class.java).updateById(existing)
        touchSession(session, sessionId)
        existing.id
    }

    fun completeMessageModelEvents(sessionId: Long, turnId: String, parentEventId: Long?) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(MessageEventMapper::class.java)
        mapper.selectList(
            QueryWrapper<MessageEventEntity>()
                .eq("session_id", sessionId)
                .eq("turn_id", turnId)
                .eq("status", MessageEventStatus.RUNNING.value)
                .`in`("event_type", MessageEventType.MODEL_REASONING.value, MessageEventType.MODEL_REPLY.value),
        ).filter { it.parentEventId == parentEventId }.forEach { event ->
            val context = parseObject(event.context)
            context.addProperty("streaming", false)
            event.status = MessageEventStatus.COMPLETED.value
            event.context = eventContextWithTokenBudget(session, MessageEventType.from(event.eventType), context).toString()
            event.revision += 1
            mapper.updateById(event)
        }
        touchSession(session, sessionId)
    }

    fun finishRunningModelEvents(
        sessionId: Long,
        turnId: String,
        status: MessageEventStatus,
        outcome: String,
        parentEventId: Long?,
    ) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(MessageEventMapper::class.java)
        mapper.selectList(
            QueryWrapper<MessageEventEntity>()
                .eq("session_id", sessionId)
                .eq("turn_id", turnId)
                .eq("status", MessageEventStatus.RUNNING.value),
        ).filter { it.parentEventId == parentEventId }.forEach { event ->
            val context = parseObject(event.context)
            context.addProperty("outcome", outcome)
            context.addProperty("streaming", false)
            event.status = status.value
            event.context = context.toString()
            event.revision += 1
            mapper.updateById(event)
        }
        touchSession(session, sessionId)
    }

    fun upsertModelRetryEvent(
        sessionId: Long,
        turnId: String,
        round: Int,
        error: String,
        parentEventId: Long?,
    ): Long {
        val existing = messageEventsForTurn(sessionId, turnId, true).firstOrNull { event ->
            event.eventType == MessageEventType.MODEL_RETRY &&
                event.eventId == "retry_$round" &&
                event.parentEventId == parentEventId
        }
        val previousCount = existing?.context?.get("retry_count")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val retryCount = if (existing == null) 1L else previousCount + 1L
        return upsertMessageEvent(
            NewMessageEvent(
                parentEventId = parentEventId,
                sessionId = sessionId,
                turnId = turnId,
                status = MessageEventStatus.COMPLETED,
                eventType = MessageEventType.MODEL_RETRY,
                eventId = "retry_$round",
                summary = "retry ×$retryCount",
                context = JsonObject().apply {
                    addProperty("round", round)
                    addProperty("retry_count", retryCount)
                    addProperty("failed_attempt", retryCount)
                    addProperty("next_attempt", retryCount + 1)
                    addProperty("error", error)
                },
            ),
        )
    }

    fun mergeToolCallResult(
        sessionId: Long,
        turnId: String,
        callId: String,
        result: JsonElement,
        status: MessageEventStatus,
        reason: String?,
        parentEventId: Long?,
    ): Long = AgentDatabase.execute { session ->
        require(status != MessageEventStatus.RUNNING) { "工具结果必须是终态" }
        val event = findEvent(session, sessionId, turnId, parentEventId, MessageEventType.TOOL_CALL, callId)
            ?: throw IllegalStateException("工具调用事件 `$callId` 不存在")
        val context = parseObject(event.context)
        context.add("result", result)
        context.addProperty("result_status", status.value)
        if (reason != null) context.addProperty("reason", reason)
        event.status = status.value
        event.summary = context.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "tool"
        event.context = eventContextWithTokenBudget(session, MessageEventType.TOOL_CALL, context).toString()
        event.revision += 1
        session.getMapper(MessageEventMapper::class.java).updateById(event)
        touchSession(session, sessionId)
        event.id!!
    }

    fun streamRoundState(sessionId: Long, turnId: String, parentEventId: Long?): StreamRoundState? =
        AgentDatabase.execute { session ->
            val events = session.getMapper(MessageEventMapper::class.java).selectList(
                QueryWrapper<MessageEventEntity>()
                    .eq("session_id", sessionId)
                    .eq("turn_id", turnId)
                    .and { wrapper ->
                        wrapper.likeRight("event_id", "stream_reasoning_")
                            .or()
                            .likeRight("event_id", "stream_reply_")
                    }
                    .orderByDesc("id"),
            ).filter { it.parentEventId == parentEventId }
            val latest = events.firstOrNull { MessageEventSupport.parseStreamRound(it.eventType, it.eventId) != null }
                ?: return@execute null
            val round = MessageEventSupport.parseStreamRound(latest.eventType, latest.eventId) ?: return@execute null
            val running = events.any {
                it.status == MessageEventStatus.RUNNING.value &&
                    (it.eventId == "stream_reasoning_$round" || it.eventId == "stream_reply_$round")
            }
            StreamRoundState(round, running)
        }

    data class SessionTaskRecord(
        val id: Long,
        val sessionId: Long,
        val turnId: String,
        val status: MessageTaskStatus,
        val content: String,
        val attachments: List<Long>,
        val attachmentItems: List<JsonElement>,
        val error: String?,
        val createdAt: String?,
        val claimedAt: String?,
        val completedAt: String?,
    )

    fun sessionTasks(sessionId: Long): List<SessionTaskRecord> = AgentDatabase.execute { session ->
        session.connection.prepareStatement(
            """
            select t.id,t.session_id,t.turn_id,t.status,t.execution_config,t.error,t.created_at,t.claimed_at,t.completed_at
            from message_processing_tasks t
            where t.session_id=? and t.status in ('pending','processing')
            order by t.id asc
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { rows ->
                val tasks = mutableListOf<SessionTaskRecord>()
                while (rows.next()) {
                    val config = JsonParser.parseString(rows.getString(5)).asJsonObject
                    val message = config.get("_message")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                    tasks += SessionTaskRecord(
                        id = rows.getLong(1),
                        sessionId = rows.getLong(2),
                        turnId = rows.getString(3),
                        status = MessageTaskStatus.from(rows.getString(4)),
                        content = message.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                        attachments = message.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray
                            ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asLong } ?: emptyList(),
                        attachmentItems = message.get("attachment_items")?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
                            ?: emptyList(),
                        error = rows.getString(6),
                        createdAt = rows.getString(7),
                        claimedAt = rows.getString(8),
                        completedAt = rows.getString(9),
                    )
                }
                tasks
            }
        }
    }

    fun cancelPendingTask(sessionId: Long, taskId: Long): Boolean = AgentDatabase.execute { session ->
        val mapper = session.getMapper(MessageProcessingTaskMapper::class.java)
        val task = mapper.selectById(taskId) ?: return@execute false
        if (task.sessionId != sessionId || task.status != MessageTaskStatus.PENDING.value) return@execute false
        mapper.deleteById(taskId)
        true
    }

    fun updatePendingTask(sessionId: Long, taskId: Long, content: String, attachments: List<Long>): Boolean =
        AgentDatabase.execute { session ->
            require(content.isNotBlank() || attachments.isNotEmpty()) { "排队消息不能为空" }
            require(attachments.isEmpty() || countAttachments(session, sessionId, attachments) == attachments.size.toLong()) {
                "消息包含无效附件"
            }
            val mapper = session.getMapper(MessageProcessingTaskMapper::class.java)
            val task = mapper.selectById(taskId) ?: return@execute false
            if (task.sessionId != sessionId || task.status != MessageTaskStatus.PENDING.value) return@execute false
            val config = JsonParser.parseString(task.executionConfig).asJsonObject
            config.add("_message", JsonObject().apply {
                addProperty("content", content)
                add("attachments", jsonArrayOf(attachments))
                add("attachment_items", attachmentItemsForIds(session, sessionId, attachments))
            })
            task.executionConfig = config.toString()
            mapper.updateById(task) == 1
        }

    fun listMessageEvents(
        sessionId: Long,
        beforeId: Long? = null,
        limit: Int = 80,
        includeContext: Boolean = false,
    ): List<MessageEventRecord> = AgentDatabase.execute { session ->
        require(limit in 1..200) { "事件分页 limit 必须在 1..=200" }
        val sql = buildString {
            append("select id from message_events where session_id=? and ifnull(parent_event_id, 0)=0 and not (event_type='model_reply' and event_id='final')")
            if (beforeId != null) append(" and id < ?")
            append(" order by id desc limit ?")
        }
        val rootIds = session.connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, sessionId)
            var index = 2
            if (beforeId != null) statement.setLong(index++, beforeId)
            statement.setInt(index, limit)
            statement.executeQuery().use { rows ->
                val ids = mutableListOf<Long>()
                while (rows.next()) ids += rows.getLong(1)
                ids.asReversed()
            }
        }
        eventsForRootIds(session, sessionId, rootIds, includeContext)
    }

    fun eventsAfter(sessionId: Long, afterId: Long, includeContext: Boolean = false): List<MessageEventRecord> =
        AgentDatabase.execute { session ->
            val rootIds = session.connection.prepareStatement(
                "select id from message_events where session_id=? and ifnull(parent_event_id, 0)=0 and id > ? and not (event_type='model_reply' and event_id='final') order by id"
            ).use { statement ->
                statement.setLong(1, sessionId)
                statement.setLong(2, afterId)
                statement.executeQuery().use { rows ->
                    val ids = mutableListOf<Long>()
                    while (rows.next()) ids += rows.getLong(1)
                    ids
                }
            }
            eventsForRootIds(session, sessionId, rootIds, includeContext)
        }

    fun messageEvent(id: Long, includeContext: Boolean): MessageEventRecord? = AgentDatabase.execute { session ->
        session.getMapper(MessageEventMapper::class.java).selectById(id)?.let { toRecord(it, includeContext) }
    }

    fun messageEventIdForTurn(sessionId: Long, turnId: String): Long? = AgentDatabase.execute { session ->
        session.getMapper(MessageEventMapper::class.java).selectOne(
            QueryWrapper<MessageEventEntity>()
                .eq("session_id", sessionId)
                .eq("turn_id", turnId)
                .eq("event_type", MessageEventType.USER_MESSAGE.value)
                .orderByAsc("id")
                .last("limit 1"),
        )?.id
    }

    fun messageEventsForTurn(sessionId: Long, turnId: String, includeContext: Boolean): List<MessageEventRecord> =
        AgentDatabase.execute { session ->
            session.getMapper(MessageEventMapper::class.java).selectList(
                QueryWrapper<MessageEventEntity>()
                    .eq("session_id", sessionId)
                    .eq("turn_id", turnId)
                    .orderByAsc("id"),
            ).map { toRecord(it, includeContext) }
        }

    fun assembledHistoryEvents(sessionId: Long, currentTurnId: String, includeCurrentTurn: Boolean): List<MessageEventRecord> =
        AgentDatabase.execute { session ->
            val config = sessionConfig(session, sessionId)
            val boundary = compactedThroughEventId(config)
            val events = session.getMapper(MessageEventMapper::class.java).selectList(
                QueryWrapper<MessageEventEntity>()
                    .eq("session_id", sessionId)
                    .orderByAsc("id"),
            ).map { toRecord(it, true) }.filter { event ->
                event.id > boundary && (includeCurrentTurn || event.turnId != currentTurnId)
            }
            events
        }

    fun messageContextStatus(sessionId: Long): JsonObject = AgentDatabase.execute { session ->
        contextStatus(session, sessionId)
    }

    fun codingCompactionEvents(sessionId: Long): List<MessageEventRecord> = AgentDatabase.execute { session ->
        val boundary = compactedThroughEventId(sessionConfig(session, sessionId))
        session.getMapper(MessageEventMapper::class.java).selectList(
            QueryWrapper<MessageEventEntity>()
                .eq("session_id", sessionId)
                .gt("id", boundary)
                .`in`("event_type", MessageEventType.USER_MESSAGE.value, MessageEventType.APPEND_MESSAGE.value, MessageEventType.MODEL_REPLY.value, MessageEventType.TOOL_CALL.value)
                .apply("ifnull(parent_event_id, 0) = 0")
                .orderByAsc("id"),
        ).map { toRecord(it, true) }.filterNot { it.eventType == MessageEventType.MODEL_REPLY && it.eventId == "final" }
    }

    fun createContextCompaction(sessionId: Long, agentId: Long, estimatedTokensBefore: Long): Long = AgentDatabase.execute { session ->
        val mapper = session.getMapper(ContextCompactionMapper::class.java)
        val running = mapper.selectList(
            QueryWrapper<ContextCompactionEntity>()
                .eq("session_id", sessionId)
                .eq("status", "running"),
        )
        require(running.isEmpty()) { "当前 Coding 会话正在压缩上下文" }
        val entity = ContextCompactionEntity().apply {
            this.sessionType = "coding"
            this.sessionId = sessionId
            this.agentId = agentId
            this.status = "running"
            this.estimatedTokensBefore = estimatedTokensBefore.coerceAtLeast(0)
        }
        mapper.insert(entity)
        entity.id ?: throw IllegalStateException("context_compactions 写入后未获得 id")
    }

    fun completeContextCompaction(
        compactionId: Long,
        sessionId: Long,
        compactedThroughEventId: Long,
        summary: String,
        estimatedTokensAfter: Long,
        modelLogId: Long?,
        usage: JsonElement?,
    ): Long = AgentDatabase.execute { session ->
        val mapper = session.getMapper(ContextCompactionMapper::class.java)
        val entity = mapper.selectById(compactionId) ?: throw IllegalStateException("压缩记录 `$compactionId` 不存在")
        require(entity.status == "running") { "压缩记录 `$compactionId` 不在 running 状态" }
        entity.status = "completed"
        entity.summary = summary
        entity.estimatedTokensAfter = estimatedTokensAfter.coerceAtLeast(0)
        entity.modelLogId = modelLogId
        entity.completedAt = nowText()
        mapper.updateById(entity)
        val notice = JsonObject().apply {
            addProperty("summary", summary)
            addProperty("content", summary)
            addProperty("compacted_through_event_id", compactedThroughEventId)
            add("extra", JsonObject().apply {
                addProperty("type", "compaction_notice")
                addProperty("delivery_receiver", "user")
            })
            usage?.takeUnless { it.isJsonNull }?.let { add("usage", it) }
        }
        val noticeId = insertEvent(
            session,
            NewMessageEvent(
                sessionId = sessionId,
                turnId = "compaction-$compactionId",
                status = MessageEventStatus.COMPLETED,
                eventType = MessageEventType.COMPACTION_NOTICE,
                eventId = compactionId.toString(),
                summary = MessageEventSupport.latestSummary(summary),
                context = notice,
            ),
        )
        val sessionMapper = session.getMapper(ChatSessionMapper::class.java)
        val chat = sessionMapper.selectById(sessionId) ?: throw IllegalStateException("会话 `$sessionId` 不存在")
        val config = parseObject(chat.config)
        config.addProperty("coding_compacted_through_event_id", compactedThroughEventId)
        config.addProperty("coding_compaction_summary", summary)
        chat.config = config.toString()
        sessionMapper.updateById(chat)
        noticeId
    }

    fun failContextCompaction(compactionId: Long, error: String): Long = AgentDatabase.execute { session ->
        failContextCompaction(session, compactionId, error)
    }

    fun hasActiveMessageTasks(sessionId: Long): Boolean = AgentDatabase.execute { session ->
        session.getMapper(MessageProcessingTaskMapper::class.java).selectCount(
            QueryWrapper<MessageProcessingTaskEntity>()
                .eq("session_id", sessionId)
                .`in`("status", MessageTaskStatus.PENDING.value, MessageTaskStatus.PROCESSING.value),
        ) > 0
    }

    fun runningContextCompactionId(sessionId: Long): Long? = AgentDatabase.execute { session ->
        runningCompactionId(session, sessionId)
    }

    fun turnHasModelOutput(sessionId: Long, turnId: String): Boolean = AgentDatabase.execute { session ->
        session.getMapper(MessageEventMapper::class.java).selectList(
            QueryWrapper<MessageEventEntity>()
                .eq("session_id", sessionId)
                .eq("turn_id", turnId)
                .`in`("event_type", MessageEventType.MODEL_REASONING.value, MessageEventType.MODEL_REPLY.value, MessageEventType.TOOL_CALL.value),
        ).any { event ->
            if (event.eventType == MessageEventType.TOOL_CALL.value) return@any true
            val context = parseObject(event.context)
            listOf("text", "response", "reasoning", "reasoning_content").any { key ->
                context.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.isNotEmpty() == true
            }
        }
    }

    fun clearSession(sessionId: Long) = AgentDatabase.execute { session ->
        session.getMapper(MessageProcessingTaskMapper::class.java).delete(
            QueryWrapper<MessageProcessingTaskEntity>().eq("session_id", sessionId),
        )
        session.getMapper(MessageAppendItemMapper::class.java).delete(
            QueryWrapper<MessageAppendItemEntity>().eq("session_id", sessionId),
        )
        session.getMapper(MessageEventMapper::class.java).delete(
            QueryWrapper<MessageEventEntity>().eq("session_id", sessionId),
        )
        session.getMapper(MessageAttachmentMapper::class.java).delete(
            QueryWrapper<MessageAttachmentEntity>().eq("session_id", sessionId),
        )
        session.getMapper(ContextCompactionMapper::class.java).delete(
            QueryWrapper<ContextCompactionEntity>().eq("session_id", sessionId),
        )
        val mapper = session.getMapper(ChatSessionMapper::class.java)
        val entity = mapper.selectById(sessionId) ?: return@execute
        val json = com.google.gson.JsonParser.parseString(entity.config).asJsonObject
        json.add("token_usage", com.google.gson.JsonObject())
        json.remove("coding_compacted_through_event_id")
        json.remove("coding_compaction_summary")
        entity.config = json.toString()
        mapper.updateById(entity)
        Unit
    }

    fun deleteMessageTurn(sessionId: Long, turnId: String): Boolean = AgentDatabase.execute { session ->
        val eventMapper = session.getMapper(MessageEventMapper::class.java)
        val events = eventMapper.selectList(
            QueryWrapper<MessageEventEntity>().eq("session_id", sessionId).eq("turn_id", turnId),
        )
        if (events.isEmpty()) return@execute false
        session.getMapper(MessageProcessingTaskMapper::class.java).delete(
            QueryWrapper<MessageProcessingTaskEntity>().eq("session_id", sessionId).eq("turn_id", turnId),
        )
        session.getMapper(MessageAppendItemMapper::class.java).delete(
            QueryWrapper<MessageAppendItemEntity>().eq("session_id", sessionId).eq("turn_id", turnId),
        )
        eventMapper.delete(QueryWrapper<MessageEventEntity>().eq("session_id", sessionId).eq("turn_id", turnId))
        true
    }

    fun listAttachmentsByIds(sessionId: Long, ids: List<Long>): List<MessageAttachmentRecord> =
        AgentDatabase.execute { session ->
            if (ids.isEmpty()) return@execute emptyList()
            session.getMapper(MessageAttachmentMapper::class.java).selectList(
                QueryWrapper<MessageAttachmentEntity>()
                    .eq("session_id", sessionId)
                    .`in`("id", ids),
            ).map(::toAttachmentRecord)
        }

    fun createAttachment(
        sessionId: Long,
        fileName: String,
        contentType: String,
        size: Long,
        path: String,
        kind: String,
        textPreview: String?,
        metadata: JsonObject,
    ): Long = AgentDatabase.execute { session ->
        val entity = MessageAttachmentEntity().apply {
            this.sessionId = sessionId
            this.fileName = fileName
            this.contentType = contentType
            this.size = size
            this.path = path
            this.kind = kind
            this.textPreview = textPreview
            this.metadata = metadata.toString()
        }
        session.getMapper(MessageAttachmentMapper::class.java).insert(entity)
        entity.id ?: throw IllegalStateException("message_attachments 写入后未获得 id")
    }

    fun sessionConfig(sessionId: Long): JsonObject = AgentDatabase.execute { session ->
        val entity = session.getMapper(ChatSessionMapper::class.java).selectById(sessionId)
            ?: throw IllegalArgumentException("消息会话 `$sessionId` 不存在")
        parseObject(entity.config)
    }


    private fun eventsForRootIds(
        session: SqlSession,
        sessionId: Long,
        rootIds: List<Long>,
        includeContext: Boolean,
    ): List<MessageEventRecord> {
        if (rootIds.isEmpty()) return emptyList()
        val placeholders = rootIds.joinToString(",") { "?" }
        val columns = if (includeContext) {
            "id,parent_event_id,session_id,turn_id,status,event_type,event_id,summary,context,revision,created_at,updated_at"
        } else {
            "id,parent_event_id,session_id,turn_id,status,event_type,event_id,summary,revision,created_at,updated_at"
        }
        val sql = """
            select $columns from message_events
            where session_id=?
              and not (event_type='model_reply' and event_id='final')
              and (
                id in ($placeholders)
                or parent_event_id in ($placeholders)
                or (
                  event_type in ('user_message','append_message')
                  and turn_id in (select turn_id from message_events where session_id=? and id in ($placeholders))
                )
              )
            order by id
        """.trimIndent()
        return session.connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setLong(index++, sessionId)
            repeat(2) { rootIds.forEach { id -> statement.setLong(index++, id) } }
            statement.setLong(index++, sessionId)
            rootIds.forEach { id -> statement.setLong(index++, id) }
            statement.executeQuery().use { rows ->
                val events = mutableListOf<MessageEventRecord>()
                while (rows.next()) events += eventFromRow(rows, includeContext)
                events
            }
        }
    }

    private fun eventFromRow(rows: java.sql.ResultSet, includeContext: Boolean): MessageEventRecord {
        val contextIndex = if (includeContext) 9 else null
        val offset = if (includeContext) 1 else 0
        return MessageEventRecord(
            id = rows.getLong(1),
            parentEventId = rows.getLong(2).takeUnless { rows.wasNull() },
            sessionId = rows.getLong(3),
            turnId = rows.getString(4),
            status = MessageEventStatus.from(rows.getString(5)),
            eventType = MessageEventType.from(rows.getString(6)),
            eventId = rows.getString(7),
            summary = rows.getString(8),
            context = contextIndex?.let { index -> rows.getString(index) }?.let(::parseObject),
            revision = rows.getInt(9 + offset),
            createdAt = rows.getString(10 + offset),
            updatedAt = rows.getString(11 + offset),
        )
    }

    private fun insertEvent(session: SqlSession, event: NewMessageEvent): Long {
        val mapper = session.getMapper(MessageEventMapper::class.java)
        val existing = findEvent(session, event.sessionId, event.turnId, event.parentEventId, event.eventType, event.eventId)
        val context = eventContextWithTokenBudget(session, event.eventType, event.context)
        if (existing != null) {
            existing.status = event.status.value
            existing.summary = event.summary
            existing.context = context.toString()
            existing.revision += 1
            mapper.updateById(existing)
            touchSession(session, event.sessionId)
            return existing.id!!
        }
        val entity = MessageEventEntity().apply {
            parentEventId = event.parentEventId
            sessionId = event.sessionId
            turnId = event.turnId
            status = event.status.value
            eventType = event.eventType.value
            eventId = event.eventId
            summary = event.summary
            this.context = context.toString()
        }
        mapper.insert(entity)
        touchSession(session, event.sessionId)
        return entity.id ?: throw IllegalStateException("message_events 写入后未获得 id")
    }

    private fun findEvent(
        session: SqlSession,
        sessionId: Long,
        turnId: String,
        parentEventId: Long?,
        eventType: MessageEventType,
        eventId: String,
    ): MessageEventEntity? = session.getMapper(MessageEventMapper::class.java).selectList(
        QueryWrapper<MessageEventEntity>()
            .eq("session_id", sessionId)
            .eq("turn_id", turnId)
            .eq("event_type", eventType.value)
            .eq("event_id", eventId),
    ).firstOrNull { it.parentEventId == parentEventId }

    private fun eventContextWithTokenBudget(
        session: SqlSession,
        eventType: MessageEventType,
        value: JsonObject,
    ): JsonObject {
        val context = value.deepCopy()
        if (eventType == MessageEventType.MODEL_RETRY || eventType == MessageEventType.TASK_FAILED) {
            context.remove("estimated_tokens")
            return context
        }
        val ratio = historyTokenRatio()
        context.remove("estimated_tokens")
        val projected = context.toString()
        context.addProperty("estimated_tokens", MessageEventSupport.estimateHistoryTokens(projected, ratio))
        return context
    }

    private fun historyTokenRatio(): Double {
        val raw = runCatching { SettingService.setting("message.history_token_ratio") }.getOrNull()
        if (raw.isNullOrBlank()) return MessageEventSupport.DEFAULT_HISTORY_TOKEN_RATIO
        val ratio = raw.toDoubleOrNull() ?: throw IllegalArgumentException("全局消息历史 Token 比例必须是正数")
        require(ratio.isFinite() && ratio > 0.0) { "全局消息历史 Token 比例必须大于 0" }
        return ratio
    }

    private fun taskExecutionConfig(
        session: SqlSession,
        sessionId: Long,
        content: String,
        attachments: List<Long>,
        executionConfig: JsonObject,
    ): JsonObject {
        val config = executionConfig.deepCopy()
        config.add("_message", JsonObject().apply {
            addProperty("content", content)
            add("attachments", jsonArrayOf(attachments))
            add("attachment_items", attachmentItemsForIds(session, sessionId, attachments))
        })
        return config
    }

    private fun attachmentItemsForIds(session: SqlSession, sessionId: Long, ids: List<Long>): JsonArray {
        if (ids.isEmpty()) return JsonArray()
        val records = session.getMapper(MessageAttachmentMapper::class.java).selectList(
            QueryWrapper<MessageAttachmentEntity>().eq("session_id", sessionId).`in`("id", ids),
        )
        require(records.size == ids.size) { "消息包含无效附件" }
        return JsonArray().apply {
            records.sortedBy { ids.indexOf(it.id) }.forEach { record ->
                add(JsonObject().apply {
                    addProperty("id", record.id)
                    addProperty("file_name", record.fileName)
                    addProperty("content_type", record.contentType)
                    addProperty("size", record.size)
                    addProperty("kind", record.kind)
                    addProperty("path", record.path)
                    addProperty("created_at", record.createdAt.orEmpty())
                })
            }
        }
    }

    private fun countAttachments(session: SqlSession, sessionId: Long, ids: List<Long>): Long =
        session.getMapper(MessageAttachmentMapper::class.java).selectCount(
            QueryWrapper<MessageAttachmentEntity>().eq("session_id", sessionId).`in`("id", ids),
        )

    private fun touchSession(session: SqlSession, sessionId: Long) {
        session.getMapper(ChatSessionMapper::class.java).update(
            null,
            UpdateWrapper<ChatSessionEntity>().eq("id", sessionId).set("updated_at", nowText()),
        )
    }

    private fun toRecord(entity: MessageEventEntity, includeContext: Boolean): MessageEventRecord =
        MessageEventRecord(
            id = entity.id!!,
            parentEventId = entity.parentEventId,
            sessionId = entity.sessionId,
            turnId = entity.turnId,
            status = MessageEventStatus.from(entity.status),
            eventType = MessageEventType.from(entity.eventType),
            eventId = entity.eventId,
            summary = entity.summary,
            context = if (includeContext) parseObject(entity.context) else null,
            revision = entity.revision,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )

    private fun toAttachmentRecord(entity: MessageAttachmentEntity): MessageAttachmentRecord =
        MessageAttachmentRecord(
            id = entity.id!!,
            sessionId = entity.sessionId,
            fileName = entity.fileName,
            contentType = entity.contentType,
            size = entity.size,
            path = entity.path,
            kind = entity.kind,
            textPreview = entity.textPreview,
            metadata = parseObject(entity.metadata),
            createdAt = entity.createdAt,
        )

    private fun recoverContextCompactions(session: SqlSession) {
        val mapper = session.getMapper(ContextCompactionMapper::class.java)
        mapper.selectList(QueryWrapper<ContextCompactionEntity>().eq("status", "running")).forEach { entity ->
            failContextCompaction(session, entity.id ?: return@forEach, "服务重启时上下文压缩未完成")
        }
    }

    private fun failContextCompaction(session: SqlSession, compactionId: Long, error: String): Long {
        val mapper = session.getMapper(ContextCompactionMapper::class.java)
        val entity = mapper.selectById(compactionId) ?: throw IllegalStateException("压缩记录 `$compactionId` 不存在")
        if (entity.status != "running") return 0
        entity.status = "failed"
        entity.error = error
        entity.completedAt = nowText()
        mapper.updateById(entity)
        val sessionId = entity.sessionId
        val notice = JsonObject().apply {
            addProperty("summary", error)
            addProperty("content", error)
            addProperty("error", error)
            add("extra", JsonObject().apply {
                addProperty("type", "compaction_notice")
                addProperty("delivery_receiver", "user")
                addProperty("result", "failed")
            })
        }
        return insertEvent(
            session,
            NewMessageEvent(
                sessionId = sessionId,
                turnId = "compaction-$compactionId",
                status = MessageEventStatus.FAILED,
                eventType = MessageEventType.COMPACTION_NOTICE,
                eventId = "$compactionId-failed",
                summary = MessageEventSupport.latestSummary("上下文压缩失败：$error"),
                context = notice,
            ),
        )
    }

    private fun contextStatus(session: SqlSession, sessionId: Long): JsonObject {
        val config = sessionConfig(session, sessionId)
        val snapshot = config.get("model_snapshot")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val contextWindow = snapshot.get("context_window")?.takeIf { it.isJsonPrimitive }?.asLong
        val reservedTokens = reservedContextTokens(config)
        val historyTokens = remainingHistoryTokens(session, sessionId, compactedThroughEventId(config))
        val estimatedTokens = historyTokens + reservedTokens
        val percent = contextWindow?.takeIf { it > 0 }?.let { window ->
            kotlin.math.round((estimatedTokens.toDouble() / window.toDouble()) * 10000.0) / 100.0
        }
        val compactionId = runningCompactionId(session, sessionId)
        return JsonObject().apply {
            addProperty("session_id", sessionId)
            addProperty("session_type", "coding")
            addProperty("estimated_tokens", estimatedTokens)
            addProperty("history_tokens", historyTokens)
            addProperty("reserved_tokens", reservedTokens)
            if (contextWindow == null) add("context_window", com.google.gson.JsonNull.INSTANCE) else addProperty("context_window", contextWindow)
            if (percent == null) add("percent", com.google.gson.JsonNull.INSTANCE) else addProperty("percent", percent)
            addProperty("compacted_through_event_id", compactedThroughEventId(config))
            add("token_usage", config.get("token_usage")?.takeIf { it.isJsonObject } ?: JsonObject())
            if (compactionId == null) add("compaction_id", com.google.gson.JsonNull.INSTANCE) else addProperty("compaction_id", compactionId)
            addProperty("compaction_status", if (compactionId == null) "idle" else "running")
            config.get("coding_compaction_summary")?.takeIf { it.isJsonPrimitive }?.asString?.let { addProperty("compaction_summary", it) }
        }
    }

    private fun remainingHistoryTokens(session: SqlSession, sessionId: Long, boundary: Long): Long {
        val events = session.getMapper(MessageEventMapper::class.java).selectList(
            QueryWrapper<MessageEventEntity>()
                .eq("session_id", sessionId)
                .gt("id", boundary)
                .apply("ifnull(parent_event_id, 0) = 0")
                .`in`("event_type", MessageEventType.USER_MESSAGE.value, MessageEventType.APPEND_MESSAGE.value, MessageEventType.MODEL_REPLY.value, MessageEventType.TOOL_CALL.value, MessageEventType.COMPACTION_NOTICE.value),
        )
        return events.sumOf { event ->
            parseObject(event.context).get("estimated_tokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        }
    }

    private fun reservedContextTokens(config: JsonObject): Long {
        val summary = config.get("coding_compaction_summary")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (summary.isBlank()) return 0
        return MessageEventSupport.estimateHistoryTokens(
            com.lhstack.tools.agent.coding.CompactionTranscriptSupport.compactionSummaryPreamble(summary),
            historyTokenRatio(),
        )
    }

    private fun compactedThroughEventId(config: JsonObject): Long =
        config.get("coding_compacted_through_event_id")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private fun runningCompactionId(session: SqlSession, sessionId: Long): Long? =
        session.getMapper(ContextCompactionMapper::class.java).selectList(
            QueryWrapper<ContextCompactionEntity>()
                .eq("session_id", sessionId)
                .eq("status", "running")
                .orderByDesc("id")
                .last("limit 1"),
        ).firstOrNull()?.id

    private fun sessionConfig(session: SqlSession, sessionId: Long): JsonObject {
        val entity = session.getMapper(ChatSessionMapper::class.java).selectById(sessionId)
            ?: throw IllegalStateException("会话 `$sessionId` 不存在")
        return parseObject(entity.config)
    }

    private fun parseObject(raw: String): JsonObject {
        val parsed = JsonParser.parseString(raw)
        require(parsed.isJsonObject) { "JSON 必须是 object" }
        return parsed.asJsonObject
    }

    private fun parseLongArray(raw: String): List<Long> {
        val parsed = JsonParser.parseString(raw)
        require(parsed.isJsonArray) { "附件列表必须是 JSON array" }
        return parsed.asJsonArray.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asLong }
    }

    private fun jsonArrayOf(ids: List<Long>): JsonArray = JsonArray().apply { ids.forEach(::add) }

    private fun nowText(): String = LocalDateTime.now().toString().replace('T', ' ')
}
