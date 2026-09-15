package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn
import com.lhstack.tools.plugins.FunctionCalling

/** 把插件暴露的 FunctionCalling 包装为模型工具。 */
class PluginFunctionTool(
    private val toolName: String,
    private val function: FunctionCalling,
    private val sessionName: String? = null,
    private val sessionId: String? = null,
    private val provider: String? = null,
    private val model: String? = null,
) : ToolDyn {

    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = toolName,
        description = function.description(),
        parameters = JsonParser.parseString(function.parameters()),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val payload = args.toString()
        val result = if (!sessionName.isNullOrBlank() && !sessionId.isNullOrBlank()) {
            function.call(payload, sessionName, sessionId, provider.orEmpty(), model.orEmpty())
        } else {
            function.call(payload)
        }
        return JsonParser.parseString(result)
    }
}
