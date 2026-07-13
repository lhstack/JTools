package com.lhstack.tools.db.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.ChatSessionEntity
import com.lhstack.tools.db.mapper.ChatSessionMapper

/**
 * 会话领域记录。会话只承载「会话名 + 关联 Agent」。请求与响应历史全部来自
 * model_request_logs（source_type = "agent"，source_id = "$agentId:$sessionId"），
 * 会话本身不存消息与附件。
 */
data class ChatSessionRecord(
    val id: Long,
    val title: String,
    val agentId: Long?,
    val createdAt: String?,
    val updatedAt: String?,
)

/**
 * 会话持久化服务。对齐 awake-claw session.rs 的 list/create/rename/delete 语义，
 * 但按 idea-tools 场景精简：会话只与一个 Agent 绑定，不存 provider/model 快照，
 * 消息历史交由 model_request_logs 承载。所有操作在 AgentDatabase.execute 事务内完成。
 */
object ChatSessionService {

    /** 会话 log 的 source_type。 */
    const val SESSION_SOURCE_TYPE: String = "agent"

    /** 会话 log 的 source_id：agentId:sessionId。缺少 agent 时用会话 id 兜底定位。 */
    fun sessionSourceId(agentId: Long?, sessionId: Long): String = "${agentId ?: 0}:$sessionId"

    /** 按 updated_at desc, id desc 列出全部会话。 */
    fun listSessions(): List<ChatSessionRecord> = AgentDatabase.execute { session ->
        session.getMapper(ChatSessionMapper::class.java)
            .selectList(QueryWrapper<ChatSessionEntity>().orderByDesc("updated_at", "id"))
            .map(::toRecord)
    }

    fun sessionById(id: Long): ChatSessionRecord? = AgentDatabase.execute { session ->
        session.getMapper(ChatSessionMapper::class.java).selectById(id)?.let(::toRecord)
    }

    /** 新建会话，绑定 Agent，返回持久化后的记录。 */
    fun createSession(title: String, agentId: Long?): ChatSessionRecord = AgentDatabase.execute { session ->
        val normalizedTitle = title.trim().ifBlank { "新会话" }
        val entity = ChatSessionEntity().apply {
            this.title = normalizedTitle
            this.agentId = agentId
        }
        session.getMapper(ChatSessionMapper::class.java).insert(entity)
        val id = entity.id ?: throw IllegalStateException("chat_session 写入后未获得 id")
        toRecord(entity.also { it.id = id })
    }

    /** 重命名会话。 */
    fun renameSession(id: Long, title: String) = AgentDatabase.execute { session ->
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotBlank()) { "会话名称不能为空" }
        val mapper = session.getMapper(ChatSessionMapper::class.java)
        val entity = mapper.selectById(id) ?: throw IllegalArgumentException("会话 `$id` 不存在")
        entity.title = normalizedTitle
        mapper.updateById(entity)
        Unit
    }

    /** 切换会话绑定的 Agent。 */
    fun updateSessionAgent(id: Long, agentId: Long?) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(ChatSessionMapper::class.java)
        val entity = mapper.selectById(id) ?: throw IllegalArgumentException("会话 `$id` 不存在")
        entity.agentId = agentId
        mapper.updateById(entity)
        Unit
    }

    fun deleteSession(id: Long) = AgentDatabase.execute { session ->
        session.getMapper(ChatSessionMapper::class.java).deleteById(id)
        Unit
    }

    private fun toRecord(entity: ChatSessionEntity): ChatSessionRecord = ChatSessionRecord(
        id = entity.id ?: 0,
        title = entity.title.orEmpty(),
        agentId = entity.agentId,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )
}
