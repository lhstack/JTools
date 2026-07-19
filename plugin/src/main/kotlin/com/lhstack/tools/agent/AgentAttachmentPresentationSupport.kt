package com.lhstack.tools.agent

import java.io.File
import kotlin.math.max

object AgentAttachmentPresentationSupport {

    fun userMessagePreview(text: String, attachments: List<AgentAttachmentState>): String {
        val trimmed = text.trim()
        if (attachments.isEmpty()) {
            return trimmed
        }
        val header = "已附加 ${attachments.size} 个文件:"
        val lines = attachments.joinToString("\n") { attachment ->
            "- ${kindLabel(attachment)} ${attachment.name.ifBlank { File(attachment.path).name.ifBlank { "附件" } }}${
                sizeLabel(
                    attachment.size
                )
            }"
        }
        return listOf(trimmed.takeIf { it.isNotBlank() }, header, lines)
            .filterNotNull()
            .joinToString("\n")
    }

    fun chipTitle(attachment: AgentAttachmentState, maxLength: Int = 22): String {
        val name = attachment.name.ifBlank { File(attachment.path).name.ifBlank { "附件" } }
        if (name.length <= maxLength) {
            return name
        }
        return name.take((maxLength - 3).coerceAtLeast(1)) + "..."
    }

    fun chipTooltip(attachment: AgentAttachmentState): String {
        val name = attachment.name.ifBlank { File(attachment.path).name.ifBlank { "附件" } }
        return buildString {
            append(name)
            attachment.path.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(it)
            }
            if (AgentAttachmentKind.fromId(attachment.kind) == AgentAttachmentKind.FILE) {
                append("\n")
                append("普通文件会先作为附件上下文注入；如启用工具，可继续读取真实内容。")
            }
        }
    }

    private fun kindLabel(attachment: AgentAttachmentState): String {
        return when (AgentAttachmentKind.fromId(attachment.kind)) {
            AgentAttachmentKind.IMAGE -> "[图片]"
            AgentAttachmentKind.AUDIO -> "[音频]"
            AgentAttachmentKind.VIDEO -> "[视频]"
            AgentAttachmentKind.FILE -> "[文件]"
        }
    }

    private fun sizeLabel(size: Long): String {
        if (size <= 0) {
            return ""
        }
        val kb = max(1, size / 1024)
        return " (${kb} KB)"
    }
}
