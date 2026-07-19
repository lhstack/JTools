package com.lhstack.tools.db.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.GlobalConfigEntity
import com.lhstack.tools.db.mapper.GlobalConfigMapper

/**
 * 全局配置读写服务。完全照抄 awake-claw repository/config.rs 的 setting /
 * positive_* / non_negative_* 读取语义。
 *
 * global_config 以 key/value 文本存储，这里提供类型化读取（含校验），
 * 供 runtime 读取 model.max_tool_call_rounds / model.max_retries 等。
 */
object SettingService {

    /** 照抄 setting：按 key 取原始字符串值，缺失返回 null。 */
    fun setting(key: String): String? = AgentDatabase.execute { session ->
        session.getMapper(GlobalConfigMapper::class.java)
            .selectOne(QueryWrapper<GlobalConfigEntity>().eq("key", key).last("limit 1"))
            ?.value
    }

    fun setSetting(key: String, value: String) = AgentDatabase.execute { session ->
        val mapper = session.getMapper(GlobalConfigMapper::class.java)
        val existing = mapper.selectOne(QueryWrapper<GlobalConfigEntity>().eq("key", key).last("limit 1"))
        if (existing == null) {
            mapper.insert(GlobalConfigEntity().apply {
                this.key = key
                this.value = value
            })
        } else {
            existing.value = value
            mapper.updateById(existing)
        }
        Unit
    }

    /** 照抄 seed_settings 的 set_setting_if_absent 语义：缺失才写入。 */
    fun setSettingIfAbsent(key: String, value: String) {
        if (setting(key) == null) {
            setSetting(key, value)
        }
    }

    /**
     * 照抄 global_usize_setting（model_runtime.rs）：
     * 缺失返回 null，存在则按非负整数解析，非法报错。
     */
    fun optionalUsizeSetting(key: String): Int? {
        val value = setting(key) ?: return null
        return value.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("配置 `$key` 必须是非负整数")
    }
}
