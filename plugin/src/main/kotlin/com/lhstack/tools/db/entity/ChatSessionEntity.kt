package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

/**
 * message_sessions 表实体。对齐 awake-claw 编码会话：
 * 表级 session_type 固定为 coding；可见性、工作区、模型快照全部放在 config JSON。
 */
@TableName("message_sessions")
class ChatSessionEntity {

    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("session_type")
    var sessionType: String = "coding"

    @TableField("title")
    var title: String = ""

    @TableField("config")
    var config: String = "{}"

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    var updatedAt: String? = null
}
