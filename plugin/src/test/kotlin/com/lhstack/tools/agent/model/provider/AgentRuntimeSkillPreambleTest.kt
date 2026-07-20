package com.lhstack.tools.agent.model.provider

import com.lhstack.tools.db.service.ResourceConfigService
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRuntimeSkillPreambleTest {
    @Test
    fun `enabled skill summaries include frontmatter descriptions`() {
        val skills = listOf(
            skill("documents", "Create and edit Word documents"),
            skill("pdfs", "Read and transform PDFs"),
            skill("disabled", "Must not be exposed"),
        )

        val summaries = AgentRuntime.enabledSkillSummaries(setOf("documents", "pdfs"), skills)

        assertEquals(
            listOf(
                "- documents: Create and edit Word documents",
                "- pdfs: Read and transform PDFs",
            ),
            summaries,
        )
    }

    @Test
    fun `unavailable skills are excluded and blank descriptions are explicit`() {
        val skills = listOf(
            skill("available", "   "),
            skill("unavailable", "Unavailable skill", available = false),
        )

        assertEquals(
            listOf("- available: 无描述"),
            AgentRuntime.enabledSkillSummaries(setOf("available", "unavailable"), skills),
        )
    }

    private fun skill(
        name: String,
        description: String?,
        available: Boolean = true,
    ) = ResourceConfigService.SkillDirectoryRecord(
        name = name,
        relativePath = name,
        path = name,
        description = description,
        available = available,
        failReason = null,
        files = listOf("SKILL.md"),
    )
}
