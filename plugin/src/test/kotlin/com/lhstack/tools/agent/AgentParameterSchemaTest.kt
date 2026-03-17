package com.lhstack.tools.agent

import io.agentscope.core.model.GenerateOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentParameterSchemaTest {

    @Test
    fun `agentscope generate options builder is available`() {
        val options = GenerateOptions.builder()
            .modelName("qwen3-coder-plus")
            .temperature(0.2)
            .build()

        assertEquals("qwen3-coder-plus", options.modelName)
        assertEquals(0.2, options.temperature)
    }

    @Test
    fun `legacy provider state migrates to new fields`() {
        val provider = AgentProviderState().apply {
            type = "openai"
            baseUrl = "https://api.openai.com/v1"
        }

        AgentProviderSupport.normalizeProvider(provider)

        assertEquals("openai_compatible", provider.providerType)
        assertEquals("openai", provider.vendorTemplate)
    }
}
