package com.lhstack.tools.plugins

/**
 * 插件信息
 */
class PluginInfo(
    //插件id
    val id: String,
    //插件安装路径
    var path: String,
    //插件名称
    val name: String,
    //插件版本
    val version: String,
    //创建时间
    val created: Long
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PluginInfo

        if (id != other.id) return false
        if (path != other.path) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + path.hashCode()
        return result
    }
}