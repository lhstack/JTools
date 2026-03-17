package com.lhstack.tools.agent

import io.agentscope.core.ReActAgent
import io.agentscope.core.hook.Hook
import io.agentscope.core.skill.SkillBox
import io.agentscope.core.tool.Toolkit

data class AgentScopeRuntimeConfig(
    val agentName: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val maxIterations: Int = 10,
    val toolkit: Toolkit? = null,
    val skillBox: SkillBox? = null,
    val hooks: List<Hook> = emptyList(),
)

data class AgentScopeRuntimeSpec(
    val modelSpec: AgentScopeModelSpec,
    val features: AgentRuntimeFeatures,
    val builder: ReActAgent.Builder,
)

class AgentScopeRuntime(
    private val modelFactory: AgentScopeModelFactory = AgentScopeModelFactory(),
) {

    fun create(
        provider: AgentProviderState,
        session: AgentSessionState = AgentSessionState(),
        config: AgentScopeRuntimeConfig = AgentScopeRuntimeConfig(),
    ): AgentScopeRuntimeSpec {
        val modelSpec = modelFactory.create(provider)
        val features = AgentRuntimeFeaturesFactory.create(session, modelSpec)
        val agentName = config.agentName.ifBlank {
            provider.name.trim().ifBlank { modelSpec.modelName }
        }
        val builder = ReActAgent.builder()
            .name(agentName)
            .description(config.description)
            .sysPrompt(config.systemPrompt)
            .model(modelSpec.model)
            .memory(features.memory)
            .maxIters(config.maxIterations)
            .statePersistence(features.statePersistence)
        config.toolkit?.let { toolkit ->
            config.skillBox?.bindToolkit(toolkit)
            builder.toolkit(toolkit)
        }
        config.skillBox?.let { builder.skillBox(it) }
        config.hooks.forEach { builder.hook(it) }
        if (features.planNotebook != null) {
            builder.planNotebook(features.planNotebook)
        }
        if (features.longTermMemory != null) {
            builder.longTermMemory(features.longTermMemory)
            if (features.longTermMemoryMode != null) {
                builder.longTermMemoryMode(features.longTermMemoryMode)
            }
        }
        return AgentScopeRuntimeSpec(modelSpec, features, builder)
    }
}
