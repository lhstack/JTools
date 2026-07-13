package com.lhstack.tools.agent.model.tools

/**
 * 工具执行异常。对齐 awake-claw tools.rs 里各工具的 thiserror 错误枚举。
 *
 * awake 每个工具有独立错误枚举（FileToolError / BashToolError / SkillsToolError / …），
 * 语义都是「携带一条给模型看的错误文案」。Kotlin 侧统一用一个异常类型 + 工厂方法承接，
 * message 文案照抄 awake 的 #[error(...)]，保证模型看到的错误信息一致。
 */
class ToolException(message: String) : RuntimeException(message) {

    companion object {
        // -------- File tool errors --------
        fun invalidPath(path: String) = ToolException("invalid path `$path`")

        fun outsideWorkspace(path: String, workspace: String) =
            ToolException("path `$path` escapes the current workspace `$workspace`")

        fun notFile(path: String) = ToolException("path `$path` is not a file")

        // -------- Get time --------
        fun invalidTimezone(tz: String) = ToolException(
            "invalid timezone `$tz`, please use an IANA timezone like Asia/Shanghai or UTC"
        )

        // -------- Bash --------
        fun emptyCommand() = ToolException("command must not be empty")

        fun invalidCwd(cwd: String) = ToolException("working directory `$cwd` is invalid")

        // -------- Skills --------
        fun skillsNotConfigured() = ToolException("skills root_dir is not configured")

        fun invalidSkillName(name: String) = ToolException("invalid skill name `$name`")

        fun invalidResourcePath(path: String) = ToolException("invalid skill resource path `$path`")

        fun rootNotDirectory(path: String) = ToolException("skills root `$path` is not a directory")

        fun skillNotFound(name: String) = ToolException("skill `$name` was not found")

        fun skillDisabled(name: String) = ToolException("skill `$name` is disabled in the current environment")

        fun skillUnavailable(name: String, reason: String) = ToolException("skill `$name` is unavailable: $reason")

        fun resourceNotFound(path: String) = ToolException("skill resource `$path` was not found")

        fun resourceNotFile(path: String) = ToolException("skill resource `$path` is not a file")

        // -------- Web fetch --------
        fun invalidMethod(method: String) = ToolException("invalid HTTP method `$method`")

        fun invalidBodyType(bodyType: String) = ToolException("invalid body_type `$bodyType`")

        fun missingBody(bodyType: String) = ToolException("body_type `$bodyType` requires corresponding body fields")

        fun invalidJsonBody(detail: String) = ToolException("invalid json_body: $detail")

        fun cancelled() = ToolException("用户手动取消")
    }
}
