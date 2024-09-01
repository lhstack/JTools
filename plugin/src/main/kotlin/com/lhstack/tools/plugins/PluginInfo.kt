package com.lhstack.tools.plugins

/**
 * 插件信息
 */
class PluginInfo(
    //插件id
    var id: String,
    //插件安装路径
    var path: String,
    //插件名称
    var name: String,
    //插件版本
    var version: String,
    //创建时间
    var created: Long
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