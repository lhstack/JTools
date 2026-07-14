package com.lhstack.tools.agent.model.log

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.ModelRequestLogEntity
import com.lhstack.tools.db.mapper.ModelRequestLogMapper
import com.lhstack.tools.agent.model.params.ResolvedModelConfig
import java.time.LocalDateTime

/**
 * 模型日志上下文。完全照抄 awake-claw model_log.rs 的 ModelLogContext。
 *
 * source_type：日志来源（chat / direct_run / workflow 等）；source_id：来源标识；
 * agent_id：关联 Agent；message_type：蒸馏消息类型；request_snapshot：附加请求快照。
 */
data class ModelLogContext(
    val sourceType: String,
    val sourceId: String? = null,
    val agentId: Long? = null,
    val messageType: String? = null,
    val requestSnapshot: JsonElement? = null,
)

/**
 * 模型请求异常。对齐 awake 的 ModelRequestError：携带失败时已建好的 log_id，
 * 供上层定位日志。message 为底层错误信息。
 */
class ModelRequestException(
    val logId: Long,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 模型请求日志服务。完全照抄 awake-claw 的 model_log.rs + repository/logs.rs 里
 * create_model_request_log / finish_model_request_log / update_model_request_log_request_data，
 * 以及 create_model_log / request_log_data / finish_model_log_* 封装。
 *
 * 写入前统一经 ModelLogSupport 脱敏（内联 base64 占位、error 截断）。所有操作在
 * AgentDatabase.execute 事务边界内完成。
 */
object ModelLogService {

    /** 照抄 create_model_log：组装 request_data（含 request_snapshot）后建日志。 */
    fun createModelLog(
        model: ResolvedModelConfig,
        context: ModelLogContext,
        providerRequest: JsonElement,
    ): Long {
        val requestData = requestLogData(providerRequest, context.requestSnapshot)
        return createModelRequestLog(
            sourceType = context.sourceType,
            sourceId = context.sourceId,
            agentId = context.agentId,
            messageType = context.messageType,
            providerId = model.providerId,
            providerName = model.providerName,
            modelId = model.id,
            modelName = model.modelAlias,
            requestData = requestData,
        )
    }

    /** 照抄 request_log_data：有 snapshot 时并入 request_snapshot 字段。 */
    fun requestLogData(providerRequest: JsonElement, requestSnapshot: JsonElement?): JsonElement {
        if (requestSnapshot == null) {
            return providerRequest
        }
        val requestData = if (providerRequest.isJsonObject) {
            providerRequest.deepCopy().asJsonObject
        } else {
            JsonObject().apply { add("provider_request", providerRequest.deepCopy()) }
        }
        requestData.add("request_snapshot", requestSnapshot.deepCopy())
        return requestData
    }

    /** 照抄 create_model_request_log：status=running，started_at 写入当前时间。 */
    fun createModelRequestLog(
        sourceType: String,
        sourceId: String?,
        agentId: Long?,
        messageType: String?,
        providerId: Long?,
        providerName: String?,
        modelId: Long?,
        modelName: String?,
        requestData: JsonElement,
    ): Long = AgentDatabase.execute { session ->
        val simplified = ModelLogSupport.simplifyLogValue(requestData)
        val entity = ModelRequestLogEntity().apply {
            this.sourceType = sourceType
            this.sourceId = sourceId
            this.agentId = agentId
            this.messageType = messageType
            this.providerId = providerId
            this.providerName = providerName
            this.modelId = modelId
            this.modelName = modelName
            this.status = "running"
            this.requestData = simplified.toString()
            this.responseData = "{}"
            this.startedAt = nowText()
        }
        session.getMapper(ModelRequestLogMapper::class.java).insert(entity)
        entity.id ?: throw IllegalStateException("model_request_log 写入后未获得 id")
    }

    /** 照抄 finish_model_request_log：写 status/response_data/error_data/finished_at。 */
    fun finishModelRequestLog(id: Long, status: String, responseData: JsonElement, errorData: String?) {
        AgentDatabase.execute { session ->
            val simplified = ModelLogSupport.simplifyLogValue(responseData)
            val mapper = session.getMapper(ModelRequestLogMapper::class.java)
            mapper.update(
                null,
                UpdateWrapper<ModelRequestLogEntity>()
                    .eq("id", id)
                    .set("status", status)
                    .set("response_data", simplified.toString())
                    .set("error_data", errorData?.let { ModelLogSupport.truncateLogText(it) })
                    .set("finished_at", nowText()),
            )
            Unit
        }
    }

    /** 照抄 update_model_request_log_request_data。 */
    fun updateModelRequestLogRequestData(id: Long, requestData: JsonElement) {
        AgentDatabase.execute { session ->
            val simplified = ModelLogSupport.simplifyLogValue(requestData)
            val mapper = session.getMapper(ModelRequestLogMapper::class.java)
            mapper.update(
                null,
                UpdateWrapper<ModelRequestLogEntity>()
                    .eq("id", id)
                    .set("request_data", simplified.toString()),
            )
            Unit
        }
    }

    /** 照抄 finish_model_log_success。 */
    fun finishModelLogSuccess(logId: Long, body: JsonElement) {
        finishModelRequestLog(logId, "completed", body, null)
    }

    /** 照抄 finish_model_log_error。 */
    fun finishModelLogError(logId: Long, body: JsonElement, error: String) {
        finishModelRequestLog(logId, "failed", body, error)
    }

    fun finishModelLogCancelled(logId: Long, body: JsonElement) {
        finishModelRequestLog(logId, "cancelled", body, null)
    }

    /**
     * 会话历史一轮记录。以 model_request_logs 为唯一数据源展开会话对话卡片。
     * request/response 为原始 JSON（未脱敏字段已在写入时处理），交由 UI 渲染。
     */
    data class ChatTurn(
        val logId: Long,
        val status: String,
        val requestData: JsonObject,
        val responseData: JsonObject,
        val errorData: String?,
        val createdAt: String?,
        val messageType: String?,
    )

    data class ModelLogRecord(
        val id: Long,
        val sourceType: String,
        val sourceId: String?,
        val agentId: Long?,
        val messageType: String?,
        val providerName: String?,
        val modelName: String?,
        val status: String,
        val requestData: JsonObject,
        val responseData: JsonObject,
        val errorData: String?,
        val startedAt: String?,
        val finishedAt: String?,
        val createdAt: String?,
    )

    data class ModelLogPage(
        val rows: List<ModelLogRecord>,
        val total: Long,
        val page: Int,
        val pageSize: Int,
    )

    fun listModelLogs(limit: Int = 200): List<ModelLogRecord> = modelLogPage(1, limit.coerceIn(1, 1000)).rows

    fun modelLogPage(page: Int, pageSize: Int): ModelLogPage = AgentDatabase.execute { session ->
        val normalizedPage = page.coerceAtLeast(1)
        val normalizedPageSize = pageSize.coerceIn(1, 200)
        val offset = (normalizedPage - 1) * normalizedPageSize
        val mapper = session.getMapper(ModelRequestLogMapper::class.java)
        val total = mapper.selectCount(QueryWrapper<ModelRequestLogEntity>())
        val rows = mapper.selectList(
            QueryWrapper<ModelRequestLogEntity>()
                .orderByDesc("id")
                .last("limit $normalizedPageSize offset $offset"),
        ).map(::toModelLogRecord)
        ModelLogPage(rows, total, normalizedPage, normalizedPageSize)
    }

    fun modelLogPage(
        page: Int,
        pageSize: Int,
        status: String?,
        agentId: Long?,
        messageType: String?,
        keyword: String?,
    ): ModelLogPage = AgentDatabase.execute { session ->
        val normalizedPage = page.coerceAtLeast(1)
        val normalizedPageSize = pageSize.coerceIn(1, 200)
        val query = QueryWrapper<ModelRequestLogEntity>()
        status?.takeIf { it.isNotBlank() }?.let { query.eq("status", it) }
        agentId?.let { query.eq("agent_id", it) }
        messageType?.takeIf { it.isNotBlank() }?.let { query.eq("message_type", it) }
        keyword?.takeIf { it.isNotBlank() }?.let {
            query.and { nested -> nested.like("request_data", it).or().like("response_data", it).or().like("error_data", it) }
        }
        val mapper = session.getMapper(ModelRequestLogMapper::class.java)
        val total = mapper.selectCount(query)
        query.orderByDesc("id").last("limit $normalizedPageSize offset ${(normalizedPage - 1) * normalizedPageSize}")
        ModelLogPage(mapper.selectList(query).map(::toModelLogRecord), total, normalizedPage, normalizedPageSize)
    }

    fun deleteModelLogs(ids: Collection<Long>) = AgentDatabase.execute { session ->
        if (ids.isEmpty()) return@execute Unit
        val mapper = session.getMapper(ModelRequestLogMapper::class.java)
        val logs = mapper.selectBatchIds(ids)
        require(logs.none { it.messageType == "chat_turn" }) { "聊天对话日志属于会话记录，不能在模型日志中删除" }
        mapper.deleteBatchIds(ids)
        Unit
    }

    fun modelLogById(id: Long): ModelLogRecord? = AgentDatabase.execute { session ->
        session.getMapper(ModelRequestLogMapper::class.java).selectById(id)?.let(::toModelLogRecord)
    }

    fun listCompletedLogsForSource(
        sourceType: String,
        sourceId: String,
        afterId: Long?,
        messageTypes: Collection<String>,
    ): List<ModelLogRecord> = AgentDatabase.execute { session ->
        val query = QueryWrapper<ModelRequestLogEntity>()
            .eq("source_type", sourceType)
            .eq("source_id", sourceId)
            .eq("status", "completed")
            .orderByAsc("id")
        afterId?.let { query.gt("id", it) }
        if (messageTypes.isNotEmpty()) {
            query.`in`("message_type", messageTypes)
        }
        session.getMapper(ModelRequestLogMapper::class.java)
            .selectList(query)
            .map(::toModelLogRecord)
    }

    private fun toModelLogRecord(entity: ModelRequestLogEntity): ModelLogRecord = ModelLogRecord(
        id = entity.id ?: 0,
        sourceType = entity.sourceType,
        sourceId = entity.sourceId,
        agentId = entity.agentId,
        messageType = entity.messageType,
        providerName = entity.providerName,
        modelName = entity.modelName,
        status = entity.status,
        requestData = parseObject(entity.requestData),
        responseData = parseObject(entity.responseData),
        errorData = entity.errorData,
        startedAt = entity.startedAt,
        finishedAt = entity.finishedAt,
        createdAt = entity.createdAt,
    )

    /**
     * 按会话来源读取全部对话轮次，按 id 升序。
     * source_id 约定为 "$agentId:$sessionId"，source_type 固定为 chat。
     */
    fun listChatTurns(sourceType: String, sourceId: String): List<ChatTurn> = AgentDatabase.execute { session ->
        session.getMapper(ModelRequestLogMapper::class.java)
            .selectList(
                QueryWrapper<ModelRequestLogEntity>()
                    .eq("source_type", sourceType)
                    .eq("source_id", sourceId)
                    .orderByAsc("id"),
            )
            .map { entity ->
                ChatTurn(
                    logId = entity.id ?: 0,
                    status = entity.status,
                    requestData = parseObject(entity.requestData),
                    responseData = parseObject(entity.responseData),
                    errorData = entity.errorData,
                    createdAt = entity.createdAt,
                    messageType = entity.messageType,
                )
            }
    }

    fun deleteModelLog(id: Long) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(ModelRequestLogMapper::class.java)
        val log = mapper.selectById(id) ?: return@execute Unit
        require(log.messageType != "chat_turn") { "聊天对话日志属于会话记录，不能在模型日志中删除" }
        mapper.deleteById(id)
        Unit
    }

    /** 删除单个会话对话轮次（同一条日志内含用户消息与模型回复）。仅供会话内删除使用，允许删除 chat_turn。 */
    fun deleteChatTurn(logId: Long) = AgentDatabase.execute { session ->
        session.getMapper(ModelRequestLogMapper::class.java).deleteById(logId)
        Unit
    }

    /** 删除某会话来源的全部日志（清空会话历史 / 删除会话时使用）。 */
    fun deleteChatTurns(sourceType: String, sourceId: String) = AgentDatabase.execute { session ->
        session.getMapper(ModelRequestLogMapper::class.java)
            .delete(
                QueryWrapper<ModelRequestLogEntity>()
                    .eq("source_type", sourceType)
                    .eq("source_id", sourceId),
            )
        Unit
    }

    private fun parseObject(text: String?): JsonObject {
        val value = text?.takeIf { it.isNotBlank() } ?: return JsonObject()
        return runCatching { JsonParser.parseString(value).asJsonObject }.getOrDefault(JsonObject())
    }

    private fun nowText(): String = LocalDateTime.now().toString().replace('T', ' ')
}
