package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class AgentParameterVisibilityTest {

    @Test
    fun `reasoning effort hidden for ollama`() {
        val visible = AgentParameterSchema.visibleFor(
            providerType = AgentProviderCatalog.TYPE_OLLAMA,
            vendorTemplate = AgentProviderCatalog.TEMPLATE_OLLAMA,
            capabilities = setOf("text_input"),
        )

        assertFalse(visible.any { it.key == "reasoning_effort" })
    }
}
