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
        BuiltinToolInfo("read_file", "ide", "Read a known project file or explicitly selected dependency/JAR/JRT path; use after find_files/search_text and request focused line ranges for large files."),
        BuiltinToolInfo("write_file", "ide", "Create or intentionally replace a whole project text file through JetBrains VFS/Document; prefer replace_text_in_file for local edits."),
        BuiltinToolInfo("replace_text_in_file", "ide", "Apply a focused, unique edit to an existing project file through the current IDE Document; report ambiguity instead of guessing."),
        BuiltinToolInfo("find_files", "ide", "Find files by name in the project; enable library scope only when dependency/JAR/JRT inspection is explicitly needed."),
        BuiltinToolInfo("search_text", "ide", "Search literal text or regex in project source/tests/docs/config; library/JAR scope is opt-in and not a bulk binary-class search."),
        BuiltinToolInfo("format_file", "ide", "Format a modified project source file with the configured JetBrains language and Code Style."),
        BuiltinToolInfo("compile_project", "ide", "Verify the IDE project with ProjectTaskManager; use Bash for Gradle/Maven/npm/custom builds and tests."),
        BuiltinToolInfo("get_file_problems", "ide", "Inspect one modified project source file for IDE errors/inspections; use Bash for project-wide compilation and tests."),
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
