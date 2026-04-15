package com.lhstack.tools.agent

import com.lhstack.tools.plugins.PluginState
import java.util.UUID

data class AgentSkillAddInput(
    val name: String,
    val description: String,
    val skillContent: String,
    val enabledByDefault: Boolean = false,
    val resources: List<AgentSkillResourceDraft> = emptyList(),
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

    fun deleteSkill(state: PluginState.State, skillIdOrName: String): AgentFunctionResult<Map<String, Any?>> {
        val skill = findSkill(state, skillIdOrName, skillIdOrName)
            ?: return AgentFunctionResult(false, error = "未找到技能")
        state.agentSkills.removeIf { it.id == skill.id }
        state.agentSessions.forEach { session ->
            session.enabledSkillIds.removeIf { it == skill.id }
        }
        return AgentFunctionResult(true, summarize(skill))
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
