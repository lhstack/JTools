package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

/**
 * models 表实体。字段照搬 awake-claw models 表。
 * modalities/model_params/execution_params/additional_params 均为 JSON 文本。
 */
@TableName("models")
class ModelEntity {

    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("provider_id")
    var providerId: Long = 0

    @TableField("alias")
    var alias: String = ""

    @TableField("model_id")
    var modelId: String = ""

    @TableField("display_name")
    var displayName: String? = null

    /** completions / responses（anthropic 为空）。 */
    @TableField("api")
    var api: String? = null

    @TableField("context_window")
    var contextWindow: Long? = null

    /** JSON 数组文本，如 ["text","image"]。 */
    @TableField("modalities")
    var modalities: String = "[]"

    @TableField("model_params")
    var modelParams: String? = null

    @TableField("execution_params")
    var executionParams: String? = null

    @TableField("additional_params")
    var additionalParams: String? = null

    @TableField("enabled")
    var enabled: Int = 1

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    var updatedAt: String? = null
}
