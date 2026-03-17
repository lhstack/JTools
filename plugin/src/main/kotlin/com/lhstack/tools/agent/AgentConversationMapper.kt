package com.lhstack.tools.agent

import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock

object AgentConversationMapper {

    fun mapAttachments(attachments: List<AgentAttachmentState>): AgentAttachmentMappingResult {
        val mediaBlocks = mutableListOf<io.agentscope.core.message.ContentBlock>()
        val fileContext = mutableListOf<AgentFileContextEntry>()
        val blocked = mutableListOf<AgentAttachmentState>()
        attachments.forEach { draft ->
            val normalized = AgentAttachmentSupport.normalize(draft)
            val mediaBlock = AgentAttachmentSupport.toMediaBlock(normalized)
            if (mediaBlock != null) {
                mediaBlocks.add(mediaBlock)
                return@forEach
            }
            val contextEntry = AgentAttachmentSupport.toFileContext(normalized)
            if (contextEntry != null) {
                fileContext.add(contextEntry)
            } else {
                blocked.add(normalized)
            }
        }
        return AgentAttachmentMappingResult(
            mediaBlocks = mediaBlocks,
            fileContext = fileContext,
            blockedAttachments = blocked,
        )
    }

    fun mapUserMessage(
        text: String,
        attachments: List<AgentAttachmentState>,
        name: String = "user",
    ): AgentConversationDraft {
        val blocks = mutableListOf<io.agentscope.core.message.ContentBlock>()
        val fileContext = mutableListOf<AgentFileContextEntry>()
        val blocked = mutableListOf<AgentAttachmentState>()
        val normalizedText = text.trim()
        val metadata = linkedMapOf<String, Any>()
        val pendingTextParts = mutableListOf<String>()

        normalizedText.takeIf { it.isNotBlank() }?.let { pendingTextParts.add(it) }
        attachments.forEach { draft ->
            val normalized = AgentAttachmentSupport.normalize(draft)
            val mediaBlock = AgentAttachmentSupport.toMediaBlock(normalized)
            if (mediaBlock != null) {
                flushTextBlock(blocks, pendingTextParts)
                blocks.add(mediaBlock)
                return@forEach
            }
            val contextEntry = AgentAttachmentSupport.toFileContext(normalized)
            if (contextEntry != null) {
                fileContext.add(contextEntry)
                pendingTextParts.add(buildFileContextText(contextEntry))
            } else {
                blocked.add(normalized)
            }
        }
        flushTextBlock(blocks, pendingTextParts)
        if (fileContext.isNotEmpty()) {
            metadata["file_context"] = fileContext
        }
        val message = Msg.builder()
            .name(name)
            .role(MsgRole.USER)
            .content(blocks)
            .metadata(metadata)
            .build()
        return AgentConversationDraft(
            message = message,
            fileContext = fileContext,
            blockedAttachments = blocked,
        )
    }

    private fun flushTextBlock(
        blocks: MutableList<io.agentscope.core.message.ContentBlock>,
        pendingTextParts: MutableList<String>,
    ) {
        val combinedText = pendingTextParts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        if (combinedText.isNotBlank()) {
            blocks.add(TextBlock.builder().text(combinedText).build())
            pendingTextParts.clear()
        }
    }

    private fun buildFileContextText(entry: AgentFileContextEntry): String {
        return buildString {
            append("附件列表:\n")
            append("- ")
            append(entry.name)
            append(" (")
            append(entry.mimeType)
            append(")")
            if (!entry.content.isNullOrBlank()) {
                append("\n")
                append(entry.content.take(2000))
            }
        }.trim()
    }
}
