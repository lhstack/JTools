package com.lhstack.tools.agent

import java.util.UUID

/** 附件类型。按 MIME / 扩展名归类，决定发送时如何转成模型内容块。 */
enum class AgentAttachmentKind(val id: String) {
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video"),
    FILE("file");

    companion object {
        fun fromId(id: String?): AgentAttachmentKind = entries.firstOrNull { it.id == id } ?: FILE
    }
}

/** 会话草稿附件。仅在发送当次使用，不做持久化；回显依赖 model_logs 的请求快照。 */
data class AgentAttachmentState(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var path: String = "",
    var mimeType: String = "",
    var size: Long = 0,
    var kind: String = AgentAttachmentKind.FILE.id,
)
