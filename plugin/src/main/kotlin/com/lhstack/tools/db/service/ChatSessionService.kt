package com.lhstack.tools.db.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.ChatSessionEntity
import com.lhstack.tools.db.mapper.ChatSessionMapper
import java.io.File

enum class ChatSessionType(val value: String) {
    PROJECT("project"),
    GLOBAL("global");

    companion object {
        fun from(value: String): ChatSessionType = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("会话类型仅支持 project、global")
    }
}

data class ChatSessionRecord(
    val id: Long,
    val title: String,
    val agentId: Long?,
    val sessionType: ChatSessionType,
    val projectPath: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

object ChatSessionService {
    const val SESSION_SOURCE_TYPE: String = "agent"

    fun sessionSourceId(agentId: Long?, sessionId: Long): String = "${agentId ?: 0}:$sessionId"

    /** Lists global sessions and sessions belonging to the current project. */
    fun listVisibleSessions(projectPath: String): List<ChatSessionRecord> {
        val normalizedProjectPath = normalizeProjectPath(projectPath)
        return AgentDatabase.execute { session ->
            session.getMapper(ChatSessionMapper::class.java)
                .selectList(
                    QueryWrapper<ChatSessionEntity>()
                        .and { scope ->
                            scope.eq("session_type", ChatSessionType.GLOBAL.value)
                                .or { project ->
                                    project.eq("session_type", ChatSessionType.PROJECT.value)
                                        .eq("project_path", normalizedProjectPath)
                                }
                        }
                        .orderByDesc("updated_at", "id")
                )
                .map(::toRecord)
        }
    }

    /** Management-only unscoped list. Chat UI must use listVisibleSessions. */
    fun listSessions(): List<ChatSessionRecord> = AgentDatabase.execute { session ->
        session.getMapper(ChatSessionMapper::class.java)
            .selectList(QueryWrapper<ChatSessionEntity>().orderByDesc("updated_at", "id"))
            .map(::toRecord)
    }

    fun sessionById(id: Long): ChatSessionRecord? = AgentDatabase.execute { session ->
        session.getMapper(ChatSessionMapper::class.java).selectById(id)?.let(::toRecord)
    }

    fun visibleSessionById(id: Long, projectPath: String): ChatSessionRecord? =
        sessionById(id)?.takeIf { it.isVisibleIn(projectPath) }

    fun createSession(
        title: String,
        agentId: Long?,
        sessionType: ChatSessionType,
        projectPath: String?,
    ): ChatSessionRecord = AgentDatabase.execute { session ->
        val normalizedProjectPath = when (sessionType) {
            ChatSessionType.PROJECT -> normalizeProjectPath(requireNotNull(projectPath) {
                "项目级会话必须关联项目路径"
            })
            ChatSessionType.GLOBAL -> null
        }
        val entity = ChatSessionEntity().apply {
            this.title = title.trim().ifBlank { "新会话" }
            this.agentId = agentId
            this.sessionType = sessionType.value
            this.projectPath = normalizedProjectPath
        }
        session.getMapper(ChatSessionMapper::class.java).insert(entity)
        val id = entity.id ?: throw IllegalStateException("chat_session 写入后未获得 id")
        toRecord(entity.also { it.id = id })
    }

    fun renameSession(id: Long, title: String) = AgentDatabase.execute { session ->
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotBlank()) { "会话名称不能为空" }
        val mapper = session.getMapper(ChatSessionMapper::class.java)
        val entity = mapper.selectById(id) ?: throw IllegalArgumentException("会话 `$id` 不存在")
        entity.title = normalizedTitle
        mapper.updateById(entity)
        Unit
    }

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

    fun normalizeProjectPath(projectPath: String): String {
        require(projectPath.isNotBlank()) { "项目路径不能为空" }
        return File(projectPath).canonicalFile.invariantSeparatorsPath
    }

    private fun ChatSessionRecord.isVisibleIn(projectPath: String): Boolean = when (sessionType) {
        ChatSessionType.GLOBAL -> true
        ChatSessionType.PROJECT -> this.projectPath == normalizeProjectPath(projectPath)
    }

    private fun toRecord(entity: ChatSessionEntity): ChatSessionRecord = ChatSessionRecord(
        id = entity.id ?: 0,
        title = entity.title.orEmpty(),
        agentId = entity.agentId,
        sessionType = ChatSessionType.from(entity.sessionType),
        projectPath = entity.projectPath,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )
}
