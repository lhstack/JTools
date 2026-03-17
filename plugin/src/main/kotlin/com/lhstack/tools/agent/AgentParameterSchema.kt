package com.lhstack.tools.agent

data class AgentParameterOption(
    val value: String,
    val label: String,
)

data class AgentParameterDefinition(
    val key: String,
    val label: String,
    val fullDescription: String,
    val valueType: String,
    val options: List<AgentParameterOption> = emptyList(),
    val supportedProviderTypes: Set<String> = emptySet(),
    val supportedVendorTemplates: Set<String> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
    val recommendedValues: Map<String, String> = emptyMap(),
)

object AgentParameterSchema {
    private val definitions = listOf(
        AgentParameterDefinition(
            key = "temperature",
            label = "Temperature",
            fullDescription = "控制输出随机性。值越高，回复越发散；值越低，回复越稳定。通常取值范围为 0 到 2。",
            valueType = "number",
            supportedProviderTypes = setOf(
                AgentProviderCatalog.TYPE_DASHSCOPE,
                AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
                AgentProviderCatalog.TYPE_ANTHROPIC,
                AgentProviderCatalog.TYPE_GEMINI,
                AgentProviderCatalog.TYPE_OLLAMA,
            ),
        ),
        AgentParameterDefinition(
            key = "top_p",
            label = "Top P",
            fullDescription = "控制核采样范围。模型只从累计概率达到 top_p 的候选 token 中采样，值越低越保守。",
            valueType = "number",
            supportedProviderTypes = setOf(
                AgentProviderCatalog.TYPE_DASHSCOPE,
                AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
                AgentProviderCatalog.TYPE_ANTHROPIC,
                AgentProviderCatalog.TYPE_GEMINI,
            ),
        ),
        AgentParameterDefinition(
            key = "reasoning_effort",
            label = "Reasoning Effort",
            fullDescription = "控制模型在推理阶段投入的计算强度。通常适用于支持推理强度分级的 OpenAI 兼容模型。",
            valueType = "enum",
            options = listOf(
                AgentParameterOption("low", "LOW"),
                AgentParameterOption("medium", "MEDIUM"),
                AgentParameterOption("high", "HIGH"),
            ),
            supportedProviderTypes = setOf(AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE),
            requiredCapabilities = setOf("reasoning"),
        ),
        AgentParameterDefinition(
            key = "tool_choice",
            label = "Tool Choice",
            fullDescription = "控制模型如何调用工具，可选自动决定、禁止工具调用或强制调用工具。",
            valueType = "enum",
            options = listOf(
                AgentParameterOption("auto", "AUTO"),
                AgentParameterOption("none", "NONE"),
                AgentParameterOption("required", "REQUIRED"),
            ),
            supportedProviderTypes = setOf(
                AgentProviderCatalog.TYPE_DASHSCOPE,
                AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
                AgentProviderCatalog.TYPE_ANTHROPIC,
                AgentProviderCatalog.TYPE_GEMINI,
                AgentProviderCatalog.TYPE_OLLAMA,
            ),
            requiredCapabilities = setOf("tool_calling"),
        ),
        AgentParameterDefinition(
            key = "thinking_budget",
            label = "Thinking Budget",
            fullDescription = "控制模型在思考阶段可消耗的 token 预算，仅在支持 thinking 模式的模型上显示。",
            valueType = "integer",
            supportedProviderTypes = setOf(
                AgentProviderCatalog.TYPE_ANTHROPIC,
                AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE,
            ),
            requiredCapabilities = setOf("reasoning"),
        ),
    )

    fun find(key: String): AgentParameterDefinition? {
        return definitions.firstOrNull { it.key == key }
    }

    fun visibleFor(
        providerType: String,
        vendorTemplate: String,
        capabilities: Set<String>,
    ): List<AgentParameterDefinition> {
        return definitions.filter { definition ->
            matchesScope(definition, providerType, vendorTemplate) &&
                capabilities.containsAll(definition.requiredCapabilities)
        }
    }

    fun recommendedFor(providerType: String, vendorTemplate: String): Map<String, String> {
        return definitions.filter { definition ->
            matchesScope(definition, providerType, vendorTemplate)
        }.flatMap { definition ->
            definition.recommendedValues.entries
        }.associate { it.toPair() }
    }

    private fun matchesScope(
        definition: AgentParameterDefinition,
        providerType: String,
        vendorTemplate: String,
    ): Boolean {
        return (definition.supportedProviderTypes.isEmpty() || providerType in definition.supportedProviderTypes) &&
            (definition.supportedVendorTemplates.isEmpty() || vendorTemplate in definition.supportedVendorTemplates)
    }
}
