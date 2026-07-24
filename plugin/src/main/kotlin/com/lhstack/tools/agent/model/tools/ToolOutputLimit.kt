package com.lhstack.tools.agent.model.tools

import java.nio.charset.StandardCharsets

/** Limits tool results at the boundary before they are sent back to the model. */
internal object ToolOutputLimit {
    const val MAX_BYTES = 8 * 1024
    private const val MAX_KILOBYTES = MAX_BYTES / 1024

    /**
     * 把超限输出截断到 MAX_BYTES（按 UTF-8 字节），并在末尾追加截断说明，
     * 让模型知道结果被截断以及下一步如何拿到完整内容；未超限则原样返回。
     *
     * 与旧的 requireWithinLimit 不同：不再整段丢弃，而是保留前 MAX_BYTES 的可用内容。
     */
    fun truncateToLimit(toolName: String, output: String): String {
        val bytes = output.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= MAX_BYTES) return output
        val note = truncationNote(toolName, bytes.size)
        val noteBytes = note.toByteArray(StandardCharsets.UTF_8).size
        // 预留出说明所需字节，保证"截断内容 + 说明"整体仍在 MAX_BYTES 内。
        val budget = (MAX_BYTES - noteBytes).coerceAtLeast(0)
        val head = decodeUtf8Prefix(bytes, budget)
        return head + note
    }

    /**
     * 从 UTF-8 字节数组安全解码前 limit 字节内的最长完整前缀，
     * 避免在多字节字符中间截断产生乱码（末尾不完整的字符会被丢弃）。
     */
    private fun decodeUtf8Prefix(bytes: ByteArray, limit: Int): String {
        if (limit <= 0) return ""
        var end = minOf(limit, bytes.size)
        // UTF-8 续字节形如 10xxxxxx；回退到字符边界，避免半个字符。
        while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(bytes, 0, end, StandardCharsets.UTF_8)
    }

    private fun truncationNote(toolName: String, totalBytes: Int): String {
        val totalKb = (totalBytes + 1023) / 1024
        return "\n\n[输出被截断：完整结果约 ${totalKb}KB，已只返回前 ${MAX_KILOBYTES}KB。" +
            hint(toolName) + "]"
    }

    private fun hint(toolName: String): String = when (toolName) {
        "search_project_text" ->
            "如需更多结果，请缩小范围：使用 file_name_glob、降低 max_results 或 context_lines，或拆分搜索关键词。"
        "find_project_files", "find_project_classes" ->
            "如需更多结果，请使用更精确的 queries 或降低 max_results_per_query，分批查找。"
        "bash" ->
            "如需完整输出，请用 head、tail、sed、grep 等缩小结果，或先写入工作区文件再用 read_project_files 分段读取。"
        else -> "如需完整内容，请缩小查询范围后分批读取。"
    }
}
