package com.lhstack.tools.agent

import com.intellij.util.xmlb.annotations.Tag
import java.util.UUID

enum class AgentAttachmentKind(val id: String) {
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video"),
    FILE("file");

    companion object {
        fun fromId(id: String?): AgentAttachmentKind {
            return entries.firstOrNull { it.id == id } ?: FILE
        }
    }
}

enum class AgentAttachmentDeliveryMode(val id: String) {
    AUTO("auto"),
    CONTENT_ONLY("content_only"),
    METADATA_ONLY("metadata_only");

    companion object {
        fun fromId(id: String?): AgentAttachmentDeliveryMode {
            return entries.firstOrNull { it.id == id } ?: AUTO
        }
    }
}

@Tag("attachment")
data class AgentAttachmentState(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var path: String = "",
    var mimeType: String = "",
    var size: Long = 0,
    var kind: String = AgentAttachmentKind.FILE.id,
    var deliveryMode: String = AgentAttachmentDeliveryMode.AUTO.id,
)

data class AgentFileContextEntry(
    val name: String,
    val mimeType: String,
    val path: String,
    val resourceRef: String,
    val content: String? = null,
    val metadataOnly: Boolean = false,
)

data class AgentAttachmentMappingResult(
    val mediaBlocks: List<io.agentscope.core.message.ContentBlock> = emptyList(),
    val fileContext: List<AgentFileContextEntry> = emptyList(),
    val blockedAttachments: List<AgentAttachmentState> = emptyList(),
)

data class AgentConversationDraft(
    val message: io.agentscope.core.message.Msg,
    val fileContext: List<AgentFileContextEntry>,
    val blockedAttachments: List<AgentAttachmentState>,
)
