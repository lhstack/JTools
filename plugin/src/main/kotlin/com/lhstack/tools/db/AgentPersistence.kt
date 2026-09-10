package com.lhstack.tools.db

import com.lhstack.tools.db.mapper.AgentMapper
import com.lhstack.tools.db.mapper.GlobalConfigMapper
import com.lhstack.tools.db.mapper.ModelMapper
import com.lhstack.tools.db.mapper.McpServerMapper
import com.lhstack.tools.db.mapper.ContextCompactionMapper
import com.lhstack.tools.db.mapper.CodingEnvironmentMapper
import com.lhstack.tools.db.mapper.MessageAppendItemMapper
import com.lhstack.tools.db.mapper.MessageAttachmentMapper
import com.lhstack.tools.db.mapper.MessageEventMapper
import com.lhstack.tools.db.mapper.MessageProcessingTaskMapper
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
                McpServerMapper::class.java,
                MessageEventMapper::class.java,
                MessageProcessingTaskMapper::class.java,
                MessageAppendItemMapper::class.java,
                MessageAttachmentMapper::class.java,
                ContextCompactionMapper::class.java,
                CodingEnvironmentMapper::class.java,
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
        SettingService.setSettingIfAbsent("model.retry_interval_ms", "2000")
        SettingService.setSettingIfAbsent("message.history_token_ratio", "2")
        SettingService.setSettingIfAbsent("model.dns_servers", "")
        SettingService.setSettingIfAbsent("model.http_request_timeout_secs", "0")
        SettingService.setSettingIfAbsent("model.http_pool_idle_timeout_secs", "120")
        SettingService.setSettingIfAbsent("model.http_pool_max_idle_per_host", "32")
        SettingService.setSettingIfAbsent("web_fetch.proxy_enabled", "false")
        SettingService.setSettingIfAbsent("web_fetch.proxy", "")
        SettingService.setSettingIfAbsent("web_fetch.timeout_secs", "30")
        SettingService.setSettingIfAbsent("web_fetch.max_response_bytes", "1000000")
        SettingService.setSettingIfAbsent("bash.max_output_chars", "8000")
        SettingService.setSettingIfAbsent("coding.bash.max_output_chars", "8000")
        SettingService.setSettingIfAbsent("coding.compaction_agent_id", "")
    }
}
