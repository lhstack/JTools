package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableName

/**
 * global_config 表实体。字段照搬 awake-claw global_config 表：key 主键 + value 文本。
 * 存放全局设置（如 model.max_tool_call_rounds / model.max_retries）。
 */
@TableName("global_config")
class GlobalConfigEntity {

    @TableId(value = "key", type = IdType.INPUT)
    var key: String = ""

    @TableField("value")
    var value: String = ""
}
