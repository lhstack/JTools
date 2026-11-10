package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import com.lhstack.tools.agent.AgentAttachmentKind
import com.lhstack.tools.agent.AgentAttachmentState
import com.lhstack.tools.agent.AgentAttachmentSupport
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.UserContent
import java.nio.file.Files
import java.nio.file.Paths

/** 编码会话附件进模型请求。 当前轮和追加输入仅发送元数据，资源通过 view_* 工具按需读取。 历史轮：只回放元数据，不再把图片/文件二进制重放进上下文。 */
object CodingAttachmentPrompt {

    fun currentUserMessage(
        content: String,
        attachments: List<MessageAttachmentRecord>,
        modalities: Collection<String>,
    ): Message {
        val parts = mutableListOf<UserContent>()
        if (content.isNotBlank()) parts += UserContent.Text(content)
        attachments.forEach { record ->
            parts += AgentAttachmentSupport.metadataContent(toDraft(record))
        }
        if (parts.isEmpty()) parts += UserContent.Text("")
        return Message.User(parts)
    }

    fun historicalUserMessage(content: String, attachmentItems: List<JsonObject>): Message {
        val parts = mutableListOf<UserContent>()
        if (content.isNotBlank()) parts += UserContent.Text(content)
        attachmentItems.forEach { item ->
            parts +=
                UserContent.Text(
                    AgentAttachmentSupport.metadataPrompt(
                        fileName = string(item, "file_name"),
                        contentType = string(item, "content_type"),
                        size = item.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
                        kind = string(item, "kind").ifBlank { AgentAttachmentKind.FILE.id },
                        path = string(item, "path"),
                        uploadedAt =
                            string(item, "created_at").ifBlank { string(item, "uploaded_at") },
                    )
                )
        }
        if (parts.isEmpty()) parts += UserContent.Text("")
        return Message.User(parts)
    }

    fun historicalUserMessageFromEvent(context: JsonObject): Message {
        val content = context.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val items =
            context
                .get("attachment_items")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
                .orEmpty()
        return historicalUserMessage(content, items)
    }

    private fun toDraft(record: MessageAttachmentRecord): AgentAttachmentState {
        val path = record.path
        val size =
            if (record.size > 0) record.size
            else runCatching { Files.size(Paths.get(path)) }.getOrDefault(0)
        return AgentAttachmentState(
            id = record.id.toString(),
            name = record.fileName,
            path = path,
            mimeType = record.contentType,
            size = size,
            kind = record.kind.ifBlank { AgentAttachmentKind.FILE.id },
        )
    }

    private fun string(obj: JsonObject, key: String): String =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
