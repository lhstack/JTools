package com.lhstack.tools.agent.model.tools

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/** 多模态资源使用的协议路径解析；普通项目工具仍由 WorkspaceTools 负责边界校验。 */
internal object ResourcePathSupport {
    private val PROTOCOL_PATH_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    fun isProtocolPath(path: String): Boolean = PROTOCOL_PATH_PATTERN.containsMatchIn(path)

    fun findFile(path: String): VirtualFile? {
        if (!isProtocolPath(path)) return null
        return runCatching { VirtualFileManager.getInstance().findFileByUrl(path) }.getOrNull()
    }

    fun requireFile(path: String): VirtualFile {
        val file = findFile(path) ?: throw ToolException("resource VFS url `$path` was not found")
        require(!file.isDirectory) { "resource path `$path` is not a file" }
        return file
    }

    fun readBytes(path: String): ByteArray? {
        val file = findFile(path) ?: return null
        if (file.isDirectory) return null
        return runCatching { file.contentsToByteArray() }.getOrNull()
    }

    fun fileName(path: String): String? = findFile(path)?.name

    fun size(path: String): Long? = findFile(path)?.takeIf { !it.isDirectory }?.length

    fun mimeType(path: String): String? = findFile(path)?.let { file ->
        runCatching { java.net.URLConnection.guessContentTypeFromName(file.name) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
