package com.lhstack.tools.agent

import com.intellij.util.xmlb.annotations.Tag
import io.agentscope.core.skill.AgentSkill
import java.util.UUID

enum class AgentSkillSourceType(val id: String, val displayName: String) {
    MANUAL("manual", "手动"),
    LOCAL_IMPORT("local_import", "本地导入");

    companion object {
        fun fromId(id: String?): AgentSkillSourceType {
            return entries.firstOrNull { it.id == id } ?: MANUAL
        }
    }
}

@Tag("agent-skill")
class AgentSkillState {
    var id: String = UUID.randomUUID().toString()
    var name: String = ""
    var description: String = ""
    var skillContent: String = ""
    var sourceType: String = AgentSkillSourceType.MANUAL.id
    var sourcePath: String = ""
    var enabledByDefault: Boolean = false
    var resources: MutableList<AgentSkillResourceState> = mutableListOf()
}

@Tag("skill-resource")
class AgentSkillResourceState {
    var path: String = ""
    var content: String = ""
}

data class AgentResolvedSkills(
    val selectedSkills: List<AgentSkillState>,
    val skillBox: io.agentscope.core.skill.SkillBox?,
    val warnings: List<String> = emptyList(),
)

data class AgentSkillImportResult(
    val importedSkills: List<AgentSkillState>,
    val skippedSkills: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class AgentSkillResourceDraft(
    val path: String,
    val content: String,
)

fun AgentSkillState.toSdkSkill(): AgentSkill {
    val builder = AgentSkill.builder()
        .name(name.trim())
        .description(description.trim())
        .skillContent(skillContent)
        .source(sourceType.trim().ifBlank { AgentSkillSourceType.MANUAL.id })
    resources.forEach { resource ->
        val path = resource.path.trim()
        if (path.isNotBlank()) {
            builder.addResource(path, resource.content)
        }
    }
    return builder.build()
}
