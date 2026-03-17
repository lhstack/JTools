package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.tool.AgentTool as AgentScopeTool
import io.agentscope.core.tool.ToolCallParam
import reactor.core.publisher.Mono

object AgentScopeToolAdapter {

    fun wrap(tool: AgentTool): AgentScopeTool {
        val parameters = parseParameters(tool.parametersJson)
        return object : AgentScopeTool {
            override fun getName(): String = tool.name

            override fun getDescription(): String = tool.description

            override fun getParameters(): Map<String, Any> = parameters

            override fun callAsync(param: ToolCallParam): Mono<ToolResultBlock> {
                return Mono.fromCallable {
                    val inputJson = JsonObject()
                    param.input.forEach { (key, value) ->
                        when (value) {
                            null -> inputJson.add(key, com.google.gson.JsonNull.INSTANCE)
                            is Number -> inputJson.addProperty(key, value)
                            is Boolean -> inputJson.addProperty(key, value)
                            else -> inputJson.addProperty(key, value.toString())
                        }
                    }
                    val result = runCatching { tool.call(inputJson.toString()) }
                        .getOrElse { error -> """{"error":"${error.message ?: "tool failed"}"}""" }
                    ToolResultBlock.of(
                        param.toolUseBlock.id,
                        tool.name,
                        TextBlock.builder().text(result).build(),
                    )
                }
            }
        }
    }

    fun wrapAll(registry: AgentToolRegistry): List<AgentScopeTool> {
        return registry.tools.map(::wrap)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseParameters(parametersJson: String): Map<String, Any> {
        if (parametersJson.isBlank()) {
            return mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        }
        val element = runCatching { JsonParser.parseString(parametersJson) }.getOrNull()
        if (element == null || !element.isJsonObject) {
            return mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        }
        return gsonToAny(element.asJsonObject) as? Map<String, Any>
            ?: mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    }

    private fun gsonToAny(element: com.google.gson.JsonElement): Any? {
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> primitive.asNumber
                    primitive.isString -> primitive.asString
                    else -> primitive.asString
                }
            }
            element.isJsonArray -> element.asJsonArray.map { gsonToAny(it) }
            element.isJsonObject -> element.asJsonObject.entrySet().associate { it.key to gsonToAny(it.value) }
            else -> null
        }
    }
}
