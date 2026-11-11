package com.lhstack.tools.components

import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.tabs.TabInfo

/** HtmlChunk overload is absent on 251; keep the old overload confined to API selection. */
internal object TabTooltipSupport {
    private val setter = TabInfo::class.java.methods.firstOrNull {
        it.name == "setTooltipText" && it.parameterTypes.contentEquals(arrayOf(HtmlChunk::class.java))
    } ?: TabInfo::class.java.getMethod("setTooltipText", String::class.java)

    fun setText(tab: TabInfo, text: String) {
        val html = HtmlChunk.text(text)
        val value = if (setter.parameterTypes[0] == HtmlChunk::class.java) html else html.toString()
        setter.invoke(tab, value)
    }
}
