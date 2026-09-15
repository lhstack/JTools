package com.lhstack.tools.agent

import com.google.gson.annotations.SerializedName

internal class AgentToolItem(
    val id: String,
    val name: String,
    val args: String,
    var result: String = "",
    var finished: Boolean = false,
    var failed: Boolean = false,
)

internal data class AgentBrowserTool(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("finished") val finished: Boolean,
    @SerializedName("failed") val failed: Boolean,
)

internal data class AgentBrowserToolDetail(
    @SerializedName("args") val args: String,
    @SerializedName("result") val result: String,
)

internal const val MAX_TOOL_DETAIL_CHARS = 16_384

internal fun toolDetailText(value: String): String {
    if (value.length <= MAX_TOOL_DETAIL_CHARS) return value
    val omitted = value.length - MAX_TOOL_DETAIL_CHARS
    return value.take(MAX_TOOL_DETAIL_CHARS) + "\n\n[内容已截断，原始内容还剩 $omitted 个字符；请使用专门工具按范围读取。]"
}

internal fun toolDetail(args: String, result: String): AgentBrowserToolDetail =
    AgentBrowserToolDetail(toolDetailText(args), toolDetailText(result))
