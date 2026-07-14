package com.lhstack.tools.agent.model.tools

import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.llm.ToolDyn
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
    )

    fun create(
        workspace: String,
        enabledTools: Set<String>,
        enabledSkills: Set<String>? = null,
        skillsRootDir: File? = null,
        toolEnvVars: Map<String, String> = emptyMap(),
        cancel: ModelCancel? = null,
    ): List<ToolDyn> {
        val workspaceTools = WorkspaceTools(workspace)
        val tools = mutableListOf<ToolDyn>()
        fun enabled(name: String): Boolean = enabledTools.contains(name)

        if (enabled(GetTimeTool.NAME)) tools.add(GetTimeTool())
        if (enabled(BashTool.NAME)) tools.add(BashTool(workspaceTools, toolEnvVars, cancel))
        if (enabled(WebFetchTool.NAME)) {
            tools.add(
                WebFetchTool(
                    workspace = workspaceTools,
                    defaultProxy = null,
                    defaultTimeoutSecs = 30,
                    defaultMaxResponseBytes = 1_000_000,
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
        return tools
    }
}
