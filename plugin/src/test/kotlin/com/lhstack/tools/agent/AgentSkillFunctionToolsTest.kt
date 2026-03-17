package com.lhstack.tools.agent

import com.lhstack.tools.plugins.PluginState
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSkillFunctionToolsTest {

    @Test
    fun `skill add update and session enable mutate state`() {
        val projectKey = "/demo/project"
        val state = PluginState.State().apply {
            agentSessions.add(AgentSessionState().apply {
                id = "session-1"
                this.projectKey = projectKey
                title = "Demo"
            })
            agentActiveSessionIdByProject[projectKey] = "session-1"
        }

        val addResult = AgentSkillFunctionTools.addSkill(
            state = state,
            input = AgentSkillAddInput(
                name = "分析",
                description = "分析技能",
                skillContent = "# 分析",
                resources = listOf(
                    AgentSkillResourceDraft(path = "references/api.md", content = "api")
                )
            )
        )

        assertTrue(addResult.ok)
        val skill = state.agentSkills.single()
        assertEquals("分析", skill.name)
        assertEquals("references/api.md", skill.resources.single().path)

        val updateResult = AgentSkillFunctionTools.updateSkill(
            state = state,
            input = AgentSkillUpdateInput(
                skillId = skill.id,
                description = "新的说明",
                resources = listOf(
                    AgentSkillResourceDraft(path = "examples/demo.kt", content = "fun main() = Unit")
                )
            )
        )

        assertTrue(updateResult.ok)
        assertEquals("新的说明", skill.description)
        assertEquals("examples/demo.kt", skill.resources.single().path)

        val enableResult = AgentSkillFunctionTools.setEnabled(
            state = state,
            scope = AgentSkillEnableScope.SESSION,
            skillId = skill.id,
            enabled = true,
            projectKey = projectKey
        )

        assertTrue(enableResult.ok)
        assertEquals(listOf(skill.id), state.agentSessions.single().enabledSkillIds)
    }

    @Test
    fun `skill import from path loads nested skills and skips duplicates`() {
        val root = Files.createTempDirectory("skills-import")
        root.resolve("skill-a").createDirectories()
        root.resolve("skill-a/SKILL.md").writeText(
            """
            ---
            name: skill_a
            description: skill a
            ---
            # Skill A
            body
            """.trimIndent()
        )
        root.resolve("skill-b").createDirectories()
        root.resolve("skill-b/SKILL.md").writeText(
            """
            ---
            name: skill_b
            description: skill b
            ---
            # Skill B
            body
            """.trimIndent()
        )

        val existing = listOf(AgentSkillState().apply {
            id = "existing"
            name = "skill_a"
            description = "existing"
            skillContent = "existing"
        })

        val result = AgentSkillImportSupport.importFromPath(root.toString(), existing)

        assertEquals(listOf("skill_b"), result.importedSkills.map { it.name })
        assertTrue(result.skippedSkills.any { it.contains("skill_a") })
    }
}
