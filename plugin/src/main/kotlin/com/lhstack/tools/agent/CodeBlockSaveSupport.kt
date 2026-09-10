package com.lhstack.tools.agent

/**
 * 代码块保存到本地时的文件名处理。Windows / macOS / Linux 共用一份规则：
 * 去掉路径分段，去掉各系统都不允许的字符，避免 Windows 预留设备名。
 */
object CodeBlockSaveSupport {
    private const val BACKSLASH = '\u005C'
    private val windowsReserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    fun suggestedFileName(raw: String?, fallback: String = "code.txt"): String {
        val leaf = (raw ?: "")
            .replace(BACKSLASH, '/')
            .substringAfterLast('/')
            .trim()
            .ifBlank { fallback }
        val sanitized = buildString(leaf.length) {
            leaf.forEach { ch -> append(if (isAllowedFileNameChar(ch)) ch else '_') }
        }.trim('.', ' ').ifBlank { fallback.substringBeforeLast('.', "code") }
        val extension = extensionOf(leaf) ?: extensionOf(fallback) ?: "txt"
        val stem = if (sanitized.contains('.')) sanitized.substringBeforeLast('.') else sanitized
        return "${sanitizeStem(stem)}.$extension"
    }

    fun extensionOf(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return ext.takeIf { it.isNotBlank() && it.length <= 16 && it.all(Char::isLetterOrDigit) }?.lowercase()
    }

    private fun sanitizeStem(stem: String): String {
        val trimmed = stem.trim('.', ' ').ifBlank { "code" }
        val upper = trimmed.uppercase()
        return if (upper in windowsReserved) "${trimmed}_file" else trimmed
    }

    private fun isAllowedFileNameChar(ch: Char): Boolean = when (ch) {
        '<', '>', ':', '"', '/', '|', '?', '*', BACKSLASH -> false
        else -> ch.code in 32..126 || ch.code > 127
    }
}
