package com.lhstack.tools.llm.provider

import com.google.gson.JsonElement

/** 将内部结构投影为文本；字符串原文不做二次 JSON 解析。 */
internal object ToolResultText {
    fun render(value: JsonElement): String = when {
        value.isJsonNull -> ""
        value.isJsonPrimitive -> value.asString
        value.isJsonArray -> value.asJsonArray.joinToString("\n") { render(it) }
        else -> value.asJsonObject.entrySet().joinToString("\n") { (key, field) ->
            val text = render(field)
            if (field.isJsonObject || field.isJsonArray) {
                if (text.isEmpty()) "$key:" else "$key:\n${text.prependIndent("  ")}"
            } else "$key: $text"
        }
    }
}
