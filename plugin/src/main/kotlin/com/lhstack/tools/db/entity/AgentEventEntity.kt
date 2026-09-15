package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

@TableName("agent_events")
class AgentEventEntity {
    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("parent_event_id")
    var parentEventId: Long? = null

    @TableField("agent_id")
    var agentId: Long = 0

    @TableField("turn_id")
    var turnId: String = ""

    @TableField("status")
    var status: String = "completed"

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
