package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn

/**
 * 润色结果收集工具。仅在单次润色任务期间由调用方通过 extraTools 注入，
 * 任务结束即随实例消亡，不进入 RuntimeTools 的全局注册表。
 *
 * 模型必须通过该工具提交润色候选，收集到的结果供 UI 回显。
 */
class PolishResultTool : ToolDyn {

    @Volatile
    private var submitted: List<String>? = null

    /** 已提交的润色结果；模型未调用该工具时为 null。 */
    fun submittedResults(): List<String>? = submitted

    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = NAME,
        description = "提交润色候选结果。必须调用一次该工具提交 $MIN_RESULTS 到 $MAX_RESULTS 条改写后的完整文本，" +
            "每条都是可直接替换原文的独立版本，不要只描述修改点。",
        parameters = JsonParser.parseString(
            """
            {
                "type": "object",
                "properties": {
                    "results": {
                        "type": "array",
                        "minItems": $MIN_RESULTS,
                        "maxItems": $MAX_RESULTS,
                        "description": "必填。$MIN_RESULTS 到 $MAX_RESULTS 条润色后的完整文本，每条可直接替换原文。",
                        "items": {
                            "type": "string",
                            "minLength": 1,
                            "description": "必填。一条完整的润色结果文本。"
                        }
                    }
                },
                "required": ["results"],
                "additionalProperties": false
            }
            """.trimIndent()
        ),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val results = parseResults(args)
        submitted = results
        return JsonObject().apply {
            addProperty("accepted", results.size)
            addProperty("message", "润色结果已提交，无需再次调用该工具。")
        }
    }

    private fun parseResults(args: JsonElement): List<String> {
        val obj = args.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw ToolException("arguments must be a JSON object")
        val array = obj.get("results")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw ToolException("missing array argument `results`")
        val results = array.mapIndexed { index, element -> requireText(element, index) }
        require(results.size in MIN_RESULTS..MAX_RESULTS) {
            "results must contain between $MIN_RESULTS and $MAX_RESULTS items"
        }
        return results
    }

    private fun requireText(element: JsonElement, index: Int): String =
        element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolException("results item ${index + 1} must be a non-blank string")

    companion object {
        const val NAME = "submit_polish_results"
        const val MIN_RESULTS = 3
        const val MAX_RESULTS = 5
    }
}
