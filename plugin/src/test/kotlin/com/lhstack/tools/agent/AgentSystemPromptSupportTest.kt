package com.lhstack.tools.agent

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSystemPromptSupportTest {

    @Test
    fun `resolve prompt content falls back to default when selection is blank or missing`() {
        val prompts = mutableListOf(
            AgentSystemPromptState().apply {
                id = AgentSystemPromptSupport.DEFAULT_PROMPT_ID
                name = "默认提示词"
                content = "default prompt"
            },
            AgentSystemPromptState().apply {
                id = "custom"
                name = "自定义"
                content = "custom prompt"
            }
        )

        assertEquals("default prompt", AgentSystemPromptSupport.resolvePromptContent(prompts, ""))
        assertEquals("default prompt", AgentSystemPromptSupport.resolvePromptContent(prompts, "missing"))
        assertEquals("custom prompt", AgentSystemPromptSupport.resolvePromptContent(prompts, "custom"))
    }

    @Test
    fun `sync session prompt inserts and updates first system message`() {
        val prompts = mutableListOf(
            AgentSystemPromptState().apply {
                id = AgentSystemPromptSupport.DEFAULT_PROMPT_ID
                name = "默认提示词"
                content = "default prompt"
            },
            AgentSystemPromptState().apply {
                id = "custom"
                name = "自定义"
                content = "custom prompt"
            }
        )
        val session = AgentSessionState().apply {
            messages.add(
                """{"role":"user","content":"hello"}"""
            )
        }

        AgentSystemPromptSupport.syncSessionSystemPrompt(session, prompts)

        assertEquals("system", JsonParser.parseString(session.messages.first()).asJsonObject.get("role").asString)
        assertEquals("default prompt", JsonParser.parseString(session.messages.first()).asJsonObject.get("content").asString)

        session.systemPromptId = "custom"
        AgentSystemPromptSupport.syncSessionSystemPrompt(session, prompts)

        assertEquals("custom prompt", JsonParser.parseString(session.messages.first()).asJsonObject.get("content").asString)
        assertTrue(session.messages[1].contains("\"role\":\"user\""))
    }
}
