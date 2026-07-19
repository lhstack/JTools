package com.lhstack.tools.agent

import com.intellij.ide.dnd.FileCopyPasteUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI

object AgentAttachmentClipboardSupport {

    fun extractFiles(transferable: Transferable): List<File> {
        FileCopyPasteUtil.getFileList(transferable)?.let { files ->
            if (files.isNotEmpty()) {
                return files
            }
        }
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            return files.orEmpty().filterIsInstance<File>()
        }
        transferable.transferDataFlavors.forEach { flavor ->
            if (flavor.mimeType.contains("uri-list", ignoreCase = true) || flavor.isFlavorTextType) {
                val text = transferableText(transferable, flavor)
                val files = parseFiles(text)
                if (files.isNotEmpty()) {
                    return files
                }
            }
        }
        return emptyList()
    }

    private fun transferableText(transferable: Transferable, flavor: DataFlavor): String {
        val data = runCatching { transferable.getTransferData(flavor) }.getOrNull() ?: return ""
        return when (data) {
            is java.io.Reader -> data.readText()
            is java.io.InputStream -> data.bufferedReader().readText()
            else -> data.toString()
        }
    }

    fun parseFiles(raw: String): List<File> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                runCatching { URI.create(line) }
                    .getOrNull()
                    ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
                    ?.let { return@mapNotNull File(it) }
                if (WINDOWS_PATH.matches(line) || UNIX_PATH.matches(line)) {
                    return@mapNotNull File(line)
                }
                null
            }
            .distinctBy { it.path }
            .toList()
    }

    private val WINDOWS_PATH = Regex("^[a-zA-Z]:\\\\.*")
    private val UNIX_PATH = Regex("^/.*")
}
