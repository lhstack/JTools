package com.lhstack.tools.db.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.config.AgentCapabilityConfig
import com.lhstack.tools.db.config.AgentDistillConfig
import com.lhstack.tools.db.config.AgentPersonaConfig
import com.lhstack.tools.db.config.AgentRuntimeConfig
import com.lhstack.tools.db.entity.AgentEntity
import com.lhstack.tools.db.mapper.AgentMapper

/**
 * Agent 领域记录。对齐 awake-claw 的 AgentRecord：把 agents 表行 + 三个 JSON 配置
 * 解析成结构化对象，屏蔽 entity 的 JSON 文本细节。
 */
data class AgentRecord(
    val id: Long?,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val providerId: Long?,
    val modelId: Long?,
    val promptId: Long?,
    val extraPrompt: String?,
    val runtimeParams: AgentRuntimeConfig,
    val extConfig: AgentCapabilityConfig,
    val distillConfig: AgentDistillConfig,
    val outputMode: String,
    val maxRuntimeSecs: Long?,
    val tags: List<String>,
    val createdAt: String?,
    val updatedAt: String?,
)

/**
 * Agent 持久化服务。对齐 awake-claw repository/agent.rs 的
 * list_agents / agent_by_id / agent_by_name / upsert_agent / delete_agent。
 * 所有操作在 AgentDatabase.execute 的事务边界内完成。
 *
 * JSON 配置的解析/序列化用 Gson；校验规则（名称、字符上限、蒸馏类型、
 * persona 长度、历史消息数）照抄 awake 的 upsert_agent。
 */
object AgentService {

    private val gson = Gson()
    private val stringListType = com.google.gson.reflect.TypeToken
        .getParameterized(List::class.java, String::class.java).type

    // -------- queries --------

    /** 照抄 list_agents：按 updated_at desc, id desc 排序。 */
    fun listAgents(): List<AgentRecord> = AgentDatabase.execute { session ->
        session.getMapper(AgentMapper::class.java)
            .selectList(QueryWrapper<AgentEntity>().orderByDesc("updated_at", "id"))
            .map { toRecord(it) }
    }

    fun agentById(id: Long): AgentRecord? = AgentDatabase.execute { session ->
        session.getMapper(AgentMapper::class.java).selectById(id)?.let { toRecord(it) }
    }

    fun agentByName(name: String): AgentRecord? = AgentDatabase.execute { session ->
        session.getMapper(AgentMapper::class.java)
            .selectOne(QueryWrapper<AgentEntity>().eq("name", name).last("limit 1"))
            ?.let { toRecord(it) }
    }

    // -------- upsert --------

    /** 照抄 upsert_agent：先校验再写入，返回 id。 */
    fun upsertAgent(agent: AgentRecord): Long = AgentDatabase.execute { session ->
        validate(agent)
        val mapper = session.getMapper(AgentMapper::class.java)
        val entity = toEntity(agent)
        if (agent.id == null) {
            mapper.insert(entity)
        } else {
            mapper.updateById(entity)
        }
        entity.id ?: agent.id ?: throw IllegalStateException("Agent 写入后未获得 id")
    }

    fun deleteAgent(id: Long) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(AgentMapper::class.java)
        if (mapper.selectById(id) == null) {
            throw IllegalArgumentException("Agent `$id` 不存在")
        }
        mapper.deleteById(id)
        Unit
    }

    fun updateDistilledPersona(id: Long, persona: AgentPersonaConfig, lastLogId: Long) {
        val current = agentById(id) ?: throw IllegalArgumentException("Agent `$id` 不存在")
        upsertAgent(
            current.copy(
                extConfig = current.extConfig.copy(persona = persona),
                distillConfig = current.distillConfig.copy(lastDistilledModelLogId = lastLogId),
            )
        )
    }

    fun updateDistillLastModelLogId(id: Long, lastLogId: Long) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(AgentMapper::class.java)
        val entity = mapper.selectById(id) ?: throw IllegalArgumentException("Agent `$id` 不存在")
        val current = parseConfig(entity.distillConfig, AgentDistillConfig::class.java, AgentDistillConfig())
        entity.distillConfig = gson.toJson(current.copy(lastDistilledModelLogId = lastLogId))
        mapper.updateById(entity)
        Unit
    }

    // -------- validation (照抄 upsert_agent 的校验) --------

    private fun validate(agent: AgentRecord) {
        validateName(agent.name)
        if ((agent.extraPrompt?.length ?: 0) > 512) {
            throw IllegalArgumentException("Agent 扩展提示信息不能超过 512 个字符")
        }
        agent.distillConfig.validateMessageTypes()
        if ((agent.distillConfig.extraPrompt?.length ?: 0) > 1024) {
            throw IllegalArgumentException("Agent 蒸馏附加提示词不能超过 1024 个字符")
        }
        validatePersonaLengths(agent.extConfig.persona)
        if ((agent.runtimeParams.maxHistoryMessages ?: 1) <= 0) {
            throw IllegalArgumentException("Agent 历史最大对话轮数必须大于 0；不限制时请留空")
        }
        if ((agent.runtimeParams.toolCallRetentionRounds ?: 0) < 0) {
            throw IllegalArgumentException("Agent 工具调用保留轮次不能小于 0；不限制时请留空")
        }
    }

    private fun validateName(name: String) {
        if (name.isBlank()) {
            throw IllegalArgumentException("Agent 名称不能为空")
        }
    }

    /** 照抄 validate_agent_persona_lengths。 */
    private fun validatePersonaLengths(persona: AgentPersonaConfig) {
        val fields = listOf(
            Triple("专业记忆", persona.memory, persona.memoryMaxChars),
            Triple("工作方法", persona.behaviorHabits, persona.behaviorHabitsMaxChars),
            Triple("角色设定", persona.soul, persona.soulMaxChars),
            Triple("能力画像", persona.profile, persona.profileMaxChars),
            Triple("边界约束", persona.guardrails, persona.guardrailsMaxChars),
        )
        for ((label, value, maxChars) in fields) {
            if (maxChars > 0 && value.length > maxChars) {
                throw IllegalArgumentException("Agent 人格配置 `$label` 不能超过 $maxChars 个字符")
            }
        }
    }

    // -------- mapping --------

    private fun toRecord(entity: AgentEntity): AgentRecord = AgentRecord(
        id = entity.id,
        name = entity.name,
        description = entity.description,
        enabled = entity.enabled != 0,
        providerId = entity.providerId,
        modelId = entity.modelId,
        promptId = entity.promptId,
        extraPrompt = entity.extraPrompt,
        runtimeParams = parseConfig(entity.runtimeParams, AgentRuntimeConfig::class.java, AgentRuntimeConfig()),
        extConfig = parseConfig(entity.extConfig, AgentCapabilityConfig::class.java, AgentCapabilityConfig()),
        // 照抄 canonicalized：读出时规范化蒸馏类型
        distillConfig = parseConfig(entity.distillConfig, AgentDistillConfig::class.java, AgentDistillConfig())
            .canonicalized(),
        outputMode = entity.outputMode.ifBlank { "text" },
        maxRuntimeSecs = entity.maxRuntimeSecs,
        tags = parseStringList(entity.tags),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    private fun toEntity(agent: AgentRecord): AgentEntity = AgentEntity().apply {
        id = agent.id
        name = agent.name
        description = agent.description?.takeIf { it.isNotBlank() }
        enabled = if (agent.enabled) 1 else 0
        providerId = agent.providerId
        modelId = agent.modelId
        promptId = agent.promptId
        extraPrompt = agent.extraPrompt?.takeIf { it.isNotBlank() }
        runtimeParams = gson.toJson(agent.runtimeParams)
        extConfig = gson.toJson(agent.extConfig)
        distillConfig = gson.toJson(agent.distillConfig)
        outputMode = agent.outputMode.trim().ifBlank { "text" }
        maxRuntimeSecs = agent.maxRuntimeSecs
        tags = gson.toJson(agent.tags)
    }

    private fun <T> parseConfig(json: String, clazz: Class<T>, fallback: T): T =
        try {
            gson.fromJson(json, clazz) ?: fallback
        } catch (_: Throwable) {
            fallback
        }

    private fun parseStringList(json: String): List<String> =
        try {
            gson.fromJson<List<String>>(json, stringListType) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
}
