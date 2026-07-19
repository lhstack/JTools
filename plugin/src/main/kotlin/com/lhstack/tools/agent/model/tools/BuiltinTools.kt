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
        BuiltinToolInfo("get_time", "time", "获取当前时间。"),
        BuiltinToolInfo("skills_list", "skills", "列出可用技能。"),
        BuiltinToolInfo("skills_view", "skills", "读取技能文件。"),
        BuiltinToolInfo("bash", "shell", "执行 Shell 命令。"),
        BuiltinToolInfo("web_fetch", "http", "发送 HTTP 请求。"),
        BuiltinToolInfo("cli", "cli", "执行 JTools CLI 结构化命令。"),
        BuiltinToolInfo("read_project_files", "ide", "读取项目文本文件。"),
        BuiltinToolInfo("write_project_files", "ide", "创建或覆盖项目文本文件。"),
        BuiltinToolInfo("replace_project_text", "ide", "精确替换项目文件文本。"),
        BuiltinToolInfo("find_project_files", "ide", "按名称或路径查找项目文件。"),
        BuiltinToolInfo("find_project_classes", "ide", "按名称查找类或类型。"),
        BuiltinToolInfo("search_project_text", "ide", "搜索项目文件内容。"),
        BuiltinToolInfo("format_project_files", "ide", "格式化项目文件指定行。"),
        BuiltinToolInfo("build_project", "ide", "使用 IDE 构建项目。"),
        BuiltinToolInfo("inspect_project_files", "ide", "使用 IDE 检查项目文件。"),
    )

    /** 照抄 builtin_tools：按配置的多模态资源类型追加 view_* 工具。 */
    fun builtinTools(viewResourceKinds: List<ResourceKind>): List<BuiltinToolInfo> {
        val tools = BUILTIN_TOOLS.toMutableList()
        for (kind in viewResourceKinds) {
            val description = when (kind) {
                ResourceKind.IMAGE -> "使用资源 Agent 分析图片。"
                ResourceKind.AUDIO -> "使用资源 Agent 分析音频。"
                ResourceKind.VIDEO -> "使用资源 Agent 分析视频。"
                ResourceKind.FILE -> "使用资源 Agent 分析文件。"
            }
            tools.add(BuiltinToolInfo(kind.toolName, "multimodal", description))
        }
        return tools
    }
}
