package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

/**
 * providers 表实体。字段照搬 awake-claw providers 表：
 * id/name/kind/api_key/base_url/api/anthropic_version/provider_config/enabled/created_at/updated_at。
 *
 * provider_config 存 JSON 文本，具体结构由上层解析（对齐 awake ProviderConfig）。
 */
@TableName("providers")
class ProviderEntity {

    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("name")
    var name: String = ""

    /** openai / anthropic 等，对齐 awake provider.kind。 */
    @TableField("kind")
    var kind: String = ""

    @TableField("api_key")
    var apiKey: String? = null

    @TableField("base_url")
    var baseUrl: String? = null

    /** 默认 api：completions / responses（anthropic 为空）。 */
    @TableField("api")
    var api: String? = null

    @TableField("anthropic_version")
    var anthropicVersion: String? = null

    /** JSON 文本，对应 awake provider_config。 */
    @TableField("provider_config")
    var providerConfig: String = "{}"

    @TableField("enabled")
    var enabled: Int = 1

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    var updatedAt: String? = null
}
