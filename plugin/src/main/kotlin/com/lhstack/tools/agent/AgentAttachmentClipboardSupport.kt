package com.lhstack.tools.agent

import com.intellij.ide.dnd.FileCopyPasteUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI

object AgentAttachmentClipboardSupport {

    fun hasFileLikeContent(transferable: Transferable): Boolean {
        if (!FileCopyPasteUtil.getFileList(transferable).isNullOrEmpty()) {
            return true
        }
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return true
        }
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val text = runCatching { transferable.getTransferData(DataFlavor.stringFlavor)?.toString().orEmpty() }.getOrNull().orEmpty()
            if (parseFiles(text).isNotEmpty()) {
                return true
            }
        }
        return transferable.transferDataFlavors.any { flavor ->
            (flavor.representationClass == String::class.java || flavor.mimeType.contains("uri-list", ignoreCase = true)) &&
                runCatching { transferable.getTransferData(flavor)?.toString().orEmpty() }
                    .getOrNull()
                    ?.let(::parseFiles)
                    .orEmpty()
                    .isNotEmpty()
        }
    }

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
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val text = transferable.getTransferData(DataFlavor.stringFlavor)?.toString().orEmpty()
            return parseFiles(text)
        }
        transferable.transferDataFlavors.forEach { flavor ->
            if (flavor.representationClass == String::class.java || flavor.mimeType.contains("uri-list", ignoreCase = true)) {
                val text = runCatching { transferable.getTransferData(flavor)?.toString().orEmpty() }.getOrNull().orEmpty()
                val files = parseFiles(text)
                if (files.isNotEmpty()) {
                    return files
                }
            }
        }
        return emptyList()
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
