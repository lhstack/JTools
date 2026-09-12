package com.lhstack.tools.llm.provider

import com.lhstack.tools.llm.LlmJson
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ProviderRound
import com.lhstack.tools.llm.ProviderToolCall
import com.lhstack.tools.llm.Usage
import com.lhstack.tools.llm.openai.OpenAiParser

/**
 * 单次 SSE 失败（Layer A）的处理：
 * - 已解析出完整 tool_call：当作本轮成功，交给正常工具循环，不走 stream 重试。
 * - 只有回复/推理：重试，并把这段 assistant 文本带进下一轮请求。
 * - 半截 tool 丢掉；没有任何可见输出：按原错误、原 body 重试。
 */
internal class StreamPartialFailure(
    val original: Throwable,
    val partial: ProviderRound?,
) : RuntimeException(original.message, original)

internal sealed class StreamFailureOutcome {
    data class Completed(val round: ProviderRound) : StreamFailureOutcome()
    data class Retry(val failure: StreamPartialFailure) : StreamFailureOutcome()
    data class Fatal(val error: Throwable) : StreamFailureOutcome()
}

internal object StreamRetryCarry {

    fun completeToolCall(id: String, name: String, arguments: String): ProviderToolCall? {
        if (id.isBlank() || name.isBlank()) return null
        val parsed = parseToolArguments(arguments) ?: return null
        return ProviderToolCall(id = id, callId = id, name = name, arguments = parsed)
    }

    fun partialRound(
        text: String,
        reasoning: List<String>,
        calls: List<ProviderToolCall> = emptyList(),
        usage: Usage = Usage(),
        assistantMessage: (String, List<ProviderToolCall>) -> Message = OpenAiParser::assistantMessage,
    ): ProviderRound? {
        if (text.isEmpty() && calls.isEmpty() && reasoning.none { it.isNotBlank() }) return null
        return ProviderRound(
            response = text,
            reasoning = reasoning.filter { it.isNotBlank() }.toMutableList(),
            toolCalls = calls.toMutableList(),
            providerMessages = mutableListOf(assistantMessage(text, calls)),
            usage = usage,
        )
    }

    fun outcome(
        error: Throwable,
        text: String,
        reasoning: List<String>,
        completeTools: List<ProviderToolCall>,
        usage: Usage = Usage(),
        assistantMessage: (String, List<ProviderToolCall>) -> Message = OpenAiParser::assistantMessage,
    ): StreamFailureOutcome {
        if (completeTools.isNotEmpty()) {
            val round = partialRound(text, reasoning, completeTools, usage, assistantMessage)
                ?: return StreamFailureOutcome.Fatal(error)
            return StreamFailureOutcome.Completed(round)
        }
        val partial = partialRound(text, reasoning, emptyList(), usage, assistantMessage)
        return if (partial != null) {
            StreamFailureOutcome.Retry(StreamPartialFailure(error, partial))
        } else {
            StreamFailureOutcome.Fatal(error)
        }
    }

    fun throwOrComplete(outcome: StreamFailureOutcome): ProviderRound = when (outcome) {
        is StreamFailureOutcome.Completed -> outcome.round
        is StreamFailureOutcome.Retry -> throw outcome.failure
        is StreamFailureOutcome.Fatal -> throw outcome.error
    }

    fun appendPartialToRequest(
        appendMessages: (List<Message>) -> Unit,
        partial: ProviderRound?,
    ) {
        val assistant = partial?.providerMessages?.lastOrNull() ?: return
        appendMessages(listOf(assistant))
    }

    private fun parseToolArguments(value: String): com.google.gson.JsonElement? = try {
        LlmJson.parseArgs(value)
    } catch (_: Throwable) {
        null
    }
}
