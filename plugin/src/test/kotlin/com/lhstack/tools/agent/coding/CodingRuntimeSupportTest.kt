package com.lhstack.tools.agent.coding

import com.lhstack.tools.db.config.AgentCapabilityConfig
import com.lhstack.tools.db.config.AgentEnabledItemsConfig
import com.lhstack.tools.db.service.ResourceConfigService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodingRuntimeSupportTest {
    @Test
    fun `include new skills except disabled`() {
        val config = AgentCapabilityConfig(
            skills = AgentEnabledItemsConfig(
                enabled = listOf("keep"),
                includeNew = true,
                disabled = listOf("off"),
            ),
        )
        val available = listOf(
            skill("keep"),
            skill("off"),
            skill("new-skill"),
        )
        assertEquals(setOf("keep", "new-skill"), CodingRuntimeSupport.effectiveSkills(config, available))
    }

    @Test
    fun `skill summaries skip unavailable skills`() {
        val available = listOf(
            skill("alpha", available = true, description = "A"),
            skill("beta", available = false, description = "B"),
        )
        assertEquals(listOf("- alpha: A"), CodingRuntimeSupport.enabledSkillSummaries(setOf("alpha", "beta"), available))
    }

    @Test
    fun `tools include new except disabled`() {
        val config = AgentCapabilityConfig(
            tools = AgentEnabledItemsConfig(
                enabled = listOf("bash"),
                includeNew = true,
                disabled = listOf("cli"),
            ),
        )
        val tools = CodingRuntimeSupport.effectiveTools(config)
        assertTrue("bash" in tools)
        assertTrue("cli" !in tools)
        assertTrue("skills_list" in tools)
    }

    private fun skill(name: String, available: Boolean = true, description: String? = null) =
        ResourceConfigService.SkillDirectoryRecord(
            name = name,
            relativePath = name,
            path = "/tmp/$name",
            description = description,
            available = available,
            failReason = if (available) null else "unavailable",
            files = listOf("SKILL.md"),
        )
}
