package com.lhstack.tools.llm.provider

import com.google.gson.JsonElement

/** 将内部结构投影为文本；字符串原文不做二次 JSON 解析。 */
internal object ToolResultText {
    private val nonEmptyLineStart = Regex("(?m)^(?!\\r?$)")
    /** 单行项紧凑排列；多行记录两侧保留段界，不改写记录内部。 */
    fun records(items: Iterable<String>): String = buildString {
        var previous: String? = null
        for (item in items) {
            previous?.let { append(if ('\n' in it || '\n' in item) "\n\n" else "\n") }
            append(item)
            previous = item
        }
    }

    fun render(value: JsonElement): String = when {
        value.isJsonNull -> ""
        value.isJsonPrimitive -> value.asString
        value.isJsonArray -> records(value.asJsonArray.map(::render))
        else -> value.asJsonObject.entrySet().joinToString("\n") { (key, field) ->
            val text = render(field)
            if (field.isJsonObject || field.isJsonArray) {
                if (text.isEmpty()) "$key:" else "$key:\n${text.replace(nonEmptyLineStart, "  ")}"
            } else "$key: $text"
        }
    }
}
