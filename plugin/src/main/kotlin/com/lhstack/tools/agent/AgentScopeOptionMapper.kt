package com.lhstack.tools.agent

import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.ToolChoice
import io.agentscope.core.model.ollama.OllamaOptions

class AgentScopeOptionMapper {

    fun map(provider: AgentProviderState): GenerateOptions {
        val normalized = provider.also(AgentProviderSupport::normalizeProvider)
        val builder = GenerateOptions.builder()
            .apiKey(normalized.apiKey.takeIf { it.isNotBlank() })
            .baseUrl(normalized.baseUrl.takeIf { it.isNotBlank() })
            .endpointPath(normalized.endpointPath.takeIf { it.isNotBlank() })
            .modelName(resolveModelName(normalized))
            .maxTokens(normalized.maxTokens.takeIf { it > 0 })

        normalized.defaultParameters.forEach { (key, rawValue) ->
            if (rawValue.isBlank()) {
                return@forEach
            }
            when (key.trim().lowercase()) {
                "temperature" -> builder.temperature(rawValue.toDoubleOrNull())
                "top_p" -> builder.topP(rawValue.toDoubleOrNull())
                "max_tokens" -> builder.maxTokens(rawValue.toIntOrNull())
                "max_completion_tokens" -> builder.maxCompletionTokens(rawValue.toIntOrNull())
                "frequency_penalty" -> builder.frequencyPenalty(rawValue.toDoubleOrNull())
                "presence_penalty" -> builder.presencePenalty(rawValue.toDoubleOrNull())
                "thinking_budget" -> builder.thinkingBudget(rawValue.toIntOrNull())
                "reasoning_effort" -> builder.reasoningEffort(rawValue.trim().lowercase())
                "tool_choice" -> builder.toolChoice(mapToolChoice(rawValue))
                "top_k" -> builder.topK(rawValue.toIntOrNull())
                "seed" -> builder.seed(rawValue.toLongOrNull())
                else -> builder.additionalBodyParam(key, parseValue(rawValue))
            }
        }

        val headers = AgentProviderSupport.parseCustomHeaders(normalized.customHeaders).headers
        headers.forEach { (name, values) ->
            if (values.isNotEmpty()) {
                builder.additionalHeader(name, values.joinToString(", "))
            }
        }
        return builder.build()
    }

    fun mapOllama(provider: AgentProviderState): OllamaOptions {
        return OllamaOptions.fromGenerateOptions(map(provider))
    }

    private fun resolveModelName(provider: AgentProviderState): String {
        val activeModel = provider.activeModel.trim()
        if (activeModel.isNotBlank()) {
            return activeModel
        }
        return provider.models.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun mapToolChoice(rawValue: String): ToolChoice? {
        return when (val normalized = rawValue.trim().lowercase()) {
            "", "auto" -> ToolChoice.Auto()
            "none" -> ToolChoice.None()
            "required" -> ToolChoice.Required()
            else -> ToolChoice.Specific(normalized)
        }
    }

    private fun parseValue(rawValue: String): Any {
        val value = rawValue.trim()
        return when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            value.toIntOrNull() != null -> value.toInt()
            value.toLongOrNull() != null -> value.toLong()
            value.toDoubleOrNull() != null -> value.toDouble()
            else -> value
        }
    }

    private fun GenerateOptions.Builder.apiKey(value: String?): GenerateOptions.Builder {
        return if (value == null) this else apiKey(value)
    }

    private fun GenerateOptions.Builder.baseUrl(value: String?): GenerateOptions.Builder {
        return if (value == null) this else baseUrl(value)
    }

    private fun GenerateOptions.Builder.endpointPath(value: String?): GenerateOptions.Builder {
        return if (value == null) this else endpointPath(value)
    }

    private fun GenerateOptions.Builder.maxTokens(value: Int?): GenerateOptions.Builder {
        return if (value == null) this else maxTokens(value)
    }

    private fun GenerateOptions.Builder.temperature(value: Double?): GenerateOptions.Builder {
        return if (value == null) this else temperature(value)
    }

    private fun GenerateOptions.Builder.topP(value: Double?): GenerateOptions.Builder {
        return if (value == null) this else topP(value)
    }

    private fun GenerateOptions.Builder.maxCompletionTokens(value: Int?): GenerateOptions.Builder {
        return if (value == null) this else maxCompletionTokens(value)
    }

    private fun GenerateOptions.Builder.frequencyPenalty(value: Double?): GenerateOptions.Builder {
        return if (value == null) this else frequencyPenalty(value)
    }

    private fun GenerateOptions.Builder.presencePenalty(value: Double?): GenerateOptions.Builder {
        return if (value == null) this else presencePenalty(value)
    }

    private fun GenerateOptions.Builder.thinkingBudget(value: Int?): GenerateOptions.Builder {
        return if (value == null) this else thinkingBudget(value)
    }

    private fun GenerateOptions.Builder.toolChoice(value: ToolChoice?): GenerateOptions.Builder {
        return if (value == null) this else toolChoice(value)
    }

    private fun GenerateOptions.Builder.topK(value: Int?): GenerateOptions.Builder {
        return if (value == null) this else topK(value)
    }

    private fun GenerateOptions.Builder.seed(value: Long?): GenerateOptions.Builder {
        return if (value == null) this else seed(value)
    }
}
