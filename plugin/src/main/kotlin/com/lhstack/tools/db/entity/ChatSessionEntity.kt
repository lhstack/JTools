package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

/**
 * chat_sessions 表实体。会话只承载「会话名 + 关联 Agent」。
 *
 * 请求与响应的持久化全部落在 model_request_logs（source_id = "$agentId:$sessionId"），
 * 会话本身不再存储消息或附件，因此不设 chat_messages / chat_attachments。
 */
@TableName("chat_sessions")
class ChatSessionEntity {

    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("title")
    var title: String? = null

    @TableField("agent_id")
    var agentId: Long? = null

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    var updatedAt: String? = null
}
