package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

/**
 * prompt_templates 表实体。字段照搬 awake-claw prompt_templates 表。
 */
@TableName("prompt_templates")
class PromptTemplateEntity {

    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("name")
    var name: String = ""

    @TableField("preamble")
    var preamble: String = ""

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    var updatedAt: String? = null
}
