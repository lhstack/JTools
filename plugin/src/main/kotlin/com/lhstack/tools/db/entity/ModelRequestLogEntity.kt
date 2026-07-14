package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

/**
 * model_request_logs 表实体。字段照搬 awake-claw model_request_logs 表。
 *
 * request_data / response_data 为 JSON 文本（写入前经 ModelLogSupport 脱敏）；
 * error_data 为截断后的错误文本。started_at 在 create 时写入，finished_at 在结束时写入。
 */
@TableName("model_request_logs")
class ModelRequestLogEntity {

    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("source_type")
    var sourceType: String = ""

    @TableField("source_id")
    var sourceId: String? = null

    @TableField("agent_id")
    var agentId: Long? = null

    @TableField("message_type")
    var messageType: String? = null

    @TableField("provider_id")
    var providerId: Long? = null

    @TableField("provider_name")
    var providerName: String? = null

    @TableField("model_id")
    var modelId: Long? = null

    @TableField("model_name")
    var modelName: String? = null

    @TableField("status")
    var status: String = "running"

    @TableField("request_data")
    var requestData: String = "{}"

    @TableField("response_data")
    var responseData: String = "{}"

    @TableField("error_data")
    var errorData: String? = null

    @TableField("started_at")
    var startedAt: String? = null

    @TableField("finished_at")
    var finishedAt: String? = null

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null
}
