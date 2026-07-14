package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import com.lhstack.tools.plugins.FunctionCalling

/** 把插件暴露的 FunctionCalling 包装为模型工具。 */
class PluginFunctionTool(
    private val toolName: String,
    private val function: FunctionCalling,
) : ToolDyn {

    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = toolName,
        description = function.description(),
        parameters = JsonParser.parseString(function.parameters()),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        return JsonParser.parseString(function.call(args.toString()))
    }
}
