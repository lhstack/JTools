package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.find.FindManager
import com.intellij.find.FindModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * File operation helpers backing the CLI file.* commands.
 *
 * When a [Project] is available the operations go through the IntelliJ platform layer, using the
 * same engines the IDE exposes through its shortcuts:
 *  - read prefers the in-memory Document (unsaved editor content is honored);
 *  - write / patch run inside a [WriteCommandAction] on the Document (undoable, re-indexed, synced
 *    with open editors);
 *  - file lookup walks [ProjectFileIndex] (respects the project excluded / ignored folders,
 *    i.e. the same tree the IDE Find File sees);
 *  - content search uses [FindManager] (the engine behind Find in Files / Ctrl+Shift+F), so regex,
 *    case sensitivity and whole-word semantics match the IDE exactly.
 *
 * When no Project is available (headless / tests) the same operations fall back to direct IO on the
 * workspace files. This is a genuine contract of the runtime (AgentRuntime.project is nullable),
 * not an error-hiding fallback.
 *
 * Every path is resolved and constrained inside the workspace via [WorkspaceTools]: reads require an
 * existing file inside the workspace, writes reject .. and absolute escapes and stay under the
 * root. Text is handled as UTF-8; binary files are rejected for read/grep.
 */
object FileToolSupport {

    private const val MAX_READ_BYTES = 2_000_000L
    private const val HARD_MAX_RESULTS = 2000
    private const val MAX_WALK_FILES = 50_000
    private const val MAX_LINE_LENGTH = 1000
    private val IGNORED_DIRS = setOf(
        ".git", ".hg", ".svn", "node_modules", ".gradle", ".idea",
        "build", "dist", "out", "target", "__pycache__", ".venv", "venv"
    )

    fun read(
        workspace: WorkspaceTools,
        project: Project?,
        path: String,
        offset: Int?,
        limit: Int?,
    ): JsonObject {
        val file = workspace.resolveExistingPath(path)
        if (!file.isFile) throw ToolException.notFile(workspace.displayPath(file))
        if (file.length() > MAX_READ_BYTES) {
            throw ToolException("file `${workspace.displayPath(file)}` is too large to read (> $MAX_READ_BYTES bytes)")
        }
        val text = readText(project, file)
            ?: throw ToolException("file `${workspace.displayPath(file)}` appears to be binary and cannot be read as text")
        val allLines = text.split("\n")
        val totalLines = allLines.size
        val start = (offset ?: 0).coerceIn(0, totalLines)
        val end = if (limit != null) (start + limit.coerceAtLeast(0)).coerceAtMost(totalLines) else totalLines
        val selected = allLines.subList(start, end)
        return JsonObject().apply {
            addProperty("path", workspace.displayPath(file))
            addProperty("content", selected.joinToString("\n"))
            addProperty("total_lines", totalLines)
            addProperty("offset", start)
            addProperty("returned_lines", selected.size)
            addProperty("truncated", start > 0 || end < totalLines)
        }
    }

    fun write(workspace: WorkspaceTools, project: Project?, path: String, content: String): JsonObject {
        val root = workspace.canonicalRoot()
        val joined = WorkspaceTools.joinWorkspacePath(root, path)
        val canonical = WorkspaceTools.canonicalize(joined)
        WorkspaceTools.ensureInsideWorkspace(canonical, root)
        writeText(project, canonical, content)
        return JsonObject().apply {
            addProperty("path", workspace.displayPath(canonical))
            addProperty("bytes", content.toByteArray(Charsets.UTF_8).size)
        }
    }

    fun patch(
        workspace: WorkspaceTools,
        project: Project?,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): JsonObject {
        val file = workspace.resolveExistingPath(path)
        if (!file.isFile) throw ToolException.notFile(workspace.displayPath(file))
        require(oldString.isNotEmpty()) { "old_string must not be empty" }
        val display = workspace.displayPath(file)
        val text = readText(project, file)
            ?: throw ToolException("file `$display` appears to be binary and cannot be patched as text")
        val count = countOccurrences(text, oldString)
        if (count == 0) throw ToolException("old_string was not found in `$display`")
        if (!replaceAll && count > 1) {
            throw ToolException("old_string is not unique in `$display` ($count matches); pass replace_all=true or add more surrounding context")
        }
        val updated = if (replaceAll) {
            text.replace(oldString, newString)
        } else {
            val idx = text.indexOf(oldString)
            text.substring(0, idx) + newString + text.substring(idx + oldString.length)
        }
        writeText(project, file, updated)
        return JsonObject().apply {
            addProperty("path", display)
            addProperty("replacements", if (replaceAll) count else 1)
        }
    }

    fun find(workspace: WorkspaceTools, project: Project?, pattern: String, basePath: String?): JsonArray {
        val base = resolveBase(workspace, basePath)
        val matcher = compileGlob(pattern)
        val results = JsonArray()
        for (file in collectFiles(project, base)) {
            val relative = relativePath(base, file)
            if (matcher.matches(relative) || matcher.matches(file.toPath().fileName)) {
                results.add(workspace.displayPath(file))
            }
        }
        return results
    }

    fun grep(
        workspace: WorkspaceTools,
        project: Project?,
        pattern: String,
        basePath: String?,
        glob: String?,
        ignoreCase: Boolean,
        maxResults: Int,
    ): JsonArray {
        val base = resolveBase(workspace, basePath)
        val globMatcher = glob?.takeIf { it.isNotBlank() }?.let { compileGlob(it) }
        val cap = maxResults.coerceIn(1, HARD_MAX_RESULTS)
        val matcher = LineMatcher.of(project, pattern, ignoreCase)
        val results = JsonArray()
        for (file in collectFiles(project, base)) {
            if (results.size() >= cap) break
            if (globMatcher != null) {
                val relative = relativePath(base, file)
                if (!globMatcher.matches(relative) && !globMatcher.matches(file.toPath().fileName)) continue
            }
            val text = readText(project, file) ?: continue
            val lines = text.split("\n")
            for ((index, line) in lines.withIndex()) {
                if (results.size() >= cap) break
                if (matcher.matches(line)) {
                    results.add(JsonObject().apply {
                        addProperty("path", workspace.displayPath(file))
                        addProperty("line_number", index + 1)
                        addProperty("line", line.take(MAX_LINE_LENGTH))
                    })
                }
            }
        }
        return results
    }

    // -------- content matching --------

    /**
     * Line matcher. With a Project it drives [FindManager] / [FindModel] (the IDE Find in Files
     * engine) so regex + case semantics match the IDE; otherwise a plain Kotlin [Regex].
     */
    private class LineMatcher private constructor(
        private val findManager: FindManager?,
        private val findModel: FindModel?,
        private val regex: Regex?,
    ) {
        fun matches(line: String): Boolean {
            if (findManager != null && findModel != null) {
                return findManager.findString(line, 0, findModel).isStringFound
            }
            return regex!!.containsMatchIn(line)
        }

        companion object {
            fun of(project: Project?, pattern: String, ignoreCase: Boolean): LineMatcher {
                if (project != null) {
                    runCatching { Regex(pattern) }.getOrElse {
                        throw ToolException("invalid regex `$pattern`: ${it.message}")
                    }
                    val model = FindModel().apply {
                        stringToFind = pattern
                        isRegularExpressions = true
                        isCaseSensitive = !ignoreCase
                        isWholeWordsOnly = false
                    }
                    return LineMatcher(FindManager.getInstance(project), model, null)
                }
                return LineMatcher(null, null, compileRegex(pattern, ignoreCase))
            }
        }
    }

    // -------- platform-aware read/write --------

    /** Read UTF-8 text, preferring the in-memory Document when a Project is available. Returns null for binary content. */
    private fun readText(project: Project?, file: File): String? {
        if (project != null) {
            val virtualFile = findVirtualFile(file)
            if (virtualFile != null) {
                val document = ApplicationManager.getApplication().runReadAction<String?> {
                    FileDocumentManager.getInstance().getDocument(virtualFile)?.text
                }
                if (document != null) return document
            }
        }
        val bytes = file.readBytes()
        if (isBinary(bytes)) return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Write UTF-8 text. With a Project, run inside a WriteCommandAction on the Document so the
     * change is undoable, re-indexed, and synced with open editors. Otherwise write directly.
     */
    private fun writeText(project: Project?, file: File, content: String) {
        if (project == null) {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            return
        }
        file.parentFile?.mkdirs()
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val virtualFile = findOrCreateVirtualFile(file)
                val documentManager = FileDocumentManager.getInstance()
                val document = documentManager.getDocument(virtualFile)
                if (document != null) {
                    document.setText(content.replace("\r\n", "\n"))
                    documentManager.saveDocument(document)
                } else {
                    VfsUtil.saveText(virtualFile, content)
                }
            }
        }
    }

    private fun findVirtualFile(file: File): VirtualFile? =
        LocalFileSystem.getInstance().findFileByIoFile(file)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

    private fun findOrCreateVirtualFile(file: File): VirtualFile {
        findVirtualFile(file)?.let { return it }
        val parent = file.parentFile
        val parentVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent)
            ?: VfsUtil.createDirectoryIfMissing(parent.path)
            ?: throw ToolException("cannot resolve parent directory `${parent.path}` in the VFS")
        return parentVf.createChildData(this, file.name)
    }

    // -------- traversal --------

    private fun resolveBase(workspace: WorkspaceTools, basePath: String?): File =
        if (basePath.isNullOrBlank()) workspace.canonicalRoot() else workspace.resolveExistingPath(basePath)

    /**
     * Collect files under base. With a Project, walk the ProjectFileIndex (respects the project
     * excluded / ignored folders); otherwise walk the IO tree.
     */
    private fun collectFiles(project: Project?, base: File): List<File> {
        if (base.isFile) return listOf(base)
        if (project != null) {
            val baseVf = findVirtualFile(base)
            if (baseVf != null) return collectFromIndex(project, baseVf)
        }
        return collectFromIo(base)
    }

    private fun collectFromIndex(project: Project, base: VirtualFile): List<File> {
        val out = ArrayList<File>()
        val fileIndex = ProjectFileIndex.getInstance(project)
        DumbService.getInstance(project).runReadActionInSmartMode(Computable {
            fileIndex.iterateContentUnderDirectory(
                base,
                ContentIterator { child ->
                    if (!child.isDirectory) {
                        child.toNioPathOrNull()?.toFile()?.let { out.add(it) }
                    }
                    out.size < MAX_WALK_FILES
                },
                { candidate ->
                    !(candidate.isDirectory && candidate != base &&
                        (candidate.name in IGNORED_DIRS || candidate.name.startsWith(".")))
                },
            )
        })
        return out
    }

    private fun VirtualFile.toNioPathOrNull(): Path? = runCatching { VfsUtilCore.virtualToIoFile(this).toPath() }.getOrNull()

    private fun collectFromIo(base: File): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(base)
        while (stack.isNotEmpty() && out.size < MAX_WALK_FILES) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name in IGNORED_DIRS || child.name.startsWith(".")) continue
                    stack.addLast(child)
                } else {
                    out.add(child)
                    if (out.size >= MAX_WALK_FILES) break
                }
            }
        }
        return out
    }

    private fun compileGlob(pattern: String) =
        try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (e: Throwable) {
            throw ToolException("invalid glob `$pattern`: ${e.message}")
        }

    private fun compileRegex(pattern: String, ignoreCase: Boolean): Regex =
        try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Throwable) {
            throw ToolException("invalid regex `$pattern`: ${e.message}")
        }

    private fun relativePath(base: File, file: File): Path =
        runCatching { base.toPath().relativize(file.toPath()) }.getOrElse { file.toPath().fileName }

    private fun isBinary(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 8000)
        for (i in 0 until limit) if (bytes[i].toInt() == 0) return true
        return false
    }

    private fun countOccurrences(text: String, sub: String): Int {
        var count = 0
        var idx = text.indexOf(sub)
        while (idx >= 0) {
            count++
            idx = text.indexOf(sub, idx + sub.length)
        }
        return count
    }
}