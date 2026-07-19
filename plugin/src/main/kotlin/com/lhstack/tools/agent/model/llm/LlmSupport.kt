package com.lhstack.tools.agent.model.llm

import com.lhstack.tools.ext.gson

/**
 * llm 层共享 helper。照抄 awake-claw common/output.rs 的 format_tool_result_content /
 * format_reasoning，供 OpenAI / Anthropic 消息转换与结构化输出复用。
 */
object LlmSupport {

    /** 照抄 format_tool_result_content：text 取原文，image 序列化成 JSON，多块以换行拼接。 */
    fun formatToolResultContent(content: List<ToolResultContent>): String =
        content.joinToString("\n") { item ->
            when (item) {
                is ToolResultContent.Text -> item.text
                is ToolResultContent.Image -> gson.toJson(item.image)
            }
        }

    /** 照抄 format_reasoning：空则空串，有 id 时前缀 [id]。 */
    fun formatReasoning(reasoning: Reasoning): String {
        val text = reasoning.displayText()
        if (text.trim().isEmpty()) {
            return ""
        }
        val id = reasoning.id
        return if (id != null) "[$id] $text" else text
    }
}
