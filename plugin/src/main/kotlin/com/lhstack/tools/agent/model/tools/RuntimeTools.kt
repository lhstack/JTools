package com.lhstack.tools.agent.model.tools

import com.intellij.openapi.project.Project
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.db.service.SettingService
import com.lhstack.tools.llm.ToolDyn
import java.io.File

/**
 * 运行时工具注册入口。照抄 awake-claw tool_runtime.rs 的 runtime_tools。
 *
 * enabledTools 是 Agent 能力策略的唯一输入；未启用的工具不会进入工具定义。
 * send_files、view_* 需要当前插件尚未提供的 runtime 上下文，因此由调用方通过
 * extraTools 显式注入，不在这里伪造注册。
 */
object RuntimeTools {

    val REGISTERED_BUILTIN_TOOL_NAMES: Set<String> = setOf(
        GetTimeTool.NAME,
        BashTool.NAME,
        WebFetchTool.NAME,
        SkillsListTool.NAME,
        SkillsViewTool.NAME,
        CliTool.NAME,
        ReadProjectFilesTool.NAME,
        WriteProjectFilesTool.NAME,
        ReplaceProjectTextTool.NAME,
        FindProjectFilesTool.NAME,
        FindProjectClassesTool.NAME,
        SearchProjectTextTool.NAME,
        FormatProjectFilesTool.NAME,
        BuildProjectTool.NAME,
        InspectProjectFilesTool.NAME,
    )

    fun create(
        workspace: String,
        enabledTools: Set<String>,
        enabledSkills: Set<String>? = null,
        skillsRootDir: File? = null,
        toolEnvVars: Map<String, String> = emptyMap(),
        cancel: ModelCancel? = null,
        project: Project? = null,
        codingSession: Boolean = false,
    ): List<ToolDyn> {
        val workspaceTools = WorkspaceTools(workspace)
        val tools = mutableListOf<ToolDyn>()
        fun enabled(name: String): Boolean = enabledTools.contains(name)

        if (enabled(GetTimeTool.NAME)) tools.add(GetTimeTool())
        if (enabled(BashTool.NAME)) {
            tools.add(BashTool(workspaceTools, toolEnvVars, cancel, maxOutputChars = bashMaxOutputChars(codingSession)))
        }
        if (enabled(WebFetchTool.NAME)) {
            val fetch = webFetchDefaults()
            tools.add(
                WebFetchTool(
                    workspace = workspaceTools,
                    defaultProxy = fetch.proxy,
                    defaultTimeoutSecs = fetch.timeoutSecs,
                    defaultMaxResponseBytes = fetch.maxResponseBytes,
                    cancel = cancel,
                )
            )
        }
        if (enabled(SkillsListTool.NAME)) {
            tools.add(SkillsListTool(workspaceTools, skillsRootDir, enabledSkills))
        }
        if (enabled(SkillsViewTool.NAME)) {
            tools.add(SkillsViewTool(workspaceTools, skillsRootDir, enabledSkills))
        }
        if (enabled(CliTool.NAME)) tools.add(CliTool(cancel))
        if (project != null) {
            val ide = IdeProjectSupport(workspaceTools, project)
            if (enabled(ReadProjectFilesTool.NAME)) tools.add(ReadProjectFilesTool(ide))
            if (enabled(WriteProjectFilesTool.NAME)) tools.add(WriteProjectFilesTool(ide))
            if (enabled(ReplaceProjectTextTool.NAME)) tools.add(ReplaceProjectTextTool(ide))
            if (enabled(FindProjectFilesTool.NAME)) tools.add(FindProjectFilesTool(ide))
            if (enabled(FindProjectClassesTool.NAME)) tools.add(FindProjectClassesTool(ide))
            if (enabled(SearchProjectTextTool.NAME)) tools.add(SearchProjectTextTool(ide))
            if (enabled(FormatProjectFilesTool.NAME)) tools.add(FormatProjectFilesTool(ide))
            if (enabled(BuildProjectTool.NAME)) tools.add(BuildProjectTool(ide, cancel))
            if (enabled(InspectProjectFilesTool.NAME)) tools.add(InspectProjectFilesTool(ide))
        }
        return tools
    }

    private data class WebFetchDefaults(val proxy: String?, val timeoutSecs: Long, val maxResponseBytes: Long)

    private fun webFetchDefaults(): WebFetchDefaults {
        val enabled = SettingService.setting("web_fetch.proxy_enabled")?.trim()?.equals("true", ignoreCase = true) == true
        val proxy = SettingService.setting("web_fetch.proxy")?.trim()?.takeIf { enabled && it.isNotEmpty() }
        return WebFetchDefaults(
            proxy = proxy,
            timeoutSecs = positiveLong("web_fetch.timeout_secs", 30),
            maxResponseBytes = positiveLong("web_fetch.max_response_bytes", 1_000_000),
        )
    }

    private fun bashMaxOutputChars(codingSession: Boolean): Int {
        val key = if (codingSession) "coding.bash.max_output_chars" else "bash.max_output_chars"
        return positiveLong(key, 8000).toInt()
    }

    private fun positiveLong(key: String, default: Long): Long {
        val raw = SettingService.setting(key)?.trim().orEmpty()
        if (raw.isEmpty()) return default
        return raw.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("配置 `$key` 必须是正整数")
    }
}
