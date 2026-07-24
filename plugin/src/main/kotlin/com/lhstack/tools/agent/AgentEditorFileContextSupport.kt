package com.lhstack.tools.agent

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.lhstack.tools.agent.model.tools.WorkspaceTools
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.SettingService
import java.io.File

/** File context: multi-selection aggregate chip for old/new JCEF. */
object AgentEditorFileContextSupport {

    private const val SETTING_KEY_PREFIX = "agent.chat.file_context_enabled:"
    const val DEFAULT_BUTTON_LABEL = "文件上下文"
    private const val CHIP_NAME_MAX = 14

    data class Snapshot(
        val path: String,
        val startOffset: Int? = null,
        val endOffset: Int? = null,
        val startLine: Int? = null,
        val endLine: Int? = null,
    ) {
        val hasSelection: Boolean
            get() = startOffset != null && endOffset != null && startOffset != endOffset

        val fileName: String
            get() = path.substringAfterLast('/').ifBlank { path }
    }

    fun isEnabled(projectPath: String): Boolean =
        SettingService.setting(settingKey(projectPath)) == "true"

    fun setEnabled(projectPath: String, enabled: Boolean) {
        SettingService.setSetting(settingKey(projectPath), if (enabled) "true" else "false")
    }

    fun settingKey(projectPath: String): String =
        SETTING_KEY_PREFIX + ChatSessionService.normalizeProjectPath(projectPath)

    fun collect(project: Project): Snapshot? = collectAll(project).firstOrNull()

    fun collectAll(project: Project): List<Snapshot> = ReadAction.compute<List<Snapshot>, RuntimeException> {
        collectAllInReadAction(project)
    }

    private fun collectAllInReadAction(project: Project): List<Snapshot> {
        val manager = FileEditorManager.getInstance(project)
        val selectedSnapshots = linkedMapOf<String, Snapshot>()
        for (virtualFile in manager.openFiles) {
            val path = displayPath(project, virtualFile)
            if (path.isBlank()) continue
            for (fileEditor in manager.getEditors(virtualFile)) {
                val editor = (fileEditor as? TextEditor)?.editor ?: continue
                val document = editor.document
                for ((start, end) in selectionRanges(editor)) {
                    if (start == end) continue
                    val key = "$path:$start:$end"
                    selectedSnapshots[key] = Snapshot(
                        path = path,
                        startOffset = start,
                        endOffset = end,
                        startLine = document.getLineNumber(start) + 1,
                        endLine = document.getLineNumber((end - 1).coerceAtLeast(start)) + 1,
                    )
                }
            }
        }
        if (selectedSnapshots.isNotEmpty()) return selectedSnapshots.values.toList()
        return listOfNotNull(collectCurrentOpenFile(project, manager))
    }

    private fun selectionRanges(editor: Editor): List<Pair<Int, Int>> {
        val ranges = linkedMapOf<String, Pair<Int, Int>>()
        for (caret in editor.caretModel.allCarets) {
            if (!caret.hasSelection()) continue
            val start = caret.selectionStart
            val end = caret.selectionEnd
            if (start == end) continue
            ranges["$start:$end"] = start to end
        }
        val starts = editor.selectionModel.blockSelectionStarts
        val ends = editor.selectionModel.blockSelectionEnds
        if (starts.isNotEmpty() && starts.size == ends.size) {
            for (i in starts.indices) {
                val start = minOf(starts[i], ends[i])
                val end = maxOf(starts[i], ends[i])
                if (start == end) continue
                ranges["$start:$end"] = start to end
            }
        }
        val selection = editor.selectionModel
        if (ranges.isEmpty() && selection.hasSelection()) {
            val start = selection.selectionStart
            val end = selection.selectionEnd
            if (start != end) {
                ranges["$start:$end"] = start to end
            }
        }
        return ranges.values.toList()
    }

    private fun collectCurrentOpenFile(project: Project, manager: FileEditorManager): Snapshot? {
        val editor = manager.selectedTextEditor
        val virtualFile = resolveVirtualFile(manager, editor) ?: return null
        val path = displayPath(project, virtualFile)
        if (path.isBlank()) return null
        return Snapshot(path = path)
    }

    fun buttonLabel(enabled: Boolean): String =
        if (enabled) "文件上下文 · 开" else DEFAULT_BUTTON_LABEL

    fun formatChipLabel(snapshot: Snapshot?): String? = formatChipLabel(listOfNotNull(snapshot))

    fun formatChipLabel(snapshots: List<Snapshot>): String? {
        if (snapshots.isEmpty()) return null
        if (snapshots.size == 1) {
            val context = snapshots[0]
            val name = truncateFileName(context.fileName)
            return if (context.hasSelection) "$name ${context.startOffset},${context.endOffset}" else name
        }
        val first = truncateFileName(snapshots[0].fileName)
        val fileCount = snapshots.map { it.path }.distinct().size
        val extra = snapshots.size - 1
        return if (fileCount > 1) "$first 等${fileCount}个文件 · ${snapshots.size}处" else "$first 等${extra}处选区"
    }

    fun formatChipTooltip(snapshot: Snapshot?): String? = formatChipTooltip(listOfNotNull(snapshot))

    fun formatChipTooltip(snapshots: List<Snapshot>): String? {
        if (snapshots.isEmpty()) return null
        return snapshots.joinToString("\n") { context ->
            if (context.hasSelection) {
                "${context.path}, startOffset=${context.startOffset}, endOffset=${context.endOffset}"
            } else context.path
        }
    }

    fun truncateFileName(fileName: String, maxLength: Int = CHIP_NAME_MAX): String {
        if (fileName.length <= maxLength) return fileName
        val keep = (maxLength - 3).coerceAtLeast(1)
        return "..." + fileName.takeLast(keep)
    }

    fun prependToPrompt(prompt: String, snapshot: Snapshot?): String =
        prependToPrompt(prompt, listOfNotNull(snapshot))

    fun prependToPrompt(prompt: String, snapshots: List<Snapshot>): String {
        val prefix = formatPrefix(snapshots) ?: return prompt
        val body = prompt.trim()
        return if (body.isEmpty()) prefix else "$prefix\n\n$body"
    }

    fun formatPrefix(snapshot: Snapshot?): String? = formatPrefix(listOfNotNull(snapshot))

    fun formatPrefix(snapshots: List<Snapshot>): String? {
        if (snapshots.isEmpty()) return null
        return snapshots.map(::singlePrefix).joinToString("\n").ifBlank { null }
    }

    private fun singlePrefix(context: Snapshot): String =
        if (context.hasSelection) {
            buildString {
                append(context.path)
                append(", startOffset=")
                append(context.startOffset)
                append(", endOffset=")
                append(context.endOffset)
            }
        } else context.path

    private fun resolveVirtualFile(manager: FileEditorManager, editor: Editor?): VirtualFile? {
        if (editor != null) {
            FileDocumentManager.getInstance().getFile(editor.document)?.let { return it }
        }
        return manager.selectedFiles.firstOrNull()
    }

    private fun displayPath(project: Project, virtualFile: VirtualFile): String {
        val absolute = virtualFile.path.replace('\\', '/')
        val basePath = project.basePath?.takeIf { it.isNotBlank() } ?: return absolute
        return runCatching {
            WorkspaceTools(basePath).displayPath(File(absolute))
        }.getOrElse {
            val root = File(basePath).canonicalFile
            val target = File(absolute).canonicalFile
            if (target.path.startsWith(root.path + File.separator) || target.path == root.path) {
                root.toPath().relativize(target.toPath()).toString().replace('\\', '/')
            } else {
                absolute
            }
        }
    }
}
