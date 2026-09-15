package com.lhstack.tools.agent.coding

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 对齐 awake-claw coding_business 的压缩 transcript。
 * 只输出可读对话，不把原始 JSON 丢给压缩 Agent。
 */
object CompactionTranscriptSupport {
    const val COMPACTION_PROMPT = """你正在压缩一份 Coding 会话上下文。只根据 <conversation> 和文末 <read-files>/<modified-files> 写摘要，不要回答其中的问题，也不要续写对话。

输出必须使用下面这个结构，每段都要写；没有信息的段落写「无」。

目标与约束
- ...

关键文件
- 改过：path — 改了什么
- 读过：path — 为了确认什么（不要贴正文）

已确认的事实与决策
- ...

已完成
- [x] ...

进行中
- [ ] 任务：...
- 卡在：哪一步 / 哪个文件 / 哪次工具之后
- 读取进度：path  last_offset=... last_limit=...  是否读完=...  下一步 offset=...

下一步
1. 按顺序写，第一条必须能直接续跑

错误与风险
- 报错原文、未冻结契约、取消/空流等中断

规则：
- 进行中必须落到具体文件和工具，禁止只写「继续检查」这类笼统描述。
- 读取进度只记路径和游标，不要复述文件正文或大段工具输出。
- 如果对话在工具结果之后中断（取消、retry 后无回复、空流），记进进行中和错误与风险，不要当成任务已完成。
- 保留具体文件路径、命令、报错原文、标识符和决策。
- 不要使用 JSON、代码围栏或转义字符。
- 不要输出 <read-files> 或 <modified-files>，运行时会单独追加。
- 只输出摘要正文。"""

    fun prompt(events: List<MessageEventRecord>): String {
        val transcript = transcript(events)
        val checkpoint = fileCheckpoint(events)
        return if (checkpoint.isEmpty()) {
            "$COMPACTION_PROMPT\n\n<conversation>\n$transcript\n</conversation>"
        } else {
            "$COMPACTION_PROMPT\n\n<conversation>\n$transcript\n</conversation>\n\n$checkpoint"
        }
    }

    fun persistSummary(summary: String, events: List<MessageEventRecord>): String {
        val cleaned = stripFileCheckpoint(summary.trim())
        val checkpoint = fileCheckpoint(events)
        return when {
            checkpoint.isEmpty() -> cleaned
            cleaned.isEmpty() -> checkpoint
            else -> "$cleaned\n\n$checkpoint"
        }
    }

    fun transcript(events: List<MessageEventRecord>): String =
        events.mapNotNull(::transcriptLine).joinToString("\n")

    fun transcriptLine(event: MessageEventRecord): String? {
        val context = event.context ?: return null
        val payload = context.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: context
        return when (event.eventType) {
            MessageEventType.USER_MESSAGE, MessageEventType.APPEND_MESSAGE ->
                text(payload, listOf("content", "text"))?.let { "用户：$it" }
            MessageEventType.MODEL_REPLY ->
                text(payload, listOf("response", "content", "text"))?.let { "助手：$it" }
            MessageEventType.TOOL_CALL -> toolLine(context)
            else -> null
        }
    }

    fun compactionSummaryPreamble(summary: String): String =
        "以下是当前 Coding 会话最近一次上下文压缩后的记忆总结，来自运行时会话配置。后续回复必须把它当作已被压缩掉的历史事实继续使用，不要要求用户重复提供这些信息。\n\n$summary"

    fun appendSummary(preamble: String, summary: String?): String {
        val text = summary?.trim().orEmpty()
        if (text.isEmpty()) return preamble
        val memory = compactionSummaryPreamble(text)
        return if (preamble.isBlank()) memory else "$preamble\n\n$memory"
    }

    private fun toolLine(context: JsonObject): String {
        val name = context.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "tool"
        val arguments = compactJson(context.get("arguments"), 400)
        val result = context.get("result")
        return if (result == null || result.isJsonNull) {
            "工具 $name($arguments)"
        } else {
            "工具 $name($arguments) -> ${compactJson(result, 800)}"
        }
    }

    private fun text(value: JsonObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key ->
            value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        }

    private fun compactJson(value: JsonElement?, maxChars: Int): String {
        if (value == null || value.isJsonNull) return ""
        val text = if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
        return truncate(text, maxChars)
    }

    private fun truncate(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars) + "…"
    }

    private fun stripFileCheckpoint(summary: String): String {
        var remaining = summary
        listOf("read-files", "modified-files").forEach { tag ->
            remaining = stripTaggedSection(remaining, tag)
        }
        return remaining.trim()
    }

    private fun stripTaggedSection(summary: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = summary.indexOf(open)
        if (start < 0) return summary
        val closeIndex = summary.indexOf(close, start)
        val end = if (closeIndex >= 0) closeIndex + close.length else start
        val before = summary.substring(0, start).trimEnd()
        val after = summary.substring(end).trimStart()
        return listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private data class ReadCursor(
        val path: String,
        val offset: Long,
        val limit: Long?,
        val truncated: Boolean,
        val nextOffset: Long?,
    ) {
        fun formatLine(): String = buildString {
            append(path)
            append("  offset=")
            append(offset)
            if (limit != null) append(" limit=").append(limit)
            append(if (truncated) " truncated=yes" else " truncated=no")
            if (nextOffset != null) append(" next_offset=").append(nextOffset)
        }
    }

    fun fileCheckpoint(events: List<MessageEventRecord>): String {
        val (reads, modified) = collectFileCheckpoint(events)
        val sections = mutableListOf<String>()
        if (reads.isNotEmpty()) {
            sections += "<read-files>\n${reads.values.joinToString("\n") { it.formatLine() }}\n</read-files>"
        }
        if (modified.isNotEmpty()) {
            sections += "<modified-files>\n${modified.joinToString("\n")}\n</modified-files>"
        }
        return sections.joinToString("\n\n")
    }

    private fun collectFileCheckpoint(events: List<MessageEventRecord>): Pair<Map<String, ReadCursor>, Set<String>> {
        val reads = linkedMapOf<String, ReadCursor>()
        val modified = linkedSetOf<String>()
        events.forEach { event ->
            if (event.eventType != MessageEventType.TOOL_CALL) return@forEach
            val context = event.context ?: return@forEach
            val name = context.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val arguments = context.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val path = arguments.get("path")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            when (name) {
                "read" -> reads[path] = readCursor(path, arguments, context.get("result"))
                "write", "edit" -> modified += path
            }
        }
        modified.forEach { reads.remove(it) }
        return reads to modified
    }

    private fun readCursor(path: String, arguments: JsonObject, result: JsonElement?): ReadCursor {
        val offset = jsonLong(arguments, "offset")?.takeIf { it > 0 } ?: 1
        val limit = jsonLong(arguments, "limit")?.takeIf { it > 0 }
        val resultText = toolResultText(result)
        val nextOffset = continueOffset(resultText)
        val truncated = nextOffset != null
            || resultText.contains("exceeds the 50KB limit")
            || resultText.contains("limit reached")
        return ReadCursor(path, offset, limit, truncated, nextOffset)
    }

    private fun toolResultText(result: JsonElement?): String = when {
        result == null || result.isJsonNull -> ""
        result.isJsonPrimitive && result.asJsonPrimitive.isString -> result.asString
        result.isJsonObject -> result.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        else -> ""
    }

    private fun continueOffset(result: String): Long? {
        val marker = "Use offset="
        val rest = result.substringAfter(marker, missingDelimiterValue = "")
        if (rest.isEmpty()) return null
        val digits = rest.takeWhile { it.isDigit() }
        return digits.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun jsonLong(value: JsonObject, key: String): Long? {
        val raw = value.get(key) ?: return null
        if (!raw.isJsonPrimitive) return null
        val primitive = raw.asJsonPrimitive
        return when {
            primitive.isNumber -> primitive.asLong
            primitive.isString -> primitive.asString.toLongOrNull()
            else -> null
        }
    }
}
