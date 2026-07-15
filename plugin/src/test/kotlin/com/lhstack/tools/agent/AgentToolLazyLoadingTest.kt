package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentToolLazyLoadingTest {
    @Test
    fun `browser message exposes tool summary without arguments or result`() {
        val card = AgentAssistantMessageCard(showToolDetail = { _, _ -> })
        card.ensureTool("call-1", "search_text", "{\"query\":\"large\"}")
        card.updateToolResult("call-1", "very large result")

        val summary = card.toBrowserMessage().tools.single()

        assertEquals("call-1", summary.id)
        assertEquals("search_text", summary.name)
        assertFalse(AgentBrowserTool::class.java.declaredFields.any { it.name == "args" || it.name == "result" })
        assertEquals(
            AgentBrowserToolDetail("{\"query\":\"large\"}", "very large result"),
            card.toolDetail("call-1"),
        )
    }
}