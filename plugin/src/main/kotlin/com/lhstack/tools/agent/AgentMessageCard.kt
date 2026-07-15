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
    private val toolDetailLoader: ((String) -> AgentBrowserToolDetail?)? = null,
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
            AgentBrowserTool(it.id, it.name, it.finished, it.failed)
        },
        generating = generating,
        createdAt = compactCreatedAt(createdAt),
        usage = usage,
        usageDetails = usageDetails,
        deletable = onDelete != null,
    )

    fun toolDetail(callId: String): AgentBrowserToolDetail? =
        toolDetailLoader?.invoke(callId) ?: tools[callId]?.let { AgentBrowserToolDetail(it.args, it.result) }

    override fun delete() = onDelete?.invoke() ?: Unit

    private fun changed() = onChanged?.invoke()
}


internal class AgentRunMessageCard(
    private val runId: String,
    private val agentId: Long,
    private val agentName: String,
    private val receiver: String,
    private val onDelete: (() -> Unit)? = null,
    private val toolDetailLoader: (String, String) -> AgentBrowserToolDetail? = { _, _ -> null },
    override val id: String = "agent-run-$runId",
) : AgentChatCard {
    private var status: String = "running"
    private var latestPrompt: String = ""
    private var response: String = ""
    private var reasoning: String = ""
    private var error: String? = null
    private var createdAt: String? = null
    private var tools: List<AgentBrowserTool> = emptyList()
    internal var onChanged: (() -> Unit)? = null

    fun update(snapshot: AgentRunSnapshot) {
        status = snapshot.status
        latestPrompt = snapshot.prompt
        response = snapshot.response
        reasoning = snapshot.reasoning
        error = snapshot.error
        createdAt = snapshot.createdAt
        tools = snapshot.tools
        onChanged?.invoke()
    }

    override fun toBrowserMessage() = AgentBrowserMessage(
        id = id,
        role = "agent_run",
        content = response,
        reasoning = reasoning,
        tools = tools,
        generating = status == "running",
        createdAt = compactCreatedAt(createdAt),
        deletable = onDelete != null,
        messageType = "agent_run",
        agentRun = AgentBrowserRun(runId, agentId, agentName, receiver, latestPrompt, status, error),
    )

    fun toolDetail(callId: String): AgentBrowserToolDetail? = toolDetailLoader(runId, callId)

    override fun delete() = onDelete?.invoke() ?: Unit
}

internal data class AgentBrowserRun(
    @SerializedName("runId") val runId: String,
    @SerializedName("agentId") val agentId: Long,
    @SerializedName("agentName") val agentName: String,
    @SerializedName("receiver") val receiver: String,
    @SerializedName("prompt") val prompt: String,
    @SerializedName("status") val status: String,
    @SerializedName("error") val error: String?,
)

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
    @SerializedName("messageType") val messageType: String = "message",
    @SerializedName("agentRun") val agentRun: AgentBrowserRun? = null,
)


internal data class AgentBrowserUsageItem(
    @SerializedName("label") val label: String,
    @SerializedName("value") val value: Long,
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

internal data class AgentBrowserAttachment(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("size") val size: Long,
    @SerializedName("kind") val kind: String,
    @SerializedName("previewUrl") val previewUrl: String? = null,
)

internal fun AgentAttachmentState.toBrowserAttachment() = AgentBrowserAttachment(
    id, name, path, mimeType, size, kind,
    previewUrl = takeIf { kind == AgentAttachmentKind.IMAGE.id }
        ?.let { attachment -> imagePreviewUrl(attachment) },
)

internal fun imagePreviewUrl(attachment: AgentAttachmentState): String? = runCatching {
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


