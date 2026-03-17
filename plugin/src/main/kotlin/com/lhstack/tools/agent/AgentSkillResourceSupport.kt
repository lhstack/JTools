package com.lhstack.tools.agent

import java.text.Normalizer
import kotlin.math.min

object AgentSkillResourceSupport {

    fun listResources(skills: List<AgentSkillState>): List<Map<String, Any?>> {
        return skills.flatMap { skill ->
            skill.resources.map { resource ->
                mapOf(
                    "skillId" to skill.id,
                    "skillName" to skill.name,
                    "path" to resource.path,
                    "length" to resource.content.length
                )
            }
        }
    }

    fun readResource(
        skills: List<AgentSkillState>,
        skillName: String?,
        path: String,
    ): AgentSkillResourceLookupResult {
        val normalizedPath = normalize(path)
        if (normalizedPath.isBlank()) {
            return AgentSkillResourceLookupResult(error = "path 不能为空")
        }
        val candidates = skills.filter { skill ->
            skillName.isNullOrBlank() || normalize(skill.name) == normalize(skillName)
        }
        if (candidates.isEmpty()) {
            return AgentSkillResourceLookupResult(error = "未找到匹配的 skill")
        }
        val exact = candidates.firstNotNullOfOrNull { skill ->
            skill.resources.firstOrNull { resource -> normalize(resource.path) == normalizedPath }?.let { resource ->
                buildResult(skill, resource)
            }
        }
        if (exact != null) {
            return exact
        }
        val byLeaf = candidates.firstNotNullOfOrNull { skill ->
            skill.resources.firstOrNull { resource -> leaf(normalize(resource.path)) == leaf(normalizedPath) }?.let { resource ->
                buildResult(skill, resource)
            }
        }
        if (byLeaf != null) {
            return byLeaf
        }
        val available = candidates.flatMap { skill ->
            skill.resources.map { resource -> "${skill.name}:${resource.path}" }
        }
        return AgentSkillResourceLookupResult(
            error = "Resource not found",
            suggestions = available.take(20)
        )
    }

    private fun buildResult(skill: AgentSkillState, resource: AgentSkillResourceState): AgentSkillResourceLookupResult {
        return AgentSkillResourceLookupResult(
            skillId = skill.id,
            skillName = skill.name,
            path = resource.path,
            content = resource.content
        )
    }

    private fun leaf(path: String): String = path.substringAfterLast('/')

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .replace('\\', '/')
            .replace(Regex("/+"), "/")
            .removePrefix("./")
    }
}

data class AgentSkillResourceLookupResult(
    val skillId: String? = null,
    val skillName: String? = null,
    val path: String? = null,
    val content: String? = null,
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)
