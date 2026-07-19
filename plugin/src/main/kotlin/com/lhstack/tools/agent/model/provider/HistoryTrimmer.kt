package com.lhstack.tools.agent.model.provider

import com.google.gson.Gson
import com.lhstack.tools.agent.model.llm.AssistantContent
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.ToolResultContent
import com.lhstack.tools.agent.model.llm.UserContent

/** 对齐 awake：先按工具调用轮次保留，再按上下文预算从最新用户轮次向前截断。 */
internal object HistoryTrimmer {
    fun trim(
        history: List<Message>,
        preamble: String,
        prompt: String,
        contextWindow: Long?,
        maxTokens: Long?,
        maxHistoryTurns: Int?,
        retentionRounds: Int?,
    ): List<Message> {
        val retained = retainLatestToolRounds(history, retentionRounds)
        val turns = retainLatestTurns(groupIntoTurns(retained), maxHistoryTurns)
        val limit = contextWindow?.takeIf { it > 0 } ?: return turns.flatten()
        val inputLimit = (limit - (maxTokens ?: 0)).coerceAtLeast(0)
        val budget = (inputLimit * 4 - preamble.length - prompt.length).coerceAtLeast(0)
        if (budget == 0L) return emptyList()
        val kept = mutableListOf<List<Message>>()
        var used = 0L
        for (turn in turns.asReversed()) {
            val compressed = turn.map(::compress)
            val cost = compressed.sumOf { GsonSize.estimate(it).toLong() }
            if (used + cost > budget) break
            used += cost
            kept += compressed
        }
        return kept.asReversed().flatten()
    }

    private fun retainLatestTurns(turns: List<List<Message>>, maxTurns: Int?): List<List<Message>> {
        if (maxTurns == null || turns.size <= maxTurns) return turns
        return turns.takeLast(maxTurns)
    }

    private fun retainLatestToolRounds(history: List<Message>, retention: Int?): List<Message> {
        if (retention == null) return history
        val groups = groupForContext(history)
        var remove = (groups.count { group -> group.any(::hasToolCall) } - retention).coerceAtLeast(0)
        return groups.filter { group ->
            if (remove > 0 && group.any(::hasToolCall)) { remove--; false } else true
        }.flatten()
    }

    private fun groupIntoTurns(history: List<Message>): List<List<Message>> {
        val turns = mutableListOf<MutableList<Message>>()
        for (group in groupForContext(history)) {
            if (group.firstOrNull()?.let { it is Message.User && !hasToolResult(it) } == true) turns += group.toMutableList()
            else turns.lastOrNull()?.addAll(group)
        }
        return turns
    }

    private fun groupForContext(history: List<Message>): List<List<Message>> {
        val groups = mutableListOf<List<Message>>()
        var index = 0
        while (index < history.size) {
            val message = history[index]
            if (hasToolCall(message)) {
                if (index + 1 < history.size && hasToolResult(history[index + 1])) {
                    groups += listOf(message, history[index + 1]); index += 2; continue
                }
                index++; continue
            }
            if (hasToolResult(message)) { index++; continue }
            groups += listOf(message); index++
        }
        return groups
    }

    private fun hasToolCall(message: Message): Boolean = message is Message.Assistant && message.content.any { it is AssistantContent.ToolCall }
    private fun hasToolResult(message: Message): Boolean = message is Message.User && message.content.any { it is UserContent.ToolResult }

    private fun compress(message: Message): Message = when (message) {
        is Message.System -> message.copy(content = compressText(message.content))
        is Message.User -> message.copy(content = message.content.map { content ->
            when (content) {
                is UserContent.Text -> content.copy(text = compressText(content.text))
                is UserContent.ToolResult -> content.copy(toolResult = content.toolResult.copy(content = content.toolResult.content.map { result -> if (result is ToolResultContent.Text) result.copy(text = compressText(result.text)) else result }))
                else -> content
            }
        })
        is Message.Assistant -> message.copy(content = message.content.map { content ->
            if (content is AssistantContent.Text) content.copy(text = compressText(content.text)) else content
        })
    }

    private fun compressText(value: String): String {
        val result = StringBuilder(); var blank = 0; var inCode = false
        value.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { raw ->
            val line = raw.trimEnd(); val fence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
            if (fence) inCode = !inCode
            if (line.isBlank()) { blank++; if (blank <= 1 && result.isNotEmpty()) result.append('\n'); return@forEach }
            blank = 0; if (result.isNotEmpty() && !result.endsWith('\n')) result.append('\n')
            if (inCode) { result.append(line); return@forEach }
            var previousSpace = false; var repeat = '\u0000'; var count = 0
            line.trim().forEach { ch ->
                if (ch.isWhitespace()) { if (!previousSpace) result.append(' '); previousSpace = true; repeat='\u0000'; count=0; return@forEach }
                previousSpace=false
                if (ch in charArrayOf('-', '=', '_', '*', '#', '!', '?', '.', '~')) { if (ch == repeat) count++ else { repeat=ch; count=1 }; if (count <= 8) result.append(ch) }
                else { repeat='\u0000'; count=0; result.append(ch) }
            }
        }
        return result.toString().trim()
    }

    private object GsonSize {
        private val gson = Gson()
        fun estimate(message: Message): Int = gson.toJson(message).length
    }
}
