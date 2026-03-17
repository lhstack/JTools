package com.lhstack.tools.agent

import io.agentscope.core.model.OpenAIChatModel
import io.agentscope.core.model.ToolChoice
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentScopeModelFactoryTest {

    @Test
    fun `agentscope openai builder is available`() {
        val model = OpenAIChatModel.builder()
            .modelName("test-model")
            .build()

        assertEquals("test-model", model.modelName)
    }

    @Test
    fun `dashscope provider creates dashscope model`() {
        val provider = sampleProvider(
            providerType = AgentProviderCatalog.TYPE_DASHSCOPE,
            vendorTemplate = AgentProviderCatalog.TEMPLATE_DASHSCOPE,
            modelName = "qwen3-coder-plus",
        )

        val spec = AgentScopeModelFactory().create(provider)

        assertEquals(AgentProviderCatalog.TYPE_DASHSCOPE, spec.providerType)
        assertEquals(AgentProviderCatalog.TEMPLATE_DASHSCOPE, spec.vendorTemplate)
        assertEquals("qwen3-coder-plus", spec.modelName)
        assertTrue(spec.model.javaClass.simpleName.contains("DashScopeChatModel"))
    }

    @Test
    fun `deepseek template uses deepseek formatter and maps default parameters`() {
        val provider = sampleProvider(
            providerType = AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            vendorTemplate = AgentProviderCatalog.TEMPLATE_DEEPSEEK,
            modelName = "deepseek-chat",
        ).apply {
            defaultParameters["temperature"] = "0.4"
            defaultParameters["reasoning_effort"] = "high"
            defaultParameters["tool_choice"] = "required"
            defaultParameters["seed"] = "7"
        }

        val spec = AgentScopeModelFactory().create(provider)

        assertEquals(AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE, spec.providerType)
        assertEquals(AgentProviderCatalog.TEMPLATE_DEEPSEEK, spec.vendorTemplate)
        assertEquals("OpenAIChatModel", spec.model.javaClass.simpleName)
        assertEquals("DeepSeekFormatter", spec.formatter.javaClass.simpleName)
        assertEquals(0.4, spec.defaultOptions.temperature)
        assertEquals("high", spec.defaultOptions.reasoningEffort)
        assertTrue(spec.defaultOptions.toolChoice is ToolChoice.Required)
        assertEquals(7L, spec.defaultOptions.seed)
    }

    @Test
    fun `model settings can disable streaming`() {
        val provider = sampleProvider(
            providerType = AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            vendorTemplate = AgentProviderCatalog.TEMPLATE_OPENAI,
            modelName = "gpt-4o-mini",
        ).apply {
            modelSettings.add(AgentModelSettings().apply {
                model = "gpt-4o-mini"
                streamingEnabled = false
            })
        }

        val spec = AgentScopeModelFactory().create(provider)

        assertEquals(false, spec.streamingEnabled)
    }

    private fun sampleProvider(
        providerType: String,
        vendorTemplate: String,
        modelName: String,
    ): AgentProviderState {
        return AgentProviderState().apply {
            this.providerType = providerType
            this.vendorTemplate = vendorTemplate
            this.activeModel = modelName
            this.apiKey = "test-key"
            this.baseUrl = AgentProviderSupport.defaultBaseUrl(providerType, vendorTemplate)
            AgentProviderSupport.normalizeProvider(this)
        }
    }
}
