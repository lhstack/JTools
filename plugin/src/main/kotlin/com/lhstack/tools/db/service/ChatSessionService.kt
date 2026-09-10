package com.lhstack.tools.db.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.google.gson.JsonObject
import com.lhstack.tools.agent.coding.CodingSessionSupport
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.ChatSessionEntity
import com.lhstack.tools.db.mapper.ChatSessionMapper

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
    val codingEnvironmentId: Long,
    val agentId: Long,
    val sessionType: ChatSessionType,
    val projectPath: String?,
    val cwd: String,
    val providerId: Long,
    val modelId: Long,
    val promptId: Long?,
    val modelSnapshot: JsonObject,
    val reasoningLevel: String?,
    val reasoningConfig: JsonObject?,
    val config: JsonObject,
    val createdAt: String?,
    val updatedAt: String?,
)

object ChatSessionService {
    const val SESSION_SOURCE_TYPE: String = "agent"

    fun sessionSourceId(agentId: Long?, sessionId: Long): String = "${agentId ?: 0}:$sessionId"

    fun listVisibleSessions(projectPath: String): List<ChatSessionRecord> {
        val normalizedProjectPath = normalizeProjectPath(projectPath)
        return listCodingSessions().filter { it.isVisibleIn(normalizedProjectPath) }
    }

    fun listSessions(): List<ChatSessionRecord> = listCodingSessions()

    fun sessionById(id: Long): ChatSessionRecord? = AgentDatabase.execute { session ->
        session.getMapper(ChatSessionMapper::class.java).selectById(id)?.let(::toRecord)
    }

    fun visibleSessionById(id: Long, projectPath: String): ChatSessionRecord? =
        sessionById(id)?.takeIf { it.isVisibleIn(normalizeProjectPath(projectPath)) }

    fun createSession(
        title: String,
        codingEnvironmentId: Long,
        sessionType: ChatSessionType,
        workspacePath: String,
        agentId: Long? = null,
        providerId: Long? = null,
        modelId: Long? = null,
        promptId: Long? = null,
    ): ChatSessionRecord {
        val config = CodingSessionSupport.buildConfig(
            CodingSessionSupport.CreateInput(
                title = title,
                visibility = sessionType,
                workspacePath = workspacePath,
                codingEnvironmentId = codingEnvironmentId,
                agentId = agentId,
                providerId = providerId,
                modelId = modelId,
                promptId = promptId,
            ),
        )
        return AgentDatabase.execute { session ->
            val entity = ChatSessionEntity().apply {
                this.sessionType = CodingSessionSupport.MESSAGE_SESSION_TYPE
                this.title = title.trim()
                this.config = config.toString()
            }
            session.getMapper(ChatSessionMapper::class.java).insert(entity)
            val id = entity.id ?: throw IllegalStateException("message_session 写入后未获得 id")
            toRecord(entity.also { it.id = id })
        }
    }

    fun renameSession(id: Long, title: String) = AgentDatabase.execute { session ->
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotBlank()) { "会话名称不能为空" }
        require(normalizedTitle.length <= 80) { "会话名称不能超过 80 个字符" }
        val mapper = session.getMapper(ChatSessionMapper::class.java)
        val entity = mapper.selectById(id) ?: throw IllegalArgumentException("会话 `$id` 不存在")
        entity.title = normalizedTitle
        mapper.updateById(entity)
        Unit
    }

    fun updateSessionSettings(
        id: Long,
        providerId: Long,
        modelId: Long,
        promptId: Long? = null,
        modelSnapshot: JsonObject? = null,
        reasoningLevel: String? = null,
        reasoningConfig: JsonObject? = null,
        maxHistoryRounds: Int? = null,
    ): ChatSessionRecord = AgentDatabase.execute { session ->
        val mapper = session.getMapper(ChatSessionMapper::class.java)
        val entity = mapper.selectById(id) ?: throw IllegalArgumentException("会话 `$id` 不存在")
        val next = CodingSessionSupport.applyModelSettings(
            parsedToObject(entity.config),
            CodingSessionSupport.ModelSettingsInput(
                providerId = providerId,
                modelId = modelId,
                promptId = promptId,
                modelSnapshot = modelSnapshot,
                reasoningLevel = reasoningLevel,
                reasoningConfig = reasoningConfig,
                maxHistoryRounds = maxHistoryRounds,
            ),
        )
        entity.config = next.toString()
        mapper.updateById(entity)
        toRecord(entity)
    }

    fun updateSessionCodingEnvironment(id: Long, environmentId: Long) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(ChatSessionMapper::class.java)
        val entity = mapper.selectById(id) ?: throw IllegalArgumentException("会话 `$id` 不存在")
        entity.config = CodingSessionSupport.replaceCodingEnvironment(parsedToObject(entity.config), environmentId).toString()
        mapper.updateById(entity)
        Unit
    }

    fun updateSessionAgent(id: Long, agentId: Long) = AgentDatabase.execute { session ->
        AgentService.agentById(agentId) ?: throw IllegalArgumentException("Agent `$agentId` 不存在")
        val mapper = session.getMapper(ChatSessionMapper::class.java)
        val entity = mapper.selectById(id) ?: throw IllegalArgumentException("会话 `$id` 不存在")
        entity.config = CodingSessionSupport.replaceAgent(parsedToObject(entity.config), agentId).toString()
        mapper.updateById(entity)
        Unit
    }

    fun deleteSession(id: Long) {
        MessageStoreService.clearSession(id)
        AgentDatabase.execute { session ->
            val changed = session.getMapper(ChatSessionMapper::class.java).deleteById(id)
            require(changed > 0) { "会话 `$id` 不存在" }
            Unit
        }
    }

    fun normalizeProjectPath(projectPath: String): String = CodingSessionSupport.canonicalizePath(projectPath)

    private fun listCodingSessions(): List<ChatSessionRecord> = AgentDatabase.execute { session ->
        session.getMapper(ChatSessionMapper::class.java)
            .selectList(
                QueryWrapper<ChatSessionEntity>()
                    .eq("session_type", CodingSessionSupport.MESSAGE_SESSION_TYPE)
                    .orderByDesc("updated_at", "id"),
            )
            .map(::toRecord)
    }

    private fun ChatSessionRecord.isVisibleIn(projectPath: String): Boolean = when (sessionType) {
        ChatSessionType.GLOBAL -> true
        ChatSessionType.PROJECT -> this.projectPath == projectPath
    }

    private fun parsedToObject(configText: String): JsonObject =
        com.google.gson.JsonParser.parseString(configText).asJsonObject

    private fun toRecord(entity: ChatSessionEntity): ChatSessionRecord {
        val id = entity.id ?: 0
        val parsed = CodingSessionSupport.parseConfig(entity.config, id)
        return ChatSessionRecord(
            id = id,
            title = entity.title,
            codingEnvironmentId = parsed.codingEnvironmentId,
            agentId = parsed.agentId,
            sessionType = parsed.visibility,
            projectPath = parsed.projectPath,
            cwd = parsed.cwd,
            providerId = parsed.providerId,
            modelId = parsed.modelId,
            promptId = parsed.promptId,
            modelSnapshot = parsed.modelSnapshot,
            reasoningLevel = parsed.reasoningLevel,
            reasoningConfig = parsed.reasoningConfig,
            config = parsedToObject(entity.config),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }
}
