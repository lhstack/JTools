package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.FieldStrategy
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

/**
 * agents 表实体。字段照搬 awake-claw agents 表。
 *
 * runtime_params / ext_config / distill_config / tags 均为 JSON 文本，
 * 具体结构由 AgentConfigs 解析（对齐 awake AgentRuntimeConfig 等）。
 */
@TableName("agents")
class AgentEntity {

    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("name")
    var name: String = ""

    @TableField("description")
    var description: String? = null

    @TableField("enabled")
    var enabled: Int = 1

    @TableField("provider_id")
    var providerId: Long? = null

    @TableField("model_id")
    var modelId: Long? = null

    @TableField(value = "prompt_id", updateStrategy = FieldStrategy.IGNORED)
    var promptId: Long? = null

    @TableField("extra_prompt")
    var extraPrompt: String? = null

    /** JSON：AgentRuntimeConfig。 */
    @TableField("runtime_params")
    var runtimeParams: String = "{}"

    /** JSON：AgentCapabilityConfig。 */
    @TableField("ext_config")
    var extConfig: String = "{}"

    /** JSON：AgentDistillConfig。 */
    @TableField("distill_config")
    var distillConfig: String = "{}"

    /** text / json 等输出模式。 */
    @TableField("output_mode")
    var outputMode: String = "text"

    @TableField("max_runtime_secs")
    var maxRuntimeSecs: Long? = null

    /** JSON 数组文本。 */
    @TableField("tags")
    var tags: String = "[]"

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    var updatedAt: String? = null
}
