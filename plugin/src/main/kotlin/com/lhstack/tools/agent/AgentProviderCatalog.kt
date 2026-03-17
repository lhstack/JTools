package com.lhstack.tools.agent

object AgentProviderCatalog {
    const val TYPE_DASHSCOPE = "dashscope"
    const val TYPE_OPENAI_COMPATIBLE = "openai_compatible"
    const val TYPE_ANTHROPIC = "anthropic"
    const val TYPE_GEMINI = "gemini"
    const val TYPE_OLLAMA = "ollama"

    const val TEMPLATE_DASHSCOPE = "dashscope"
    const val TEMPLATE_OPENAI = "openai"
    const val TEMPLATE_DEEPSEEK = "deepseek"
    const val TEMPLATE_GLM = "glm"
    const val TEMPLATE_OPENROUTER = "openrouter"
    const val TEMPLATE_SILICONFLOW = "siliconflow"
    const val TEMPLATE_ALIBABA_OPENAI_COMPATIBLE = "alibaba_openai_compatible"
    const val TEMPLATE_ANTHROPIC = "anthropic"
    const val TEMPLATE_GEMINI = "gemini"
    const val TEMPLATE_OLLAMA = "ollama"

    private val defaultCapabilitiesByType = mapOf(
        TYPE_DASHSCOPE to listOf("text_input", "tool_calling", "reasoning", "memory", "planning", "state_persistence"),
        TYPE_OPENAI_COMPATIBLE to listOf("text_input", "tool_calling", "reasoning", "memory", "planning", "state_persistence"),
        TYPE_ANTHROPIC to listOf("text_input", "tool_calling", "reasoning", "memory", "planning", "state_persistence"),
        TYPE_GEMINI to listOf("text_input", "tool_calling", "reasoning", "memory", "planning", "state_persistence"),
        TYPE_OLLAMA to listOf("text_input", "tool_calling", "memory", "planning", "state_persistence"),
    )

    fun normalizeProviderType(providerType: String?, legacyType: String?): String {
        val trimmed = providerType?.trim().orEmpty()
        if (trimmed.isNotBlank()) {
            return when (trimmed) {
                TYPE_DASHSCOPE,
                TYPE_OPENAI_COMPATIBLE,
                TYPE_ANTHROPIC,
                TYPE_GEMINI,
                TYPE_OLLAMA -> trimmed
                else -> TYPE_OPENAI_COMPATIBLE
            }
        }
        return when (legacyType?.trim()?.lowercase()) {
            AgentProviderType.ANTHROPIC.id -> TYPE_ANTHROPIC
            else -> TYPE_OPENAI_COMPATIBLE
        }
    }

    fun normalizeVendorTemplate(providerType: String, vendorTemplate: String?, legacyType: String?): String {
        val trimmed = vendorTemplate?.trim().orEmpty()
        if (trimmed.isNotBlank()) {
            return trimmed
        }
        return when (providerType) {
            TYPE_DASHSCOPE -> TEMPLATE_DASHSCOPE
            TYPE_ANTHROPIC -> TEMPLATE_ANTHROPIC
            TYPE_GEMINI -> TEMPLATE_GEMINI
            TYPE_OLLAMA -> TEMPLATE_OLLAMA
            TYPE_OPENAI_COMPATIBLE -> when (legacyType?.trim()?.lowercase()) {
                AgentProviderType.ANTHROPIC.id -> TEMPLATE_ANTHROPIC
                else -> TEMPLATE_OPENAI
            }
            else -> TEMPLATE_OPENAI
        }
    }

    fun defaultBaseUrl(providerType: String, vendorTemplate: String): String {
        return when (providerType) {
            TYPE_DASHSCOPE -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
            TYPE_ANTHROPIC -> "https://api.anthropic.com"
            TYPE_GEMINI -> "https://generativelanguage.googleapis.com"
            TYPE_OLLAMA -> "http://localhost:11434"
            TYPE_OPENAI_COMPATIBLE -> when (vendorTemplate) {
                TEMPLATE_DEEPSEEK -> "https://api.deepseek.com"
                TEMPLATE_GLM -> "https://open.bigmodel.cn/api/paas/v4"
                TEMPLATE_OPENROUTER -> "https://openrouter.ai/api/v1"
                TEMPLATE_SILICONFLOW -> "https://api.siliconflow.cn/v1"
                TEMPLATE_ALIBABA_OPENAI_COMPATIBLE -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
                else -> "https://api.openai.com/v1"
            }
            else -> "https://api.openai.com/v1"
        }
    }

    fun defaultEndpointPath(providerType: String): String {
        return when (providerType) {
            TYPE_OPENAI_COMPATIBLE,
            TYPE_DASHSCOPE -> "/chat/completions"
            TYPE_ANTHROPIC -> "/v1/messages"
            TYPE_GEMINI -> ""
            TYPE_OLLAMA -> "/api/chat"
            else -> ""
        }
    }

    fun defaultCapabilities(providerType: String): MutableList<String> {
        return defaultCapabilitiesByType[providerType].orEmpty().toMutableList()
    }
}
