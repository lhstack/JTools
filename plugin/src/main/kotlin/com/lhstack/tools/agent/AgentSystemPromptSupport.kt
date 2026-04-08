package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.xmlb.annotations.Tag
import java.util.UUID

@Tag("agent-system-prompt")
class AgentSystemPromptState {
    var id: String = ""
    var name: String = ""
    var content: String = ""
}

object AgentSystemPromptSupport {
    const val DEFAULT_PROMPT_ID = "__default__"
    const val DEFAULT_PROMPT_NAME = "默认提示词"

    private const val DEFAULT_PROMPT_CONTENT = """
你是 JTools 智能体。你的首要任务是理解用户真实意图，并根据上下文自主决定下一步最合适的动作，例如直接回答、提出一个关键澄清问题、读取信息、调用工具或继续分解任务。

你可以调用工具完成任务。插件工具名称以 plugin_ 开头，系统工具名称以 jtools_ 开头。

行为原则：
1. 对于读取、分析、总结、检索、只读查询、低风险且可逆的操作，默认直接执行，不要等待用户逐步指挥。
2. 如果用户目标明确且所需信息充分，先用一句简短的话说明你的判断和下一步，然后立即执行。
3. 如果用户意图存在关键歧义、信息不足，且不同解释会导致不同结果，先提出一个最关键的问题再继续，不要自行猜测。
4. 优先根据证据和上下文推进任务，不要机械复述用户要求，不要无意义地确认显而易见的下一步。

危险操作：
以下操作必须先获得用户明确确认后才能执行：删除、覆盖、批量修改本地内容；执行会影响真实环境的外部操作；具有不可逆后果、额外成本、权限风险或数据风险的操作。
如果存在风险，先明确说明将执行什么、为什么有风险，并等待用户确认。

工具调用要求：
- 调用工具时，参数必须始终是合法 JSON 对象。
- 避免连续重复调用同一个工具；如果无法获得新信息，应停止并向用户说明当前限制。
- 在调用工具前，只保留必要说明，不输出冗长过程描述。
"""

    fun normalizePrompts(prompts: List<AgentSystemPromptState>): MutableList<AgentSystemPromptState> {
        val normalized = linkedMapOf<String, AgentSystemPromptState>()
        prompts.forEach { raw ->
            val candidateId = raw.id.trim().ifBlank { UUID.randomUUID().toString() }
            val prompt = AgentSystemPromptState().apply {
                id = if (candidateId == DEFAULT_PROMPT_ID) DEFAULT_PROMPT_ID else candidateId
                name = raw.name.trim()
                content = normalizeContent(raw.content)
            }
            if (prompt.id == DEFAULT_PROMPT_ID) {
                prompt.name = prompt.name.ifBlank { DEFAULT_PROMPT_NAME }
                prompt.content = prompt.content.ifBlank { DEFAULT_PROMPT_CONTENT.trim() }
            } else {
                if (prompt.name.isBlank()) {
                    prompt.name = "提示词"
                }
                if (prompt.content.isBlank()) {
                    prompt.content = DEFAULT_PROMPT_CONTENT.trim()
                }
            }
            normalized.putIfAbsent(prompt.id, prompt)
        }
        if (normalized[DEFAULT_PROMPT_ID] == null) {
            normalized[DEFAULT_PROMPT_ID] = createDefaultPrompt()
        }
        return buildList {
            add(normalized.getValue(DEFAULT_PROMPT_ID))
            normalized.values.filter { it.id != DEFAULT_PROMPT_ID }.forEach { add(it) }
        }.toMutableList()
    }

    fun resolvePromptContent(prompts: List<AgentSystemPromptState>, promptId: String?): String {
        val normalized = normalizePrompts(prompts)
        val selected = promptId?.trim().orEmpty()
        return normalized.firstOrNull { it.id == selected }?.content
            ?: normalized.first { it.id == DEFAULT_PROMPT_ID }.content
    }

    fun resolvePromptName(prompts: List<AgentSystemPromptState>, promptId: String?): String {
        val normalized = normalizePrompts(prompts)
        val selected = promptId?.trim().orEmpty()
        return normalized.firstOrNull { it.id == selected }?.name
            ?: normalized.first { it.id == DEFAULT_PROMPT_ID }.name
    }

    fun normalizeSelectedPromptId(promptId: String?, prompts: List<AgentSystemPromptState>): String {
        val selected = promptId?.trim().orEmpty()
        if (selected.isBlank() || selected == DEFAULT_PROMPT_ID) {
            return ""
        }
        return if (normalizePrompts(prompts).any { it.id == selected && it.id != DEFAULT_PROMPT_ID }) {
            selected
        } else {
            ""
        }
    }

    fun syncSessionSystemPrompt(session: AgentSessionState, prompts: List<AgentSystemPromptState>) {
        val normalizedId = normalizeSelectedPromptId(session.systemPromptId, prompts)
        session.systemPromptId = normalizedId
        val promptContent = resolvePromptContent(prompts, normalizedId)
        syncSystemPromptStrings(session.messages, promptContent)
    }

    fun syncSystemPromptStrings(messages: MutableList<String>, promptContent: String) {
        val normalizedContent = normalizeContent(promptContent)
        if (messages.isEmpty()) {
            messages.add(systemMessage(normalizedContent).toString())
            return
        }
        val first = messages.firstOrNull()?.let { raw ->
            runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        }
        if (first?.get("role")?.takeIf { !it.isJsonNull }?.asString == "system") {
            first.addProperty("content", normalizedContent)
            messages[0] = first.toString()
            return
        }
        messages.add(0, systemMessage(normalizedContent).toString())
    }

    fun syncSystemPromptJson(messages: MutableList<JsonObject>, promptContent: String) {
        val normalizedContent = normalizeContent(promptContent)
        if (messages.isEmpty()) {
            messages.add(systemMessage(normalizedContent))
            return
        }
        val first = messages.firstOrNull()
        if (first?.get("role")?.takeIf { !it.isJsonNull }?.asString == "system") {
            first.addProperty("content", normalizedContent)
            return
        }
        messages.add(0, systemMessage(normalizedContent))
    }

    fun createDefaultPrompt(): AgentSystemPromptState {
        return AgentSystemPromptState().apply {
            id = DEFAULT_PROMPT_ID
            name = DEFAULT_PROMPT_NAME
            content = DEFAULT_PROMPT_CONTENT.trim()
        }
    }

    fun customPrompts(prompts: List<AgentSystemPromptState>): List<AgentSystemPromptState> {
        return normalizePrompts(prompts).filter { it.id != DEFAULT_PROMPT_ID }
    }

    private fun systemMessage(promptContent: String): JsonObject {
        return JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", promptContent)
        }
    }

    private fun normalizeContent(content: String?): String {
        return content.orEmpty().replace("\r\n", "\n").trim()
    }
}
