package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.find.FindManager
import com.intellij.find.FindModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import java.io.File
import java.nio.file.FileSystems

internal class IdeProjectSupport(
    private val workspace: WorkspaceTools,
    val project: Project,
) {
    private val root: File get() = workspace.canonicalRoot()
    private val rootVf: VirtualFile
        get() = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)
            ?: throw ToolException("workspace `${root.path}` is not available in the IDE VFS")

    fun read(path: String, ranges: List<LineRange>, maxLines: Int): JsonObject {
        val file = resolveReadableFile(path)
        require(!file.isDirectory) { "path `${displayPath(file)}` is not a file" }
        val text = readText(file)
        val lines = text.split('\n')
        val selectedRanges = if (ranges.isEmpty()) listOf(LineRange(1, minOf(lines.size, maxLines))) else ranges
        val output = JsonArray()
        var emitted = 0
        for (range in selectedRanges) {
            require(range.start >= 1 && range.end >= range.start) { "invalid line range ${range.start}-${range.end}" }
            val end = minOf(range.end, lines.size)
            for (lineNumber in range.start..end) {
                if (emitted >= maxLines) break
                output.add(JsonObject().apply {
                    addProperty("line", lineNumber)
                    addProperty("text", lines[lineNumber - 1].trimEnd('\r'))
                })
                emitted++
            }
            if (emitted >= maxLines) break
        }
        return JsonObject().apply {
            addProperty("path", displayPath(file))
            addProperty("charset", file.charset.name())
            addProperty("content_kind", contentKind(file))
            addProperty("line_count", lines.size)
            addProperty("truncated", emitted >= maxLines && selectedRanges.sumOf { it.end - it.start + 1 } > emitted)
            add("lines", output)
        }
    }

    fun write(path: String, content: String, overwrite: Boolean): JsonObject {
        val target = resolveWritableProjectFile(path)
        val existing = findLocalFile(target)
        require(existing == null || overwrite) { "file `${workspace.displayPath(target)}` already exists; set overwrite=true to replace it" }
        val normalized = StringUtil.convertLineSeparators(content)
        runOnEdt {
            WriteCommandAction.runWriteCommandAction(project, "JTools Write File", null, {
                val file = existing ?: createLocalFile(target)
                val document = FileDocumentManager.getInstance().getDocument(file)
                    ?: throw ToolException("`${workspace.displayPath(target)}` is not a writable text file")
                document.setText(normalized)
                FileDocumentManager.getInstance().saveDocument(document)
            })
        }
        val file = findLocalFile(target) ?: throw ToolException("failed to create `${workspace.displayPath(target)}`")
        return JsonObject().apply {
            addProperty("path", workspace.displayPath(target))
            addProperty("charset", file.charset.name())
            addProperty("created", existing == null)
            addProperty("overwritten", existing != null)
        }
    }

    fun replace(path: String, oldText: String, newText: String, replaceAll: Boolean, caseSensitive: Boolean): JsonObject {
        require(oldText.isNotEmpty()) { "old_text must not be empty" }
        val file = resolveProjectFile(path)
        require(!file.isDirectory) { "path `${displayPath(file)}` is not a file" }
        val document = ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)
        } ?: throw ToolException("`${displayPath(file)}` is not a writable text file")
        val ranges = findOccurrences(document.text, oldText, caseSensitive)
        require(ranges.isNotEmpty()) { "old_text was not found in `${displayPath(file)}`" }
        require(replaceAll || ranges.size == 1) {
            "old_text occurs ${ranges.size} times in `${displayPath(file)}`; provide more context or set replace_all=true"
        }
        val replacements = if (replaceAll) ranges else listOf(ranges.single())
        runOnEdt {
            WriteCommandAction.runWriteCommandAction(project, "JTools Replace Text", null, {
                for (range in replacements.asReversed()) {
                    document.replaceString(range.first, range.last + 1, newText)
                }
                FileDocumentManager.getInstance().saveDocument(document)
            })
        }
        return JsonObject().apply {
            addProperty("path", displayPath(file))
            addProperty("replacements", replacements.size)
            addProperty("charset", file.charset.name())
        }
    }

    fun findFiles(name: String, match: NameMatch, includeLibraries: Boolean, limit: Int): JsonObject {
        require(name.isNotBlank()) { "name must not be blank" }
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        val matcher = nameMatcher(name, match)
        val results = linkedMapOf<String, VirtualFile>()
        var truncated = false
        smartRead {
            FilenameIndex.processAllFileNames(Processor { candidate ->
                if (!matcher(candidate)) return@Processor true
                var keepGoing = true
                FilenameIndex.processFilesByName(candidate, false, scope, Processor { file ->
                    if (includeLibraries || isProjectFile(file)) {
                        results.putIfAbsent(file.url, file)
                        if (results.size >= limit) {
                            truncated = true
                            keepGoing = false
                            return@Processor false
                        }
                    }
                    true
                })
                if (!keepGoing) return@Processor false
                true
            }, scope, null)
        }
        return filesResult(results.values, truncated)
    }

    fun searchText(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        includeLibraries: Boolean,
        filePattern: String?,
        contextLines: Int,
        limit: Int,
    ): JsonObject {
        require(query.isNotEmpty()) { "query must not be empty" }
        val matcher = IdeFindMatcher(project, query, regex, caseSensitive)
        val fileMatcher = filePattern?.takeIf { it.isNotBlank() }?.let(::globMatcher)
        val results = JsonArray()
        var truncated = false
        val files = searchFiles(includeLibraries, limit)
        for (file in files) {
            if (results.size() >= limit) { truncated = true; break }
            if (fileMatcher != null && !fileMatcher(file.name)) continue
            val text = runCatching { readText(file) }.getOrNull() ?: continue
            val lines = text.split('\n')
            for ((index, rawLine) in lines.withIndex()) {
                if (!matcher.matches(rawLine)) continue
                val from = maxOf(0, index - contextLines)
                val to = minOf(lines.lastIndex, index + contextLines)
                val context = JsonArray()
                for (lineIndex in from..to) {
                    context.add(JsonObject().apply {
                        addProperty("line", lineIndex + 1)
                        addProperty("text", lines[lineIndex].trimEnd('\r'))
                    })
                }
                results.add(JsonObject().apply {
                    addProperty("path", displayPath(file))
                    addProperty("line", index + 1)
                    addProperty("content", rawLine.trimEnd('\r'))
                    add("lines", context)
                })
                if (results.size() >= limit) { truncated = true; break }
            }
        }
        return JsonObject().apply {
            addProperty("count", results.size())
            addProperty("truncated", truncated)
            add("matches", results)
        }
    }

    fun resolveProjectFile(path: String): VirtualFile {
        val file = workspace.resolveExistingPath(path)
        val vf = findLocalFile(file) ?: throw ToolException("file `${workspace.displayPath(file)}` was not found in the IDE VFS")
        require(isProjectFile(vf)) { "path `${workspace.displayPath(file)}` is outside the project content" }
        return vf
    }

    fun displayPath(file: VirtualFile): String {
        if (file.fileSystem.protocol == JarFileSystem.PROTOCOL) return file.url
        val ioFile = runCatching { VfsUtilCore.virtualToIoFile(file) }.getOrNull()
        return if (ioFile != null) workspace.displayPath(ioFile) else file.url
    }

    private fun filesResult(files: Collection<VirtualFile>, truncated: Boolean): JsonObject = JsonObject().apply {
        val array = JsonArray()
        files.forEach { file -> array.add(JsonObject().apply {
            addProperty("path", displayPath(file))
            addProperty("name", file.name)
            addProperty("scope", if (isProjectFile(file)) "project" else "library")
            addProperty("protocol", file.fileSystem.protocol)
        }) }
        addProperty("count", array.size())
        addProperty("truncated", truncated)
        add("files", array)
    }

    private fun searchFiles(includeLibraries: Boolean, limit: Int): LinkedHashSet<VirtualFile> {
        val files = linkedSetOf<VirtualFile>()
        smartRead {
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (!file.isDirectory) files.add(file)
                true
            }
            if (includeLibraries) {
                collectLibraryFiles(files, limit)
            }
        }
        return files
    }

    private fun collectLibraryFiles(files: MutableSet<VirtualFile>, limit: Int) {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val visitedRoots = mutableSetOf<String>()
        FilenameIndex.processAllFileNames(Processor { name ->
            FilenameIndex.processFilesByName(name, false, GlobalSearchScope.allScope(project), Processor { file ->
                if (isProjectFile(file) || (!fileIndex.isInLibrarySource(file) && !fileIndex.isInLibraryClasses(file))) {
                    return@Processor true
                }
                val root = fileIndex.getSourceRootForFile(file) ?: fileIndex.getClassRootForFile(file) ?: return@Processor true
                if (visitedRoots.add(root.url)) {
                    VfsUtilCore.iterateChildrenRecursively(root, null, com.intellij.openapi.roots.ContentIterator { candidate ->
                        if (!candidate.isDirectory && isLibraryTextCandidate(candidate)) files.add(candidate)
                        files.size < limit
                    })
                }
                files.size < limit
            })
            files.size < limit
        }, GlobalSearchScope.allScope(project), null)
    }

    private fun isLibraryTextCandidate(file: VirtualFile): Boolean =
        file.extension?.lowercase() in setOf(
            "java", "kt", "kts", "groovy", "scala", "xml", "properties", "json", "yaml", "yml",
            "md", "txt", "js", "ts", "tsx", "jsx", "vue", "html", "css", "sql"
        )

    private fun readText(file: VirtualFile): String = ReadAction.compute<String, RuntimeException> {
        FileDocumentManager.getInstance().getDocument(file)?.text
            ?: PsiManager.getInstance(project).findFile(file)?.text
            ?: VfsUtilCore.loadText(file)
    }

    private fun contentKind(file: VirtualFile): String = when {
        file.extension.equals("class", true) -> "decompiled"
        file.fileSystem.protocol == JarFileSystem.PROTOCOL -> "jar"
        file.fileSystem.protocol == "jrt" -> "jrt"
        else -> "project"
    }

    private fun resolveReadableFile(path: String): VirtualFile {
        val trimmed = path.trim()
        if (trimmed.startsWith("jar://") || trimmed.startsWith("jrt://") || trimmed.startsWith("file://")) {
            val file = VirtualFileManager.getInstance().findFileByUrl(trimmed)
                ?: throw ToolException("IDE file `$trimmed` was not found")
            require(file.fileSystem.protocol != JarFileSystem.PROTOCOL || !file.isDirectory) { "path `$trimmed` is a directory" }
            return file
        }
        return resolveProjectFile(trimmed)
    }

    private fun resolveWritableProjectFile(path: String): File {
        val relative = WorkspaceTools.normalizeRelativeSegments(path) ?: throw ToolException.invalidPath(path)
        val target = WorkspaceTools.canonicalize(root.resolve(relative))
        WorkspaceTools.ensureInsideWorkspace(target, root)
        return target
    }

    private fun createLocalFile(file: File): VirtualFile {
        val parent = file.parentFile ?: throw ToolException("cannot resolve parent directory for `${file.path}`")
        val parentVf = VfsUtil.createDirectoryIfMissing(parent.path)
            ?: throw ToolException("cannot create directory `${parent.path}`")
        return parentVf.findChild(file.name) ?: parentVf.createChildData(this, file.name)
    }

    private fun findLocalFile(file: File): VirtualFile? =
        LocalFileSystem.getInstance().findFileByIoFile(file)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

    private fun isProjectFile(file: VirtualFile): Boolean = ProjectFileIndex.getInstance(project).isInContent(file)

    private fun <T> smartRead(action: () -> T): T {
        DumbService.getInstance(project).waitForSmartMode()
        return ReadAction.compute<T, RuntimeException> { action() }
    }

    private fun runOnEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeAndWait(action)
    }

    private fun findOccurrences(text: String, needle: String, caseSensitive: Boolean): List<IntRange> {
        val results = mutableListOf<IntRange>()
        var from = 0
        while (from <= text.length - needle.length) {
            val index = text.indexOf(needle, from, ignoreCase = !caseSensitive)
            if (index < 0) break
            results.add(index until index + needle.length)
            from = index + needle.length
        }
        return results
    }

    private fun nameMatcher(pattern: String, match: NameMatch): (String) -> Boolean = when (match) {
        NameMatch.EXACT -> { candidate -> candidate.equals(pattern, ignoreCase = true) }
        NameMatch.CONTAINS -> { candidate -> candidate.contains(pattern, ignoreCase = true) }
        NameMatch.GLOB -> {
            val matcher: (String) -> Boolean = globMatcher(pattern)
            matcher
        }
    }

    private fun globMatcher(pattern: String): (String) -> Boolean {
        val matcher = try { FileSystems.getDefault().getPathMatcher("glob:$pattern") }
        catch (e: Throwable) { throw ToolException("invalid glob `$pattern`: ${e.message}") }
        return { name -> matcher.matches(FileSystems.getDefault().getPath(name)) }
    }
}

internal data class LineRange(val start: Int, val end: Int)
internal enum class NameMatch { EXACT, CONTAINS, GLOB }

private class IdeFindMatcher(project: Project, query: String, regex: Boolean, caseSensitive: Boolean) {
    private val manager = FindManager.getInstance(project)
    private val model = FindModel().apply {
        stringToFind = query
        isRegularExpressions = regex
        isCaseSensitive = caseSensitive
        isWholeWordsOnly = false
    }

    fun matches(text: String): Boolean = manager.findString(text, 0, model).isStringFound
}