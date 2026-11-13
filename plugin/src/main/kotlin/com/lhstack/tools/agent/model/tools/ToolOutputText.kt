package com.lhstack.tools.agent.model.tools

import com.google.gson.Gson
import com.google.gson.JsonElement

/** Count Unicode characters in serialized tool results. */
internal object ToolOutputText {
    private val gson = Gson()

    fun count(text: String) = text.codePointCount(0, text.length)
    fun size(value: JsonElement) = count(gson.toJson(value))
}
