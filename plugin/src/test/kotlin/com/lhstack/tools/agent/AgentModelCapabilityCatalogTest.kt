package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentModelCapabilityCatalogTest {

    @Test
    fun `multimodal capability options are separate from execution options`() {
        assertEquals(
            listOf("text", "image", "audio", "video", "file"),
            AgentModelCapabilityCatalog.multimodalOptions().map { it.id }
        )
        assertEquals(
            listOf("tool_calling", "streaming"),
            AgentModelCapabilityCatalog.executionOptions().map { it.id }
        )
    }

    @Test
    fun `selected capabilities normalize and deduplicate`() {
        val values = AgentModelCapabilityCatalog.normalize(
            listOf("image", "streaming", "image", "tool_calling", " ")
        )

        assertEquals(listOf("image", "streaming", "tool_calling"), values)
    }

    @Test
    fun `streaming is treated as execution option not multimodal option`() {
        assertTrue(AgentModelCapabilityCatalog.executionOptions().any { it.id == "streaming" })
        assertTrue(AgentModelCapabilityCatalog.multimodalOptions().none { it.id == "streaming" })
    }

    @Test
    fun `reasoning is not exposed as configurable model capability`() {
        assertTrue(AgentModelCapabilityCatalog.executionOptions().none { it.id == "reasoning" })
        assertTrue(AgentModelCapabilityCatalog.normalize(listOf("reasoning", "image")) == listOf("image"))
    }
}
