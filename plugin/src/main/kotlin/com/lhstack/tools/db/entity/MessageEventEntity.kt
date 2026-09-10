package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

@TableName("message_events")
class MessageEventEntity {
    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("parent_event_id")
    var parentEventId: Long? = null

    @TableField("session_id")
    var sessionId: Long = 0

    @TableField("turn_id")
    var turnId: String = ""

    @TableField("status")
    var status: String = "running"

    @TableField("event_type")
    var eventType: String = ""

    @TableField("event_id")
    var eventId: String = ""

    @TableField("summary")
    var summary: String = ""

    @TableField("context")
    var context: String = "{}"

    @TableField("revision")
    var revision: Int = 1

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    var updatedAt: String? = null
}

@TableName("message_processing_tasks")
class MessageProcessingTaskEntity {
    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("session_id")
    var sessionId: Long = 0

    @TableField("turn_id")
    var turnId: String = ""

    @TableField("status")
    var status: String = "pending"

    @TableField("execution_config")
    var executionConfig: String = "{}"

    @TableField("error")
    var error: String? = null

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField("claimed_at")
    var claimedAt: String? = null

    @TableField("completed_at")
    var completedAt: String? = null
}

@TableName("message_append_items")
class MessageAppendItemEntity {
    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("session_id")
    var sessionId: Long = 0

    @TableField("turn_id")
    var turnId: String = ""

    @TableField("message_event_id")
    var messageEventId: Long? = null

    @TableField("sequence")
    var sequence: Long = 0

    @TableField("content")
    var content: String = ""

    @TableField("attachments")
    var attachments: String = "[]"

    @TableField("status")
    var status: String = "pending"

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField("claimed_at")
    var claimedAt: String? = null

    @TableField("delivered_at")
    var deliveredAt: String? = null
}

@TableName("message_attachments")
class MessageAttachmentEntity {
    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("session_id")
    var sessionId: Long = 0

    @TableField("file_name")
    var fileName: String = ""

    @TableField("content_type")
    var contentType: String = ""

    @TableField("size")
    var size: Long = 0

    @TableField("path")
    var path: String = ""

    @TableField("kind")
    var kind: String = "file"

    @TableField("text_preview")
    var textPreview: String? = null

    @TableField("metadata")
    var metadata: String = "{}"

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null
}


@TableName("context_compactions")
class ContextCompactionEntity {
    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("session_type")
    var sessionType: String = "coding"

    @TableField("session_id")
    var sessionId: Long = 0

    @TableField("agent_id")
    var agentId: Long = 0

    @TableField("status")
    var status: String = "running"

    @TableField("estimated_tokens_before")
    var estimatedTokensBefore: Long = 0

    @TableField("estimated_tokens_after")
    var estimatedTokensAfter: Long? = null

    @TableField("summary")
    var summary: String = ""

    @TableField("model_log_id")
    var modelLogId: Long? = null

    @TableField("error")
    var error: String? = null

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField("completed_at")
    var completedAt: String? = null
}
