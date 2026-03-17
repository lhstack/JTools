package com.lhstack.tools.agent

import io.agentscope.core.formatter.anthropic.AnthropicChatFormatter
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter
import io.agentscope.core.formatter.ollama.OllamaChatFormatter
import io.agentscope.core.formatter.openai.DeepSeekFormatter
import io.agentscope.core.formatter.openai.GLMFormatter
import io.agentscope.core.formatter.openai.OpenAIChatFormatter
import io.agentscope.core.model.AnthropicChatModel
import io.agentscope.core.model.ChatModelBase
import io.agentscope.core.model.DashScopeChatModel
import io.agentscope.core.model.EndpointType
import io.agentscope.core.model.GeminiChatModel
import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.OllamaChatModel
import io.agentscope.core.model.OpenAIChatModel

data class AgentScopeModelSpec(
    val providerType: String,
    val vendorTemplate: String,
    val modelName: String,
    val baseUrl: String,
    val endpointPath: String,
    val streamingEnabled: Boolean,
    val chatModeEnabled: Boolean,
    val responsesModeEnabled: Boolean,
    val model: ChatModelBase,
    val formatter: Any,
    val defaultOptions: GenerateOptions,
)

class AgentScopeModelFactory(
    private val optionMapper: AgentScopeOptionMapper = AgentScopeOptionMapper(),
) {

    fun create(provider: AgentProviderState): AgentScopeModelSpec {
        val normalized = provider.also(AgentProviderSupport::normalizeProvider)
        val modelName = normalized.activeModel.trim().ifBlank {
            normalized.models.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        }
        require(modelName.isNotBlank()) { "Agent provider must define an active model" }

        val defaultOptions = optionMapper.map(normalized)
        val modelSettings = AgentProviderSupport.findModelSettings(normalized, modelName)
        val providerType = normalized.providerType
        val vendorTemplate = normalized.vendorTemplate
        return when (providerType) {
            AgentProviderCatalog.TYPE_DASHSCOPE -> createDashScope(normalized, modelName, defaultOptions, modelSettings)
            AgentProviderCatalog.TYPE_ANTHROPIC -> createAnthropic(normalized, modelName, defaultOptions, modelSettings)
            AgentProviderCatalog.TYPE_GEMINI -> createGemini(normalized, modelName, defaultOptions, modelSettings)
            AgentProviderCatalog.TYPE_OLLAMA -> createOllama(normalized, modelName, defaultOptions, modelSettings)
            AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE -> createOpenAI(normalized, vendorTemplate, modelName, defaultOptions, modelSettings)
            else -> error("Unsupported provider type: $providerType")
        }
    }

    private fun createDashScope(
        provider: AgentProviderState,
        modelName: String,
        defaultOptions: GenerateOptions,
        settings: AgentModelSettings?,
    ): AgentScopeModelSpec {
        val formatter = DashScopeChatFormatter()
        val streamingEnabled = settings?.streamingEnabled ?: true
        val model = DashScopeChatModel.builder()
            .apiKey(provider.apiKey)
            .modelName(modelName)
            .stream(streamingEnabled)
            .endpointType(resolveDashScopeEndpoint(settings))
            .defaultOptions(defaultOptions)
            .baseUrl(provider.baseUrl)
            .formatter(formatter)
            .build()
        return AgentScopeModelSpec(
            providerType = provider.providerType,
            vendorTemplate = provider.vendorTemplate,
            modelName = modelName,
            baseUrl = provider.baseUrl,
            endpointPath = provider.endpointPath,
            streamingEnabled = streamingEnabled,
            chatModeEnabled = settings?.chatModeEnabled ?: true,
            responsesModeEnabled = settings?.responsesModeEnabled ?: false,
            model = model,
            formatter = formatter,
            defaultOptions = defaultOptions,
        )
    }

    private fun createOpenAI(
        provider: AgentProviderState,
        vendorTemplate: String,
        modelName: String,
        defaultOptions: GenerateOptions,
        settings: AgentModelSettings?,
    ): AgentScopeModelSpec {
        val formatter = when (vendorTemplate) {
            AgentProviderCatalog.TEMPLATE_DEEPSEEK -> DeepSeekFormatter()
            AgentProviderCatalog.TEMPLATE_GLM -> GLMFormatter()
            else -> OpenAIChatFormatter()
        }
        val streamingEnabled = settings?.streamingEnabled ?: true
        val model = OpenAIChatModel.builder()
            .apiKey(provider.apiKey)
            .modelName(modelName)
            .stream(streamingEnabled)
            .generateOptions(defaultOptions)
            .baseUrl(provider.baseUrl)
            .endpointPath(provider.endpointPath)
            .formatter(formatter)
            .build()
        return AgentScopeModelSpec(
            providerType = provider.providerType,
            vendorTemplate = vendorTemplate,
            modelName = modelName,
            baseUrl = provider.baseUrl,
            endpointPath = provider.endpointPath,
            streamingEnabled = streamingEnabled,
            chatModeEnabled = settings?.chatModeEnabled ?: true,
            responsesModeEnabled = settings?.responsesModeEnabled ?: false,
            model = model,
            formatter = formatter,
            defaultOptions = defaultOptions,
        )
    }

    private fun createAnthropic(
        provider: AgentProviderState,
        modelName: String,
        defaultOptions: GenerateOptions,
        settings: AgentModelSettings?,
    ): AgentScopeModelSpec {
        val formatter = AnthropicChatFormatter()
        val streamingEnabled = settings?.streamingEnabled ?: true
        val model = AnthropicChatModel.builder()
            .baseUrl(provider.baseUrl)
            .apiKey(provider.apiKey)
            .modelName(modelName)
            .stream(streamingEnabled)
            .defaultOptions(defaultOptions)
            .formatter(formatter)
            .build()
        return AgentScopeModelSpec(
            providerType = provider.providerType,
            vendorTemplate = provider.vendorTemplate,
            modelName = modelName,
            baseUrl = provider.baseUrl,
            endpointPath = provider.endpointPath,
            streamingEnabled = streamingEnabled,
            chatModeEnabled = settings?.chatModeEnabled ?: true,
            responsesModeEnabled = settings?.responsesModeEnabled ?: false,
            model = model,
            formatter = formatter,
            defaultOptions = defaultOptions,
        )
    }

    private fun createGemini(
        provider: AgentProviderState,
        modelName: String,
        defaultOptions: GenerateOptions,
        settings: AgentModelSettings?,
    ): AgentScopeModelSpec {
        val formatter = "GeminiChatFormatter"
        val streamingEnabled = settings?.streamingEnabled ?: true
        val model = GeminiChatModel.builder()
            .apiKey(provider.apiKey)
            .modelName(modelName)
            .streamEnabled(streamingEnabled)
            .defaultOptions(defaultOptions)
            .build()
        return AgentScopeModelSpec(
            providerType = provider.providerType,
            vendorTemplate = provider.vendorTemplate,
            modelName = modelName,
            baseUrl = provider.baseUrl,
            endpointPath = provider.endpointPath,
            streamingEnabled = streamingEnabled,
            chatModeEnabled = settings?.chatModeEnabled ?: true,
            responsesModeEnabled = settings?.responsesModeEnabled ?: false,
            model = model,
            formatter = formatter,
            defaultOptions = defaultOptions,
        )
    }

    private fun createOllama(
        provider: AgentProviderState,
        modelName: String,
        defaultOptions: GenerateOptions,
        settings: AgentModelSettings?,
    ): AgentScopeModelSpec {
        val formatter = OllamaChatFormatter()
        val model = OllamaChatModel.builder()
            .modelName(modelName)
            .baseUrl(provider.baseUrl)
            .defaultOptions(optionMapper.mapOllama(provider))
            .formatter(formatter)
            .build()
        return AgentScopeModelSpec(
            providerType = provider.providerType,
            vendorTemplate = provider.vendorTemplate,
            modelName = modelName,
            baseUrl = provider.baseUrl,
            endpointPath = provider.endpointPath,
            streamingEnabled = settings?.streamingEnabled ?: true,
            chatModeEnabled = settings?.chatModeEnabled ?: true,
            responsesModeEnabled = settings?.responsesModeEnabled ?: false,
            model = model,
            formatter = formatter,
            defaultOptions = defaultOptions,
        )
    }

    private fun resolveDashScopeEndpoint(settings: AgentModelSettings?): EndpointType {
        val capabilities = settings?.modelCapabilities.orEmpty()
        return if (capabilities.any { AgentModelCapabilityCatalog.isMultimodal(it) && it != "text" && it != "file" }) {
            EndpointType.MULTIMODAL
        } else {
            EndpointType.AUTO
        }
    }
}
