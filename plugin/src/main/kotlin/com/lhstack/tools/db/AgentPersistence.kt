package com.lhstack.tools.db

import com.lhstack.tools.db.mapper.AgentMapper
import com.lhstack.tools.db.mapper.GlobalConfigMapper
import com.lhstack.tools.db.mapper.ModelMapper
import com.lhstack.tools.db.mapper.ChatSessionMapper
import com.lhstack.tools.db.mapper.ModelRequestLogMapper
import com.lhstack.tools.db.mapper.PromptTemplateMapper
import com.lhstack.tools.db.mapper.ProviderMapper
import com.lhstack.tools.db.service.SettingService

/**
 * 持久化引导入口。负责登记各模块 Mapper、初始化数据库并注入默认设置。
 * 由插件生命周期在应用启动时调用一次。后续新增模块只需在此登记对应 Mapper。
 */
object AgentPersistence {

    @Volatile
    private var bootstrapped = false

    fun bootstrap() {
        if (bootstrapped) {
            return
        }
        synchronized(this) {
            if (bootstrapped) {
                return
            }
            AgentDatabase.registerMapper(
                ProviderMapper::class.java,
                ModelMapper::class.java,
                PromptTemplateMapper::class.java,
                AgentMapper::class.java,
                ModelRequestLogMapper::class.java,
                ChatSessionMapper::class.java,
                GlobalConfigMapper::class.java,
            )
            AgentDatabase.init()
            seedSettings()
            bootstrapped = true
        }
    }

    /** 照抄 awake-claw seed_settings 里模型运行相关的默认设置（缺失才写入）。 */
    private fun seedSettings() {
        SettingService.setSettingIfAbsent("model.max_tool_call_rounds", "30")
        SettingService.setSettingIfAbsent("model.max_retries", "0")
        SettingService.setSettingIfAbsent("model.dns_servers", "")
    }
}
