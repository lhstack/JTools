package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.FieldFill
import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

@TableName("coding_environments")
class CodingEnvironmentEntity {
    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("name")
    var name: String = ""

    @TableField("enabled")
    var enabled: Int = 1

    @TableField("config")
    var config: String = "{}"

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    var createdAt: String? = null

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    var updatedAt: String? = null
}
