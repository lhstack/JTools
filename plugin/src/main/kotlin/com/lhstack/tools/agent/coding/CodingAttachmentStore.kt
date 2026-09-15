package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import com.lhstack.tools.agent.AgentAttachmentKind
import com.lhstack.tools.agent.AgentAttachmentState
import com.lhstack.tools.agent.AgentAttachmentSupport
import com.lhstack.tools.const.Const
import com.lhstack.tools.db.service.MessageStoreService
import java.io.File

object CodingAttachmentStore {
    fun persist(sessionId: Long, draft: AgentAttachmentState): Long {
        val normalized = AgentAttachmentSupport.normalize(draft)
        require(normalized.path.isNotBlank()) { "附件路径不能为空" }
        val source = File(normalized.path)
        require(source.isFile) { "附件文件不存在：${normalized.path}" }
        val destDir = File(Const.JTOOLS_PLUGIN_HOME, "coding-attachments/$sessionId").apply { mkdirs() }
        val dest = File(destDir, "${normalized.id}-${normalized.name.ifBlank { source.name }}")
        source.copyTo(dest, overwrite = true)
        return MessageStoreService.createAttachment(
            sessionId = sessionId,
            fileName = normalized.name.ifBlank { dest.name },
            contentType = normalized.mimeType,
            size = dest.length(),
            path = dest.absolutePath,
            kind = AgentAttachmentKind.fromId(normalized.kind).id,
            textPreview = null,
            metadata = JsonObject(),
        )
    }
}
