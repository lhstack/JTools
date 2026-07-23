package com.lhstack.tools.agent

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.lhstack.tools.agent.model.tools.WorkspaceTools
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.SettingService
import java.io.File

/**
 * 编辑器「文件上下文」：仅向用户消息附加当前打开文件路径（及可选选区 offset），不读取文件内容。
 * 开关按项目路径隔离；UI 以引用 chip 展示（如 `...gradle.kts, startOffset=…, endOffset=…`）。
 */
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

    fun collect(project: Project): Snapshot? = ReadAction.compute<Snapshot?, RuntimeException> {
        collectInReadAction(project)
    }

    /**
     * 必须在 read-action 内访问 Editor/SelectionModel/Document（新 IDE 线程模型要求）。
     * 调用方可能在 EDT Timer 上同步状态，不能直接裸读编辑器模型。
     */
    private fun collectInReadAction(project: Project): Snapshot? {
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.selectedTextEditor
        val virtualFile = resolveVirtualFile(manager, editor) ?: return null
        val path = displayPath(project, virtualFile)
        if (path.isBlank()) return null
        val selection = editor?.selectionModel
        if (selection != null && selection.hasSelection()) {
            val start = selection.selectionStart
            val end = selection.selectionEnd
            val document = editor.document
            return Snapshot(
                path = path,
                startOffset = start,
                endOffset = end,
                startLine = document.getLineNumber(start) + 1,
                endLine = document.getLineNumber((end - 1).coerceAtLeast(start)) + 1,
            )
        }
        return Snapshot(path = path)
    }

    fun buttonLabel(enabled: Boolean): String =
        if (enabled) "文件上下文 · 开" else DEFAULT_BUTTON_LABEL

    /**
     * 输入区引用 chip 短文案：
     * - 仅打开：`...Server.java`
     * - 有选区：`...Server.java 962,1470`
     * 完整路径与 startOffset/endOffset 放在 tooltip。
     */
    fun formatChipLabel(snapshot: Snapshot?): String? {
        val context = snapshot ?: return null
        val name = truncateFileName(context.fileName)
        return if (context.hasSelection) {
            "$name ${context.startOffset},${context.endOffset}"
        } else {
            name
        }
    }

    fun formatChipTooltip(snapshot: Snapshot?): String? {
        val context = snapshot ?: return null
        return if (context.hasSelection) {
            buildString {
                append(context.path)
                append('\n')
                append("startOffset=")
                append(context.startOffset)
                append(", endOffset=")
                append(context.endOffset)
            }
        } else {
            context.path
        }
    }

    fun truncateFileName(fileName: String, maxLength: Int = CHIP_NAME_MAX): String {
        if (fileName.length <= maxLength) return fileName
        val keep = (maxLength - 3).coerceAtLeast(1)
        return "..." + fileName.takeLast(keep)
    }

    /** 将文件上下文前缀附加到用户输入开头；无上下文时返回原文本。 */
    fun prependToPrompt(prompt: String, snapshot: Snapshot?): String {
        val prefix = formatPrefix(snapshot) ?: return prompt
        val body = prompt.trim()
        return if (body.isEmpty()) prefix else "$prefix\n\n$body"
    }

    /**
     * 发送给模型的前缀：相对路径；有选区时附 startOffset/endOffset（与产品口径一致）。
     */
    fun formatPrefix(snapshot: Snapshot?): String? {
        val context = snapshot ?: return null
        return if (context.hasSelection) {
            buildString {
                append(context.path)
                append(", startOffset=")
                append(context.startOffset)
                append(", endOffset=")
                append(context.endOffset)
            }
        } else {
            context.path
        }
    }

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
