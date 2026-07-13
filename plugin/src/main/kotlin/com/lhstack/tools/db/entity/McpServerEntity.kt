package com.lhstack.tools.db.entity

import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

@TableName("mcp_servers")
class McpServerEntity {
    @TableId(type = IdType.AUTO)
    var id: Long? = null

    @TableField("name")
    var name: String = ""

    @TableField("enabled")
    var enabled: Int = 1

    @TableField("transport")
    var transport: String = "stdio"

    @TableField("command")
    var command: String? = null

    @TableField("args")
    var args: String = "[]"

    @TableField("env")
    var env: String = "{}"

    @TableField("url")
    var url: String? = null

    @TableField("headers")
    var headers: String = "{}"
}
