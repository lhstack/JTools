package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSkillResourceSupportTest {

    @Test
    fun `list resources returns all resources for selected skills`() {
        val skill = AgentSkillState().apply {
            id = "skill-1"
            name = "测试"
            resources.add(AgentSkillResourceState().apply {
                path = "测试资源"
                content = "内容1"
            })
            resources.add(AgentSkillResourceState().apply {
                path = "测试11"
                content = "内容2"
            })
        }

        val listed = AgentSkillResourceSupport.listResources(listOf(skill))

        assertEquals(2, listed.size)
        assertTrue(listed.any { it["path"] == "测试资源" })
        assertTrue(listed.any { it["path"] == "测试11" })
    }

    @Test
    fun `read resource supports exact and leaf path matching`() {
        val skill = AgentSkillState().apply {
            id = "skill-1"
            name = "测试"
            resources.add(AgentSkillResourceState().apply {
                path = "references/测试11"
                content = "资源内容1112"
            })
        }

        val exact = AgentSkillResourceSupport.readResource(listOf(skill), "测试", "references/测试11")
        val leaf = AgentSkillResourceSupport.readResource(listOf(skill), "测试", "测试11")

        assertEquals("资源内容1112", exact.content)
        assertEquals("资源内容1112", leaf.content)
    }
}
