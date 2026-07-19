package com.lhstack.tools.agent.model.anthropic

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.http.ModelHttpSupport
import com.lhstack.tools.agent.model.llm.AssistantContent
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ProviderRound
import com.lhstack.tools.agent.model.llm.ProviderToolCall
import com.lhstack.tools.agent.model.llm.ToolCall
import com.lhstack.tools.agent.model.llm.ToolFunction
import com.lhstack.tools.agent.model.llm.Usage

/**
 * Anthropic 响应解析。完全照抄 awake-claw anthropic.rs 的 parse_message_response /
 * usage_from_value / tool_input_arguments / assistant_message。
 */
internal object AnthropicParser {

    /** 照抄 parse_message_response：content 分 text/thinking/tool_use 三类聚合。 */
    fun parseMessageResponse(value: JsonElement): ProviderRound {
        val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val text = StringBuilder()
        val reasoning = mutableListOf<String>()
        val calls = mutableListOf<ProviderToolCall>()
        val content = obj.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        for (block in content) {
            if (!block.isJsonObject) continue
            val blockObj = block.asJsonObject
            when (stringField(blockObj, "type") ?: "") {
                "text" -> stringField(blockObj, "text")?.let { text.append(it) }
                "thinking" -> stringField(blockObj, "thinking")?.let { reasoning.add(it) }
                "tool_use" -> {
                    val id = stringField(blockObj, "id") ?: ""
                    calls.add(
                        ProviderToolCall(
                            id = id,
                            callId = id,
                            name = stringField(blockObj, "name") ?: "",
                            arguments = toolInputArguments(blockObj.get("input")),
                        )
                    )
                }
            }
        }
        val usage = withTotal(obj.get("usage")?.let { usageFromValue(it) } ?: Usage())
        val round = ProviderRound(response = text.toString())
        round.reasoning.addAll(reasoning)
        round.toolCalls.addAll(calls)
        round.providerMessages.add(assistantMessage(text.toString(), calls))
        round.usage.add(usage)
        return round
    }

    /** 照抄 tool_input_arguments：字符串则解析成 JSON，对象原样，缺失为空对象。 */
    fun toolInputArguments(input: JsonElement?): JsonElement = when {
        input == null || input.isJsonNull -> JsonObject()
        input.isJsonPrimitive && input.asJsonPrimitive.isString -> ModelHttpSupport.parseArgs(input.asString)
        else -> input
    }

    /** 流式片段只保留 provider 原始 usage；total_tokens 在完整 usage 合并后统一推导。 */
    fun usageFromValue(value: JsonElement): Usage = Usage.fromJson(value)

    /** Anthropic 不返回 total_tokens，只在完整响应或流结束后按官方公式补充一次。 */
    fun withTotal(usage: Usage): Usage {
        if (usage.getLong("total_tokens") != null) return usage
        val input = usage.getLong("input_tokens")
        val output = usage.getLong("output_tokens")
        if (input == null && output == null) return usage
        val total = saturatingSum(
            input ?: 0,
            usage.getLong("cache_creation_input_tokens") ?: 0,
            usage.getLong("cache_read_input_tokens") ?: 0,
            output ?: 0,
        )
        usage.setLong("total_tokens", total)
        return usage
    }

    private fun saturatingSum(vararg values: Long): Long = values.fold(0L) { total, value ->
        if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }

    /** 照抄 assistant_message：text + tool_use 拼成 assistant Message。 */
    fun assistantMessage(text: String, calls: List<ProviderToolCall>): Message {
        val content = mutableListOf<AssistantContent>()
        if (text.isNotEmpty()) {
            content.add(AssistantContent.text(text))
        }
        for (call in calls) {
            content.add(
                AssistantContent.ToolCall(
                    ToolCall(
                        id = call.id,
                        callId = call.callId,
                        function = ToolFunction(name = call.name, arguments = call.arguments),
                        signature = null,
                        additionalParams = null,
                    )
                )
            )
        }
        if (content.isEmpty()) {
            content.add(AssistantContent.text(""))
        }
        return Message.Assistant(id = null, content = content)
    }

    private fun stringField(obj: JsonObject, key: String): String? {
        val value = obj.get(key) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }

    private fun longField(obj: JsonObject, key: String): Long? {
        val value = obj.get(key) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asLong else null
    }
}
