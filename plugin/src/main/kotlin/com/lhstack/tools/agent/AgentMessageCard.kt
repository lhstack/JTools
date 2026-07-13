package com.lhstack.tools.agent

import com.google.gson.annotations.SerializedName
import java.util.UUID

internal sealed interface AgentChatCard {
    val id: String
    fun toBrowserMessage(): AgentBrowserMessage
    fun delete()
}

internal class AgentUserMessageCard(
    private val content: String,
    private val attachments: List<AgentAttachmentState> = emptyList(),
    private val onDelete: (() -> Unit)? = null,
    private val createdAt: String? = null,
    override val id: String = "user-${UUID.randomUUID()}",
) : AgentChatCard {
    override fun toBrowserMessage() = AgentBrowserMessage(
        id = id,
        role = "user",
        content = content,
        attachments = attachments.map(AgentAttachmentState::toBrowserAttachment),
        createdAt = compactCreatedAt(createdAt),
        deletable = onDelete != null,
    )

    override fun delete() = onDelete?.invoke() ?: Unit
}

internal class AgentAssistantMessageCard(
    @Suppress("UNUSED_PARAMETER") showToolDetail: (AgentToolItem, javax.swing.JComponent) -> Unit,
    private val onDelete: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") private val onCopyCode: (() -> Unit)? = null,
    override val id: String = "assistant-${UUID.randomUUID()}",
) : AgentChatCard {
    private var response = ""
    private var reasoning = ""
    private var reasoningExpanded = true
    private var generating = true
    private var createdAt: String? = null
    private var usage: String? = null
    private var usageDetails: List<AgentBrowserUsageItem> = emptyList()
    private val tools = linkedMapOf<String, AgentToolItem>()
    internal var onChanged: (() -> Unit)? = null

    fun appendResponse(text: String) {
        if (text.isEmpty()) return
        response += normalizeLineBreaks(text)
        changed()
    }

    fun setResponse(text: String) {
        response = normalizeBlockText(text)
        changed()
    }

    fun setResponseIfEmpty(text: String) {
        if (response.isBlank()) setResponse(text)
    }

    fun appendReasoning(text: String) {
        if (text.isEmpty()) return
        reasoning += normalizeLineBreaks(text)
        changed()
    }

    fun setReasoning(text: String, expanded: Boolean) {
        reasoning = normalizeBlockText(text)
        reasoningExpanded = expanded
        changed()
    }

    fun ensureTool(callId: String, name: String, args: String): AgentToolItem =
        tools[callId] ?: AgentToolItem(callId, name.ifBlank { "工具" }, args).also {
            tools[callId] = it
            changed()
        }

    fun updateToolResult(callId: String, result: String) {
        val item = tools[callId] ?: return
        item.result = normalizeBlockText(result)
        item.finished = true
        item.failed = toolResultFailed(item.result)
        changed()
    }

    fun finish(createdAt: String?, usageText: String?, details: List<AgentBrowserUsageItem> = emptyList()) {
        generating = false
        this.createdAt = createdAt
        usage = usageText
        usageDetails = details
        changed()
    }

    override fun toBrowserMessage() = AgentBrowserMessage(
        id = id,
        role = "assistant",
        content = response,
        reasoning = reasoning,
        reasoningExpanded = reasoningExpanded,
        tools = tools.values.map {
            AgentBrowserTool(it.id, it.name, it.args, it.result, it.finished, it.failed)
        },
        generating = generating,
        createdAt = compactCreatedAt(createdAt),
        usage = usage,
        usageDetails = usageDetails,
        deletable = onDelete != null,
    )

    override fun delete() = onDelete?.invoke() ?: Unit

    private fun changed() = onChanged?.invoke()
}

internal class AgentToolItem(
    val id: String,
    val name: String,
    val args: String,
    var result: String = "",
    var finished: Boolean = false,
    var failed: Boolean = false,
)

internal data class AgentBrowserMessage(
    @SerializedName("id") val id: String,
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String,
    @SerializedName("reasoning") val reasoning: String = "",
    @SerializedName("reasoningExpanded") val reasoningExpanded: Boolean = false,
    @SerializedName("tools") val tools: List<AgentBrowserTool> = emptyList(),
    @SerializedName("attachments") val attachments: List<AgentBrowserAttachment> = emptyList(),
    @SerializedName("generating") val generating: Boolean = false,
    @SerializedName("createdAt") val createdAt: String? = null,
    @SerializedName("usage") val usage: String? = null,
    @SerializedName("usageDetails") val usageDetails: List<AgentBrowserUsageItem> = emptyList(),
    @SerializedName("deletable") val deletable: Boolean = false,
)


internal data class AgentBrowserUsageItem(
    @SerializedName("label") val label: String,
    @SerializedName("value") val value: Long,
)

internal data class AgentBrowserTool(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("args") val args: String,
    @SerializedName("result") val result: String,
    @SerializedName("finished") val finished: Boolean,
    @SerializedName("failed") val failed: Boolean,
)

internal data class AgentBrowserAttachment(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("size") val size: Long,
    @SerializedName("kind") val kind: String,
    @SerializedName("previewUrl") val previewUrl: String? = null,
)

private fun AgentAttachmentState.toBrowserAttachment() = AgentBrowserAttachment(
    id, name, path, mimeType, size, kind,
    previewUrl = takeIf { kind == AgentAttachmentKind.IMAGE.id }
        ?.let { attachment -> imagePreviewUrl(attachment) },
)

private fun imagePreviewUrl(attachment: AgentAttachmentState): String? = runCatching {
    val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(attachment.path))
    "data:${attachment.mimeType.ifBlank { "image/png" }};base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"
}.getOrNull()

private fun toolResultFailed(result: String): Boolean =
    result.startsWith("工具调用失败:") || result.startsWith("异常:") || result.startsWith("错误:") || result == "用户手动取消"

private fun normalizeBlockText(text: String): String = normalizeLineBreaks(text).trim()
private fun normalizeLineBreaks(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
private fun compactCreatedAt(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return null
    return text.replace('T', ' ').substringBefore('.').takeIf { it.isNotBlank() }
}
