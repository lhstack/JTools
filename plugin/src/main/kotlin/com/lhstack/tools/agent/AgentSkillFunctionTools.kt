package com.lhstack.tools.agent

import com.lhstack.tools.plugins.PluginState
import java.util.UUID

enum class AgentSkillEnableScope(val id: String) {
    DEFAULT("default"),
    SESSION("session");

    companion object {
        fun fromId(id: String?): AgentSkillEnableScope {
            return entries.firstOrNull { it.id == id } ?: SESSION
        }
    }
}

data class AgentSkillAddInput(
    val name: String,
    val description: String,
    val skillContent: String,
    val enabledByDefault: Boolean = false,
    val resources: List<AgentSkillResourceDraft> = emptyList(),
)

data class AgentSkillUpdateInput(
    val skillId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val skillContent: String? = null,
    val enabledByDefault: Boolean? = null,
    val resources: List<AgentSkillResourceDraft>? = null,
)

object AgentSkillFunctionTools {

    fun addSkill(state: PluginState.State, input: AgentSkillAddInput): AgentFunctionResult<Map<String, Any?>> {
        val name = input.name.trim()
        if (name.isBlank()) {
            return AgentFunctionResult(false, error = "name 不能为空")
        }
        if (state.agentSkills.any { it.name == name }) {
            return AgentFunctionResult(false, error = "技能已存在: $name")
        }
        val skill = AgentSkillSupport.normalizeSkill(AgentSkillState().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            this.description = input.description
            this.skillContent = input.skillContent
            this.enabledByDefault = input.enabledByDefault
            this.sourceType = AgentSkillSourceType.MANUAL.id
            this.resources = input.resources.map {
                AgentSkillResourceState().apply {
                    path = it.path
                    content = it.content
                }
            }.toMutableList()
        }) ?: return AgentFunctionResult(false, error = "技能数据无效")
        state.agentSkills.add(skill)
        return AgentFunctionResult(true, summarize(skill))
    }

    fun updateSkill(state: PluginState.State, input: AgentSkillUpdateInput): AgentFunctionResult<Map<String, Any?>> {
        val skill = findSkill(state, input.skillId, input.name)
            ?: return AgentFunctionResult(false, error = "未找到技能")
        input.name?.let { updated ->
            val normalized = updated.trim()
            if (normalized.isBlank()) {
                return AgentFunctionResult(false, error = "name 不能为空")
            }
            if (state.agentSkills.any { it.id != skill.id && it.name == normalized }) {
                return AgentFunctionResult(false, error = "技能已存在: $normalized")
            }
            skill.name = normalized
        }
        input.description?.let { skill.description = it }
        input.skillContent?.let { skill.skillContent = it }
        input.enabledByDefault?.let { skill.enabledByDefault = it }
        input.resources?.let { resources ->
            skill.resources = resources.map { resource ->
                AgentSkillResourceState().apply {
                    path = resource.path
                    content = resource.content
                }
            }.toMutableList()
        }
        AgentSkillSupport.normalizeSkill(skill)
        return AgentFunctionResult(true, summarize(skill))
    }

    fun deleteSkill(state: PluginState.State, skillIdOrName: String): AgentFunctionResult<Map<String, Any?>> {
        val skill = findSkill(state, skillIdOrName, skillIdOrName)
            ?: return AgentFunctionResult(false, error = "未找到技能")
        state.agentSkills.removeIf { it.id == skill.id }
        state.agentSessions.forEach { session ->
            session.enabledSkillIds.removeIf { it == skill.id }
        }
        return AgentFunctionResult(true, summarize(skill))
    }

    fun setEnabled(
        state: PluginState.State,
        scope: AgentSkillEnableScope,
        skillId: String? = null,
        name: String? = null,
        enabled: Boolean,
        projectKey: String? = null,
        sessionId: String? = null,
    ): AgentFunctionResult<Map<String, Any?>> {
        val skill = findSkill(state, skillId, name) ?: return AgentFunctionResult(false, error = "未找到技能")
        when (scope) {
            AgentSkillEnableScope.DEFAULT -> skill.enabledByDefault = enabled
            AgentSkillEnableScope.SESSION -> {
                val session = findSession(state, projectKey, sessionId)
                    ?: return AgentFunctionResult(false, error = "未找到会话")
                if (enabled) {
                    if (!session.enabledSkillIds.contains(skill.id)) {
                        session.enabledSkillIds.add(skill.id)
                    }
                } else {
                    session.enabledSkillIds.removeIf { it == skill.id }
                }
                return AgentFunctionResult(
                    true,
                    mapOf(
                        "scope" to scope.id,
                        "skillId" to skill.id,
                        "skillName" to skill.name,
                        "enabled" to enabled,
                        "sessionId" to session.id
                    )
                )
            }
        }
        return AgentFunctionResult(
            true,
            mapOf(
                "scope" to scope.id,
                "skillId" to skill.id,
                "skillName" to skill.name,
                "enabled" to enabled
            )
        )
    }

    fun listSkills(state: PluginState.State): List<Map<String, Any?>> {
        return AgentSkillSupport.normalizeSkills(state.agentSkills).map { summarize(it) }
    }

    private fun findSkill(state: PluginState.State, skillId: String?, name: String?): AgentSkillState? {
        val normalizedId = skillId?.trim().orEmpty()
        val normalizedName = name?.trim().orEmpty()
        return state.agentSkills.firstOrNull { skill ->
            (normalizedId.isNotBlank() && skill.id == normalizedId) ||
                (normalizedName.isNotBlank() && skill.name == normalizedName)
        }
    }

    private fun findSession(state: PluginState.State, projectKey: String?, sessionId: String?): AgentSessionState? {
        val normalizedSessionId = sessionId?.trim().orEmpty()
        if (normalizedSessionId.isNotBlank()) {
            return state.agentSessions.firstOrNull { it.id == normalizedSessionId }
        }
        val normalizedProjectKey = projectKey?.trim().orEmpty()
        if (normalizedProjectKey.isBlank()) {
            return null
        }
        val activeId = state.agentActiveSessionIdByProject[normalizedProjectKey]
            ?: state.agentActiveSessionId.takeIf { it.isNotBlank() }
        return state.agentSessions.firstOrNull { it.id == activeId && it.projectKey == normalizedProjectKey }
            ?: state.agentSessions.firstOrNull { it.projectKey == normalizedProjectKey }
    }

    private fun summarize(skill: AgentSkillState): Map<String, Any?> {
        return mapOf(
            "id" to skill.id,
            "name" to skill.name,
            "description" to skill.description,
            "sourceType" to skill.sourceType,
            "enabledByDefault" to skill.enabledByDefault,
            "resourceCount" to skill.resources.size
        )
    }
}
