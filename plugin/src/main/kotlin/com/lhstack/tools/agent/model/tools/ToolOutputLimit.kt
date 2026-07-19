package com.lhstack.tools.agent.model.tools

import java.nio.charset.StandardCharsets

/** Limits tool results at the boundary before they are sent back to the model. */
internal object ToolOutputLimit {
    const val MAX_BYTES = 8 * 1024
    private const val MAX_KILOBYTES = MAX_BYTES / 1024

    fun requireWithinLimit(toolName: String, output: String) {
        if (output.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) return
        throw ToolException(message(toolName))
    }

    fun message(toolName: String): String =
        "工具 `$toolName` 返回结果超过 ${MAX_KILOBYTES}KB，原始结果未返回。" +
            when (toolName) {
                "search_project_text" ->
                    "请缩小搜索范围：使用 file_name_glob，降低 max_results 或 context_lines，" +
                        "并拆分搜索关键词。"
                "find_project_files", "find_project_classes" ->
                    "请使用更精确的 queries 或降低 max_results_per_query，分批查找。"
                "bash" ->
                    "请限制命令输出：使用 head、tail、sed、grep 等命令缩小结果，" +
                        "或先将结果写入工作区文件，再使用 read_project_files 分段读取。"
                else -> "请缩小查询范围后分批读取。"
            } + "不要重复执行相同参数。"

    private fun resultTooLarge(toolName: String): String = message(toolName)
}
