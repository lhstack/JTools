package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn
import com.lhstack.tools.db.config.AgentPersonaConfig
import java.util.concurrent.atomic.AtomicReference

/** Agent 蒸馏任务专用动态工具。仅暂存通过长度校验的完整人格配置，由调用方在模型执行成功后提交。 */
class UpdateAgentDistillationTool(
    private val draft: AtomicReference<AgentPersonaConfig?>,
    private val limits: AgentPersonaConfig,
) : ToolDyn {
    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = NAME,
        description = "提交 Agent 蒸馏后的完整长期专业配置。只在 Agent 蒸馏任务中使用；如果任一字段超过最大字符数，本工具会返回错误，必须压缩后重新调用。",
        parameters = JsonParser.parseString(
            """
            {
              "type":"object",
              "properties":{
                "memory":{"type":"string","description":"完整的专业记忆，必须在对应最大字符数以内。"},
                "behavior_habits":{"type":"string","description":"完整的工作方法，必须在对应最大字符数以内。"},
                "soul":{"type":"string","description":"完整的角色设定，必须在对应最大字符数以内。"},
                "profile":{"type":"string","description":"完整的能力画像，必须在对应最大字符数以内。"},
                "guardrails":{"type":"string","description":"完整的边界约束，必须在对应最大字符数以内。"}
              },
              "required":["memory","behavior_habits","soul","profile","guardrails"]
            }
            """.trimIndent()
        ),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        require(args.isJsonObject) { "$NAME 参数必须是 JSON object" }
        val value = args.asJsonObject
        val fields = listOf(
            Field("memory", "专业记忆", limits.memoryMaxChars),
            Field("behavior_habits", "工作方法", limits.behaviorHabitsMaxChars),
            Field("soul", "角色设定", limits.soulMaxChars),
            Field("profile", "能力画像", limits.profileMaxChars),
            Field("guardrails", "边界约束", limits.guardrailsMaxChars),
        )
        val values = fields.associate { it.key to requiredString(value, it.key) }
        val errors = fields.mapNotNull { field ->
            val actual = values.getValue(field.key).length
            if (field.maxChars <= 0 || actual <= field.maxChars) null else JsonObject().apply {
                addProperty("field", field.key)
                addProperty("max_chars", field.maxChars)
                addProperty("actual_chars", actual)
                addProperty("overflow_chars", actual - field.maxChars)
                addProperty("instruction", "字段 `${field.key}` 当前 $actual 字符，超过 ${field.maxChars} 字符，请删除重复、临时和低价值内容后重新调用 $NAME。")
            }
        }
        if (errors.isNotEmpty()) return JsonObject().apply {
            addProperty("ok", false)
            addProperty("message", "Agent 蒸馏内容未通过长度校验，请根据 errors 压缩后重新调用本工具。")
            add("errors", com.google.gson.JsonArray().apply { errors.forEach(::add) })
        }
        draft.set(
            AgentPersonaConfig(
                memory = values.getValue("memory"), memoryMaxChars = limits.memoryMaxChars,
                behaviorHabits = values.getValue("behavior_habits"), behaviorHabitsMaxChars = limits.behaviorHabitsMaxChars,
                soul = values.getValue("soul"), soulMaxChars = limits.soulMaxChars,
                profile = values.getValue("profile"), profileMaxChars = limits.profileMaxChars,
                guardrails = values.getValue("guardrails"), guardrailsMaxChars = limits.guardrailsMaxChars,
            )
        )
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("message", "Agent 蒸馏内容已通过校验并暂存。请用最终回复输出本次蒸馏更新摘要，不要再输出 JSON。")
            add("errors", com.google.gson.JsonArray())
        }
    }

    private fun requiredString(value: JsonObject, key: String): String =
        value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
            ?: throw IllegalArgumentException("$NAME 缺少字符串字段 `$key`")

    private data class Field(val key: String, val label: String, val maxChars: Int)

    companion object {
        const val NAME = "update_agent_distillation"
    }
}
