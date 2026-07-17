package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AgentToolLazyLoadingTest {
    @Test
    fun `browser message exposes tool summary without arguments or result`() {
        val card = AgentAssistantMessageCard(showToolDetail = { _, _ -> })
        card.ensureTool("call-1", "search_project_text", "{\"query\":\"large\"}")
        card.updateToolResult("call-1", "very large result")

        val summary = card.toBrowserMessage().tools.single()

        assertEquals("call-1", summary.id)
        assertEquals("search_project_text", summary.name)
        assertFalse(AgentBrowserTool::class.java.declaredFields.any { it.name == "args" || it.name == "result" })
        assertEquals(
            AgentBrowserToolDetail("{\"query\":\"large\"}", "very large result"),
            card.toolDetail("call-1"),
        )
    }
    @org.junit.jupiter.api.Test
    fun `delegated cards expose their agent identity`() {
        val user = AgentUserMessageCard(content = "delegated", actorLabel = "Writer Agent 发送").toBrowserMessage()
        val assistant = AgentAssistantMessageCard(
            showToolDetail = { _, _ -> },
            actorLabel = "Reviewer Agent 回复",
        ).toBrowserMessage()

        assertEquals("Writer Agent 发送", user.actorLabel)
        assertEquals("Reviewer Agent 回复", assistant.actorLabel)
    }

    @org.junit.jupiter.api.Test
    fun `persisted agent run card loads tool detail by its log id`() {
        var requestedLogId: Long? = null
        val expected = AgentBrowserToolDetail("{\"timezone\":\"UTC\"}", "{\"datetime\":\"now\"}")
        val card = AgentRunMessageCard(
            "run-1",
            1,
            "Writer",
            AgentRunReceiver.USER.value,
            toolDetailLoader = { runId, logId, callId ->
                assertEquals("run-1", runId)
                assertEquals("call-1", callId)
                requestedLogId = logId
                expected
            },
        )
        card.update(AgentRunSnapshot(
            "run-1", 1, "Writer", null, 9, "user", "prompt", "completed", "output", "",
            listOf(AgentBrowserTool("call-1", "get_time", true, false)), null, 208,
        ))

        assertEquals(expected, card.toolDetail("call-1"))
        assertEquals(208, requestedLogId)
    }

    @org.junit.jupiter.api.Test
    fun `agent run card identifies whether the agent replies or sends`() {
        val reply = AgentRunMessageCard("reply", 1, "Writer", AgentRunReceiver.USER.value).apply {
            update(AgentRunSnapshot("reply", 1, "Writer", null, 9, "user", "prompt", "completed", "output", "", emptyList(), null, 1))
        }.toBrowserMessage()
        val send = AgentRunMessageCard("send", 1, "Writer", AgentRunReceiver.AI.value).apply {
            update(AgentRunSnapshot("send", 1, "Writer", null, 9, "ai", "prompt", "completed", "output", "", emptyList(), null, 2))
        }.toBrowserMessage()

        assertEquals("Writer Agent 回复", reply.actorLabel)
        assertEquals("Writer Agent 发送", send.actorLabel)
    }

}
class AgentToolDetailLimitTest {
    @org.junit.jupiter.api.Test
    fun `tool detail is bounded before it reaches the browser`() {
        val detail = com.lhstack.tools.agent.toolDetail("a".repeat(20_000), "r".repeat(20_000))
        assertTrue(detail.args.length <= com.lhstack.tools.agent.MAX_TOOL_DETAIL_CHARS + 100)
        assertTrue(detail.result.length <= com.lhstack.tools.agent.MAX_TOOL_DETAIL_CHARS + 100)
        assertTrue(detail.result.contains("["))
    }
}


class AgentBrowserMessagePersistenceTest {
    @org.junit.jupiter.api.Test
    fun `transient streaming cards are not marked as persisted`() {
        val user = AgentUserMessageCard(content = "draft").toBrowserMessage()
        val assistant = AgentAssistantMessageCard(showToolDetail = { _, _ -> }).toBrowserMessage()

        assertFalse(user.persisted)
        assertFalse(assistant.persisted)
    }

    @org.junit.jupiter.api.Test
    fun `database backed turn cards are marked as persisted`() {
        val user = AgentUserMessageCard(content = "saved", persisted = true).toBrowserMessage()
        val assistant = AgentAssistantMessageCard(
            showToolDetail = { _, _ -> },
            persisted = true,
        ).toBrowserMessage()

        assertTrue(user.persisted)
        assertTrue(assistant.persisted)
    }
}
