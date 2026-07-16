package com.lhstack.tools.agent.model.tools

/**
 * 内置工具元数据。完全照抄 awake-claw tools.rs 的 BuiltinToolInfo / BUILTIN_TOOLS / builtin_tools。
 *
 * 提供工具清单（名称 / 分类 / 描述），供 Agent 能力配置页展示可选工具。
 * view_* 多模态工具按配置的资源类型动态追加，语义照抄 builtin_tools(view_resource_kinds)。
 */
data class BuiltinToolInfo(
    val name: String,
    val category: String,
    val description: String,
)

/** 资源类型。照抄 awake 的 ResourceKind。 */
enum class ResourceKind(val asStr: String, val toolName: String) {
    IMAGE("image", "view_image"),
    AUDIO("audio", "view_audio"),
    VIDEO("video", "view_video"),
    FILE("file", "view_files");

    companion object {
        /** 照抄 from_content_type。 */
        fun fromContentType(contentType: String): ResourceKind {
            val ct = contentType.substringBefore(';').trim().lowercase()
            return when {
                ct.startsWith("image/") -> IMAGE
                ct.startsWith("audio/") -> AUDIO
                ct.startsWith("video/") -> VIDEO
                else -> FILE
            }
        }
    }
}

object BuiltinTools {

    /** 照抄 BUILTIN_TOOLS 常量。 */
    val BUILTIN_TOOLS: List<BuiltinToolInfo> = listOf(
        BuiltinToolInfo("get_time", "time", "Get the current time for an optional IANA timezone."),
        BuiltinToolInfo("skills_list", "skills", "List available skills from the configured skills directory."),
        BuiltinToolInfo("skills_view", "skills", "Load one skill resource from the configured skills directory."),
        BuiltinToolInfo("bash", "shell", "Run a shell command and return stdout, stderr, and exit code."),
        BuiltinToolInfo("web_fetch", "http", "Send an HTTP request with optional proxy, headers, and request body."),
        BuiltinToolInfo("cli", "cli", "Execute structured JTools CLI commands such as mcp.list, mcp.create, mcp.test, and mcp.call."),
        BuiltinToolInfo("read_project_files", "ide", "Read one or more current-project text files through the IDE, including unsaved editor content."),
        BuiltinToolInfo("write_project_files", "ide", "Create or completely replace one or more current-project text files through the IDE."),
        BuiltinToolInfo("replace_project_text", "ide", "Apply exact, prevalidated text replacements to existing current-project files."),
        BuiltinToolInfo("find_project_files", "ide", "Find current-project files by exact, contains, or glob file-name queries."),
        BuiltinToolInfo("search_project_text", "ide", "Search text file contents in the current project with optional regex, file-name glob, and line context."),
        BuiltinToolInfo("format_project_files", "ide", "Format explicit line ranges with the formatter supplied by the active IDE and installed file-type support."),
        BuiltinToolInfo("build_project", "ide", "Build the current project with JetBrains ProjectTaskManager and the task runner supplied by the active IDE."),
        BuiltinToolInfo("inspect_project_files", "ide", "Run enabled local inspections for current-project files using the active IDE inspection profile."),
    )

    /** 照抄 builtin_tools：按配置的多模态资源类型追加 view_* 工具。 */
    fun builtinTools(viewResourceKinds: List<ResourceKind>): List<BuiltinToolInfo> {
        val tools = BUILTIN_TOOLS.toMutableList()
        for (kind in viewResourceKinds) {
            val description = when (kind) {
                ResourceKind.IMAGE -> "Analyze one or more workspace images using the configured image resource agent."
                ResourceKind.AUDIO -> "Analyze one or more workspace audio files using the configured audio resource agent."
                ResourceKind.VIDEO -> "Analyze one or more workspace videos using the configured video resource agent."
                ResourceKind.FILE -> "Analyze one or more workspace files using the configured file resource agent."
            }
            tools.add(BuiltinToolInfo(kind.toolName, "multimodal", description))
        }
        return tools
    }
}
