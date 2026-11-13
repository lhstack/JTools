package com.lhstack.tools.agent.resources

import java.io.File
import java.net.URI

/**
 * Shared local reference parser. Absolute paths are accepted; relative paths resolve against the workspace.
 */
object LocalResourceReferenceSupport {
    data class Location(val file: File, val line: Int? = null, val column: Int? = null)

    fun resolve(
        reference: String,
        workspace: String,
    ): Location {
        require(reference.isNotBlank()) { "资源引用不能为空" }
        var path = reference
        var line: Int? = null
        var column: Int? = null
        val suffix = Regex("(?:#L(\\d+)(?:C(\\d+))?|:(\\d+)(?::(\\d+))?)$").find(path)
        if (suffix != null) {
            line = (suffix.groupValues[1].ifEmpty { suffix.groupValues[3] }).toInt()
            column =
                (suffix.groupValues[2].ifEmpty { suffix.groupValues[4] })
                    .takeIf { it.isNotEmpty() }
                    ?.toInt()
            require(line > 0 && (column == null || column > 0)) { "行列必须为正整数" }
            path = path.substring(0, suffix.range.first)
        }
        if (path.startsWith("file:", true)) {
            val uri = URI(path.replace(" ", "%20"))
            require(uri.host.isNullOrEmpty() || uri.host == "localhost") { "不支持远程文件 URI" }
            path = uri.path.also { require(!it.isNullOrBlank()) { "file URI 必须包含绝对路径" } }
        } else {
            require(
                !Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(path) ||
                    Regex("^[a-zA-Z]:[\\\\/]").containsMatchIn(path)
            ) {
                "不支持的资源协议"
            }
        }
        if (!reference.startsWith("file:", true))
            path = java.net.URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8)
        require(!path.contains('\u0000')) { "路径不能包含 NUL" }
        val root = File(workspace).canonicalFile
        val input = File(path)
        val file = (if (input.isAbsolute) input else root.resolve(path)).canonicalFile
        require(file.exists()) { "资源不存在: $path" }
        return Location(file, line, column)
    }
}
