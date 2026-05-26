package com.lhstack.tools.agent

import java.io.File

enum class AgentToolPermissionScope(val id: String, val displayName: String, val tooltip: String) {
    READ_ONLY("read_only", "只读", "只读：允许读取项目内文件；项目外完整路径需要完全访问。"),
    WORKSPACE_WRITE("workspace_write", "项目", "项目：允许读取和写入当前项目目录。"),
    DANGER_FULL_ACCESS("danger_full_access", "完全", "完全：允许访问项目外完整路径和执行系统级操作。");

    companion object {
        fun fromId(id: String?): AgentToolPermissionScope {
            return entries.firstOrNull { it.id == id } ?: WORKSPACE_WRITE
        }
    }
}

enum class AgentToolApprovalPolicy(val id: String, val displayName: String, val tooltip: String) {
    AUTO_APPROVE("auto_approve", "自动", "自动：权限范围允许的工具直接执行。"),
    CONFIRM_DANGEROUS("confirm_dangerous", "确认", "确认：危险操作执行前弹窗确认。"),
    DENY_DANGEROUS("deny_dangerous", "拒绝", "拒绝：危险操作直接拦截。");

    companion object {
        fun fromId(id: String?): AgentToolApprovalPolicy {
            return entries.firstOrNull { it.id == id } ?: CONFIRM_DANGEROUS
        }
    }
}

data class AgentToolPermissionOutcome(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
)

object AgentToolPermissionHelp {
    fun fullText(): String {
        return """
            访问权限

            只读
            - 允许读取当前项目内文件和搜索当前项目内内容。
            - 项目外完整路径需要完全访问。
            - 适合只查看代码、搜索文件、读取网页信息的场景。

            项目
            - 允许读取和写入当前项目目录。
            - 相对路径会基于当前项目目录解析。
            - 项目外完整路径仍需要完全访问。

            完全
            - 允许访问项目外完整路径。
            - 允许以项目外目录作为搜索根目录或命令工作目录。
            - 允许执行系统级命令。是否直接执行还会受危险操作策略控制。

            危险操作策略

            自动
            - 当前访问权限允许的工具调用直接执行。
            - 项目写入、编辑、创建目录、危险命令、项目外完整路径不会再单独确认。

            确认
            - 需要项目或完全访问的工具调用会被视为危险操作。
            - 执行危险操作前会弹窗确认。
            - 项目内写入、编辑、创建目录也会受危险操作策略控制。

            拒绝
            - 需要项目或完全访问的工具调用会直接拦截。
            - 适合不希望模型修改文件、创建目录、触碰项目外文件或执行危险命令的场景。

            工具权限

            read_file
            - 项目内相对路径或项目内完整路径：只读。
            - 项目外完整路径需要完全访问。
            - 只传 path 时默认读取前 200 行，最多一次读取 1000 行。

            glob_search / grep_search
            - 项目内搜索：只读。
            - path 指向项目外完整路径时需要完全访问。
            - glob_search 直接扫描本地文件系统，不依赖 IDE 索引缓存。

            write_file / edit_file
            - 项目内路径：项目。
            - 项目外完整路径需要完全访问。
            - 覆盖或编辑文件属于危险操作，项目内外都会受危险操作策略控制。

            jtools_create_directory
            - 项目内路径：项目。
            - 项目外完整路径需要完全访问。
            - 创建目录属于危险操作，项目内外都会受危险操作策略控制。

            bash
            - 已识别的项目内只读命令：只读，例如 ls、find、rg、grep、cat、head、tail、wc。
            - 项目内写入或重定向：项目。
            - 未识别命令、系统级命令、项目外完整路径、项目外工作目录、明显危险路径：完全。
            - 项目内写入、重定向、未识别命令、系统级命令和项目外操作都属于危险操作。
            - 当危险操作策略为“确认”时，会弹窗确认。

            WebFetch / WebSearch
            - 只读。
            - 用于读取网页或搜索网页信息，不写入本地文件。

            AI 浏览器工具
            - browser_open / browser_read / browser_click / browser_type / browser_scroll / browser_show / browser_hide / browser_close：只读。
            - 用于临时打开网页、读取页面内容和执行受控浏览器动作，不写入本地文件。
            - browser_show 显示浏览器组件前会单独询问用户；任务完成后应调用 browser_close 销毁浏览器。

            Skills 工具
            - jtools_skill_list：只读。
            - SkillBox 内置 load_skill_through_path：用于读取已注册 skill 的 SKILL.md 与资源内容。
            - jtools_skill_delete：项目，删除 skill 配置，受危险操作策略控制。
            - jtools_skill_import_from_path：项目，从本地路径导入并写入 skill 配置，受危险操作策略控制。

            MCP 工具
            - jtools_mcp_list_servers、jtools_mcp_query、jtools_mcp_test_server：只读。
            - jtools_mcp_update_server / jtools_mcp_delete_server：项目，修改或删除 MCP 配置，受危险操作策略控制。
            - 动态 MCP 工具：项目。MCP 工具能力来自外部服务，默认受危险操作策略控制。

            插件与系统工具
            - jtools_list_plugins、jtools_get_plugin_detail、jtools_get_system_info、jtools_get_current_project：只读。
            - jtools_install_plugin_from_file / jtools_uninstall_plugin：项目，安装或卸载插件，受危险操作策略控制。
            - 外部插件工具：项目。插件工具没有权限元数据时默认受危险操作策略控制。
        """.trimIndent()
    }
}

object AgentToolPermissionSupport {
    private val windowsDrivePath = Regex("^[A-Za-z]:[\\\\/].+")
    private val uncPath = Regex("^\\\\\\\\[^\\\\/]+[\\\\/][^\\\\/]+.*")
    private val readOnlyShellCommands = setOf(
        "cat", "head", "tail", "less", "more", "ls", "ll", "dir", "find", "test", "[", "[[",
        "grep", "rg", "awk", "sed", "file", "stat", "readlink", "wc", "sort", "uniq", "cut", "tr",
        "pwd", "echo", "printf", "date", "time",
        "get-date", "get-computerinfo", "get-timezone", "get-location", "get-childitem", "get-content",
        "where-object", "select-object", "format-list", "format-table",
        "ver", "whoami", "hostname"
    )

    fun classifyBashPermission(command: String): AgentToolPermissionScope {
        return classifyBashPermission(command, File(System.getProperty("user.dir")))
    }

    fun classifyBashPermission(command: String, projectRoot: File): AgentToolPermissionScope {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return AgentToolPermissionScope.DANGER_FULL_ACCESS
        }
        val base = trimmed
            .substringBefore('|')
            .substringBefore(';')
            .substringBefore('>')
            .substringBefore('<')
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()
        val commandName = base.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (commandName !in readOnlyShellCommands) {
            return AgentToolPermissionScope.DANGER_FULL_ACCESS
        }
        if (hasProjectExternalPaths(trimmed, projectRoot)) {
            return AgentToolPermissionScope.DANGER_FULL_ACCESS
        }
        return if (hasShellWriteOperator(trimmed)) {
            AgentToolPermissionScope.WORKSPACE_WRITE
        } else {
            AgentToolPermissionScope.READ_ONLY
        }
    }

    fun isExplicitAbsolutePath(path: String): Boolean {
        val normalized = cleanPathToken(path)
        return normalized.startsWith("/") ||
            normalized == "~" ||
            normalized.startsWith("~/") ||
            windowsDrivePath.matches(normalized) ||
            uncPath.matches(normalized)
    }

    fun resolveExplicitFile(path: String): File? {
        val normalized = cleanPathToken(path)
        if (!isExplicitAbsolutePath(normalized)) {
            return null
        }
        return when {
            normalized == "~" -> File(System.getProperty("user.home"))
            normalized.startsWith("~/") -> File(System.getProperty("user.home"), normalized.removePrefix("~/"))
            else -> File(normalized)
        }
    }

    fun requiredPermissionForPath(
        projectRoot: File,
        path: String?,
        baseRequired: AgentToolPermissionScope,
    ): AgentToolPermissionScope {
        val normalized = path?.trim().orEmpty()
        if (normalized.isBlank() || !isExplicitAbsolutePath(normalized)) {
            return baseRequired
        }
        if (isProjectExternalExplicitPath(projectRoot, normalized)) {
            return AgentToolPermissionScope.DANGER_FULL_ACCESS
        }
        return baseRequired
    }

    fun maxScope(
        first: AgentToolPermissionScope,
        second: AgentToolPermissionScope,
    ): AgentToolPermissionScope {
        return if (first.ordinal >= second.ordinal) first else second
    }

    fun evaluate(
        selectedScope: AgentToolPermissionScope,
        approvalPolicy: AgentToolApprovalPolicy,
        requiredScope: AgentToolPermissionScope,
    ): AgentToolPermissionOutcome {
        if (selectedScope.ordinal < requiredScope.ordinal) {
            return AgentToolPermissionOutcome(allowed = false, requiresConfirmation = false)
        }
        return when (approvalPolicy) {
            AgentToolApprovalPolicy.AUTO_APPROVE -> AgentToolPermissionOutcome(true, false)
            AgentToolApprovalPolicy.CONFIRM_DANGEROUS -> AgentToolPermissionOutcome(
                allowed = true,
                requiresConfirmation = requiredScope.ordinal >= AgentToolPermissionScope.WORKSPACE_WRITE.ordinal
            )
            AgentToolApprovalPolicy.DENY_DANGEROUS -> AgentToolPermissionOutcome(
                allowed = requiredScope == AgentToolPermissionScope.READ_ONLY,
                requiresConfirmation = false
            )
        }
    }

    private fun hasProjectExternalPaths(command: String, projectRoot: File): Boolean {
        val tokens = command.split(Regex("\\s+"))
        for (token in tokens) {
            val cleaned = cleanPathToken(token)
            if (cleaned.isBlank() || cleaned.startsWith('-')) {
                continue
            }
            if (requiredPermissionForPath(projectRoot, cleaned, AgentToolPermissionScope.READ_ONLY) ==
                AgentToolPermissionScope.DANGER_FULL_ACCESS
            ) {
                return true
            }
            if (isRelativeTraversalOutsideProject(projectRoot, cleaned)) {
                return true
            }
        }
        return false
    }

    private fun hasShellWriteOperator(command: String): Boolean {
        return Regex("""(^|\s)(>|>>|\d>|&>)""").containsMatchIn(command) ||
            Regex("""(^|\s)-i($|\s)""").containsMatchIn(command)
    }

    private fun isProjectExternalExplicitPath(projectRoot: File, path: String): Boolean {
        val normalized = cleanPathToken(path)
        if ((windowsDrivePath.matches(normalized) || uncPath.matches(normalized)) && File.separatorChar != '\\') {
            return true
        }
        val target = resolveExplicitFile(normalized) ?: return false
        return !normalizedPath(target).startsWith(normalizedPath(projectRoot))
    }

    private fun isRelativeTraversalOutsideProject(projectRoot: File, path: String): Boolean {
        if (!path.startsWith("..") && !path.contains("/../") && !path.contains("\\..\\")) {
            return false
        }
        val target = File(projectRoot, path)
        return !normalizedPath(target).startsWith(normalizedPath(projectRoot))
    }

    private fun normalizedPath(file: File): java.nio.file.Path {
        return file.toPath().toAbsolutePath().normalize()
    }

    private fun cleanPathToken(path: String): String {
        return path.trim().trim('"', '\'', '`', ',', ';')
    }
}
