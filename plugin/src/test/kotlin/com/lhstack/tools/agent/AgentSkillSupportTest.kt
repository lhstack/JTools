package com.lhstack.tools.agent

import io.agentscope.core.tool.Toolkit
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentSkillSupportTest {

    @Test
    fun `resolve selected manual skills into skill box`() {
        val skill = AgentSkillState().apply {
            id = "skill-1"
            name = "代码审查"
            description = "用于代码审查"
            skillContent = "review only"
            resources.add(AgentSkillResourceState().apply {
                path = "references/api.md"
                content = "# api"
            })
            resources.add(AgentSkillResourceState().apply {
                path = "scripts/hello.sh"
                content = "printf 'hello\\n'"
            })
        }

        val toolkit = Toolkit()
        val resolved = AgentSkillSupport.resolve(listOf(skill), listOf("skill-1"), toolkit)

        assertEquals(1, resolved.selectedSkills.size)
        assertNotNull(resolved.skillBox)
        assertTrue(resolved.warnings.isEmpty())
        assertTrue(resolved.skillBox!!.skillPrompt.contains("代码审查"))
        assertTrue("execute_shell_command" in toolkit.getToolNames())
        assertTrue(resolved.skillBox!!.skillPrompt.contains("Code Execution"))
        val uploadDir = assertNotNull(resolved.skillBox!!.uploadDir)
        assertTrue(Files.isRegularFile(uploadDir.resolve("skill-1/scripts/hello.sh")))
        assertTrue(skill.toSdkSkill().resources.containsKey("references/api.md"))
    }

    @Test
    fun `import scans root and nested skill directories`() {
        val root = Files.createTempDirectory("agent-skill-import")
        val rootSkill = root.resolve("SKILL.md")
        rootSkill.writeText(
            """
            ---
            name: root_skill
            description: root description
            ---
            # Root Skill
            root content
            """.trimIndent()
        )
        root.resolve("references").createDirectories()
        root.resolve("references/api.md").writeText("root api")

        val nestedDir = root.resolve("skills/nested-skill")
        nestedDir.createDirectories()
        nestedDir.resolve("SKILL.md").writeText(
            """
            ---
            name: nested_skill
            description: nested description
            ---
            # Nested Skill
            nested content
            """.trimIndent()
        )
        nestedDir.resolve("examples").createDirectories()
        nestedDir.resolve("examples/example.kt").writeText("fun main() = Unit")

        val result = AgentSkillSupport.importFromDirectory(root)

        assertEquals(2, result.importedSkills.size)
        assertTrue(result.warnings.isEmpty())
        val rootImported = result.importedSkills.first { it.name == "root_skill" }
        assertEquals(AgentSkillSourceType.LOCAL_IMPORT.id, rootImported.sourceType)
        assertTrue(rootImported.resources.any { it.path == "references/api.md" && it.content == "root api" })
        val nestedImported = result.importedSkills.first { it.name == "nested_skill" }
        assertTrue(nestedImported.resources.any { it.path == "examples/example.kt" })
    }
}
