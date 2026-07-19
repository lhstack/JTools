package com.lhstack.tools.agent.model.openai

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
 * OpenAI 响应解析。完全照抄 awake-claw openai.rs 里的 parse_chat_response / chat_reasoning /
 * parse_response / collect_response_* / chat_tool_call / round_from_parts / assistant_message /
 * chat_usage / response_usage / append_reasoning_delta / response_reasoning_text。
 */
internal object OpenAiParser {

    /** 照抄 parse_chat_response。 */
    fun parseChatResponse(value: JsonElement): ProviderRound {
        val message = value.obj()
            ?.getArray("choices")
            ?.firstOrNull()
            ?.obj()
            ?.getObject("message")
            ?: throw IllegalStateException("OpenAI chat response missing choices[0].message")
        val text = message.getString("content").orEmpty()
        val reasoning = chatReasoning(message)
        val calls = message.getArray("tool_calls")
            ?.map { chatToolCall(it) }
            ?.toMutableList()
            ?: mutableListOf()
        val usage = value.obj()?.get("usage")?.let { chatUsage(it) } ?: Usage()
        return ProviderRound(
            response = text,
            reasoning = reasoning.toMutableList(),
            toolCalls = calls.toMutableList(),
            providerMessages = mutableListOf(assistantMessage(text, calls)),
            usage = usage,
        )
    }

    /** 照抄 chat_reasoning。 */
    private fun chatReasoning(message: JsonObject): List<String> {
        val text = (message.getString("reasoning_content") ?: message.getString("reasoning"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return listOf(text)
    }

    /** 照抄 parse_response。 */
    fun parseResponse(value: JsonElement): ProviderRound {
        val output = value.obj()?.getArray("output")
            ?: throw IllegalStateException("OpenAI response missing output")
        val text = StringBuilder()
        val reasoning = mutableListOf<String>()
        val calls = mutableListOf<ProviderToolCall>()
        for (item in output) {
            collectResponseOutputItem(item, text, reasoning, calls)
        }
        val usage = value.obj()?.get("usage")?.let { responseUsage(it) } ?: Usage()
        return ProviderRound(
            response = text.toString(),
            reasoning = reasoning,
            toolCalls = calls,
            providerMessages = mutableListOf(assistantMessage(text.toString(), calls)),
            usage = usage,
        )
    }

    /** 照抄 collect_response_output_item。 */
    private fun collectResponseOutputItem(
        item: JsonElement,
        text: StringBuilder,
        reasoning: MutableList<String>,
        calls: MutableList<ProviderToolCall>,
    ) {
        val obj = item.obj() ?: return
        when (obj.getString("type").orEmpty()) {
            "message" -> collectResponseMessageText(obj, text)
            "reasoning" -> collectResponseReasoning(obj, reasoning)
            "function_call" -> calls.add(
                ProviderToolCall(
                    id = (obj.getString("id") ?: obj.getString("call_id")).orEmpty(),
                    callId = obj.getString("call_id"),
                    name = obj.getString("name").orEmpty(),
                    arguments = ModelHttpSupport.parseArgs(obj.getString("arguments") ?: "{}"),
                )
            )
        }
    }

    /** 照抄 collect_response_message_text。 */
    private fun collectResponseMessageText(item: JsonObject, text: StringBuilder) {
        val content = item.getArray("content") ?: return
        for (part in content) {
            val partObj = part.obj() ?: continue
            when (partObj.getString("type").orEmpty()) {
                "output_text" -> partObj.getString("text")?.let { text.append(it) }
                "refusal" -> partObj.getString("refusal")?.let { text.append(it) }
            }
        }
    }

    /** 照抄 collect_response_reasoning。 */
    private fun collectResponseReasoning(item: JsonObject, reasoning: MutableList<String>) {
        collectResponseReasoningParts(item.get("summary"), reasoning)
        collectResponseReasoningParts(item.get("content"), reasoning)
    }

    private fun collectResponseReasoningParts(parts: JsonElement?, reasoning: MutableList<String>) {
        val array = parts?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (part in array) {
            responseReasoningText(part)?.let { reasoning.add(it) }
        }
    }

    /** 照抄 response_reasoning_text：多个候选字段取第一个字符串。 */
    fun responseReasoningText(value: JsonElement): String? {
        val obj = value.obj() ?: return null
        return obj.getString("delta")
            ?: obj.getString("text")
            ?: obj.getString("reasoning")
            ?: obj.getString("reasoning_text")
            ?: obj.getString("reasoning_content")
    }

    /** 照抄 chat_tool_call。 */
    private fun chatToolCall(value: JsonElement): ProviderToolCall {
        val obj = value.obj() ?: throw IllegalStateException("OpenAI tool call invalid")
        val function = obj.getObject("function")
            ?: throw IllegalStateException("OpenAI tool call missing function")
        val id = obj.getString("id").orEmpty()
        return ProviderToolCall(
            id = id,
            callId = obj.getString("id"),
            name = function.getString("name")
                ?: throw IllegalStateException("OpenAI tool call missing function.name"),
            arguments = ModelHttpSupport.parseArgs(function.getString("arguments") ?: "{}"),
        )
    }

    /** 照抄 assistant_message。 */
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

    /** OpenAI 原样保留 provider usage；total_tokens 使用 provider 返回值，不自行推导。 */
    fun chatUsage(value: JsonElement): Usage = Usage.fromJson(value)

    /** OpenAI Responses 同样原样保留，包括嵌套的 *_tokens_details。 */
    fun responseUsage(value: JsonElement): Usage = Usage.fromJson(value)

    /** 照抄 append_reasoning_delta：首片新建，后续追加到最后一段。 */
    fun appendReasoningDelta(reasoning: MutableList<String>, text: String) {
        if (text.isEmpty()) return
        if (reasoning.isEmpty()) {
            reasoning.add(text)
        } else {
            reasoning[reasoning.size - 1] = reasoning.last() + text
        }
    }

    // -------- Gson 取值 helper --------

    private fun JsonElement?.obj(): JsonObject? =
        if (this != null && this.isJsonObject) this.asJsonObject else null

    private fun JsonObject.getObject(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.getArray(key: String): com.google.gson.JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.getString(key: String): String? {
        val value = get(key) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }

    private fun JsonObject.getLong(key: String): Long? {
        val value = get(key) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asLong else null
    }
}
