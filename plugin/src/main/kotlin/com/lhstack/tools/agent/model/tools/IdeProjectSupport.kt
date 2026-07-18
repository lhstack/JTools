package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.ide.util.gotoByName.ChooseByNameViewModel
import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.ide.util.gotoByName.GotoFileModel
import com.intellij.find.FindModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.util.Processor
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.WeakHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

internal class IdeProjectSupport(
    private val workspace: WorkspaceTools,
    val project: Project,
) {
    private val root: File get() = workspace.canonicalRoot()

    fun readFiles(requests: List<ReadFileRequest>): List<JsonObject> {
        require(requests.isNotEmpty()) { "read requests must not be empty" }
        return smartRead {
            val files = requests.map { request ->
                checkToolThreadCancellation()
                resolveProjectFileInReadAction(request.path)
            }
            require(files.map { it.url }.distinct().size == files.size) { "read paths must not contain duplicates" }
            requests.zip(files).map { (request, file) -> readFileInReadAction(file, request.ranges, request.maxLines) }
        }
    }

    private fun readFileInReadAction(file: VirtualFile, ranges: List<LineRange>, maxLines: Int): JsonObject {
        require(!file.isDirectory) { "path `${displayPathInReadAction(file)}` is not a file" }
        val text = readTextInReadAction(file)
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
            addProperty("path", displayPathInReadAction(file))
            addProperty("charset", file.charset.name())
            addProperty("content_kind", contentKindInReadAction(file))
            addProperty("line_count", lines.size)
            addProperty("truncated", emitted >= maxLines && selectedRanges.sumOf { it.end - it.start + 1 } > emitted)
            add("lines", output)
        }
    }

    fun writeFiles(requests: List<WriteFileRequest>): List<JsonObject> {
        require(requests.isNotEmpty()) { "write requests must not be empty" }
        val prepared = requests.map { request ->
            val target = resolveWritableProjectFile(request.path)
            val existing = readModel { findLocalFileInReadAction(target) }
            require(existing == null || request.overwrite) {
                "file `${workspace.displayPath(target)}` already exists; set overwrite=true to replace it"
            }
            PreparedWrite(request, target, existing)
        }
        require(prepared.map { it.target }
            .distinct().size == prepared.size) { "write paths must not contain duplicates" }

        val results = runOnEdt {
            val values = mutableListOf<JsonObject>()
            WriteCommandAction.runWriteCommandAction(project, "JTools Write Files", null, {
                prepared.forEach { item ->
                    val file = item.existing ?: createLocalFileInWriteAction(item.target)
                    val document = FileDocumentManager.getInstance().getDocument(file)
                        ?: throw ToolException("`${workspace.displayPath(item.target)}` is not a writable text file")
                    document.setText(StringUtil.convertLineSeparators(item.request.content))
                    FileDocumentManager.getInstance().saveDocument(document)
                    values.add(JsonObject().apply {
                        addProperty("path", workspace.displayPath(item.target))
                        addProperty("charset", file.charset.name())
                        addProperty("created", item.existing == null)
                        addProperty("overwritten", item.existing != null)
                    })
                }
            })
            values
        }
        return results
    }

    fun replaceFiles(requests: List<ReplaceTextRequest>): List<JsonObject> {
        require(requests.isNotEmpty()) { "replace requests must not be empty" }
        val prepared = requests.map { request -> prepareReplacement(request) }
        require(prepared.map { it.file.url }.distinct().size == prepared.size) {
            "replace batch supports at most one edit per file"
        }

        runOnEdt {
            WriteCommandAction.runWriteCommandAction(project, "JTools Replace Text In Files", null, {
                prepared.forEach { item ->
                    item.ranges.asReversed().forEach { range ->
                        item.document.replaceString(range.first, range.last + 1, item.request.newText)
                    }
                }
                prepared.forEach { FileDocumentManager.getInstance().saveDocument(it.document) }
            })
        }
        return prepared.map { item ->
            val metadata = readModel { FileMetadata(displayPathInReadAction(item.file), item.file.charset.name()) }
            JsonObject().apply {
                addProperty("path", metadata.path)
                addProperty("replacements", item.ranges.size)
                addProperty("charset", metadata.charset)
            }
        }
    }

    private fun prepareReplacement(request: ReplaceTextRequest): PreparedReplacement {
        require(request.oldText.isNotEmpty()) { "old_text must not be empty for `${request.path}`" }
        val file = resolveProjectFile(request.path)
        val document = readModel {
            require(!file.isDirectory) { "path `${displayPathInReadAction(file)}` is not a file" }
            FileDocumentManager.getInstance().getDocument(file)
                ?: throw ToolException("`${displayPathInReadAction(file)}` is not a writable text file")
        }
        val matches = findOccurrences(document.text, request.oldText, request.caseSensitive)
        require(matches.isNotEmpty()) { "old_text was not found in `${request.path}`" }
        require(request.replaceAll || matches.size == 1) {
            "old_text occurs ${matches.size} times in `${request.path}`; provide more context or set replace_all=true"
        }
        return PreparedReplacement(
            request,
            file,
            document,
            if (request.replaceAll) matches else listOf(matches.single())
        )
    }

    fun findFiles(pattern: String, limit: Int, includeGlobal: Boolean = false): JsonObject {
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        return serializedIndexedSearch {
            smartRead {
                val model = GotoFileModel(project)
                val elements = searchChooseByName(model, pattern, limit, includeGlobal)
                val files = linkedMapOf<String, VirtualFile>()
                elements.forEach { element ->
                val file = when (element) {
                    is PsiFileSystemItem -> element.virtualFile
                    is PsiElement -> element.containingFile?.virtualFile
                    is VirtualFile -> element
                    else -> null
                } ?: return@forEach
                if (file.isValid && !file.isDirectory) files.putIfAbsent(file.url, file)
            }
                filesResultInReadAction(files.values, elements.truncated, if (includeGlobal) "global" else "project")
            }
        }
    }

    fun findClasses(pattern: String, limit: Int, includeGlobal: Boolean = false): JsonObject {
        require(pattern.isNotBlank()) { "pattern must not be blank" }
        return serializedIndexedSearch {
            smartRead {
                val model = GotoClassModel2(project)
                val elements = searchChooseByName(model, pattern, limit, includeGlobal)
                val classes = JsonArray()
                elements.forEach { element ->
                val item = element as? NavigationItem ?: return@forEach
                val psi = element as? PsiElement
                val file = psi?.containingFile?.virtualFile
                classes.add(JsonObject().apply {
                    addProperty("name", item.name.orEmpty())
                    addProperty("qualified_name", model.getFullName(element).orEmpty())
                    if (file != null) addProperty("path", displayPathInReadAction(file))
                    psi?.textOffset?.takeIf { it >= 0 }?.let { offset ->
                        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
                        if (document != null) addProperty("line", document.getLineNumber(offset) + 1)
                    }
                    addProperty("scope", if (includeGlobal) "global" else "project")
                })
            }
                JsonObject().apply {
                    addProperty("count", classes.size())
                    addProperty("truncated", elements.truncated)
                    add("classes", classes)
                }
            }
        }
    }

    private fun searchChooseByName(
        model: ChooseByNameModel,
        pattern: String,
        limit: Int,
        includeGlobal: Boolean,
    ): ChooseByNameResults {
        val provider = com.intellij.ide.util.gotoByName.ChooseByNameModelEx.getItemProvider(model, null)
        val results = ArrayList<Any>(limit)
        var truncated = false
        val viewModel = IndexedSearchViewModel(project, model, includeGlobal, limit + 1)
        withInterruptAwareIndicator { indicator ->
            provider.filterElements(viewModel, pattern, includeGlobal, indicator, Processor { element ->
                checkToolThreadCancellation()
                ProgressManager.checkCanceled()
                if (results.size >= limit) {
                    truncated = true
                    return@Processor false
                }
                results.add(element)
                true
            })
        }
        return ChooseByNameResults(results, truncated)
    }

    fun searchText(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        filePattern: String?,
        contextLines: Int,
        limit: Int,
    ): JsonObject {
        require(query.isNotEmpty()) { "query must not be empty" }
        return serializedIndexedSearch {
            waitForIndexes()
            searchTextWithIndex(query, regex, caseSensitive, filePattern, contextLines, limit)
        }
    }

    /**
     * FindInProjectUtil owns its read actions and explicitly forbids callers
     * from invoking findUsages inside an existing read action.
     */
    private fun searchTextWithIndex(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        filePattern: String?,
        contextLines: Int,
        limit: Int,
    ): JsonObject {
        val model = textSearchModel(query, regex, caseSensitive, filePattern)
        val results = JsonArray()
        var truncated = false
        val presentation = FindInProjectUtil.setupProcessPresentation(
            project,
            true,
            FindInProjectUtil.setupViewPresentation(model),
        )
        withInterruptAwareIndicator { indicator ->
            FindInProjectUtil.findUsages(
                model,
                project,
                indicator,
                presentation,
                emptySet(),
                Processor { usage: UsageInfo ->
                    checkToolThreadCancellation()
                    if (results.size() >= limit) {
                        truncated = true
                        return@Processor false
                    }
                    appendIndexedUsage(usage, contextLines, results)
                    true
                },
            )
        }
        return JsonObject().apply {
            addProperty("count", results.size())
            addProperty("truncated", truncated)
            addProperty("indexed", true)
            add("matches", results)
        }
    }

    private fun textSearchModel(
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
        filePattern: String?,
    ): FindModel = FindModel().apply {
        stringToFind = query
        isRegularExpressions = regex
        isCaseSensitive = caseSensitive
        isWholeWordsOnly = false
        isMultipleFiles = true
        isProjectScope = true
        isFindAll = true
        fileFilter = filePattern?.takeIf { it.isNotBlank() }
    }

    private fun waitForIndexes() {
        checkToolThreadCancellation()
        DumbService.getInstance(project).waitForSmartMode()
        checkToolThreadCancellation()
    }

    private fun appendIndexedUsage(usage: UsageInfo, contextLines: Int, results: JsonArray) {
        val file = usage.virtualFile ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val segment = usage.segment ?: return
        val startLine = document.getLineNumber(segment.startOffset)
        val endLine = document.getLineNumber(segment.endOffset.coerceAtMost(document.textLength))
        val from = maxOf(0, startLine - contextLines)
        val to = minOf(document.lineCount - 1, endLine + contextLines)
        val context = JsonArray()
        for (line in from..to) {
            context.add(JsonObject().apply {
                addProperty("line", line + 1)
                addProperty("text", document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))))
            })
        }
        results.add(JsonObject().apply {
            addProperty("path", displayPathInReadAction(file))
            addProperty("line", startLine + 1)
            addProperty("content", document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(startLine), document.getLineEndOffset(startLine))))
            add("lines", context)
        })
    }

    /**
     * Formats only the explicitly selected line ranges. The caller must provide a
     * range so formatting cannot silently reformat a collaborator's changes.
     */
    fun format(path: String, ranges: List<LineRange>, timeoutSecs: Int): JsonObject {
        require(ranges.isNotEmpty()) { "ranges must contain at least one line range" }
        val file = resolveProjectFile(path)
        val psiFile = ApplicationManager.getApplication().runReadAction(
            Computable { PsiManager.getInstance(project).findFile(file) }
        ) ?: throw ToolException("`${displayPath(file)}` is not supported by an installed IDE file-type plugin")
        val document = ApplicationManager.getApplication().runReadAction(
            Computable { FileDocumentManager.getInstance().getDocument(file) }
        ) ?: throw ToolException("`${displayPath(file)}` is not a writable text file")
        val selectedRanges = ranges.map { lineRange ->
            require(lineRange.start >= 1 && lineRange.end >= lineRange.start) {
                "invalid line range ${lineRange.start}-${lineRange.end}"
            }
            require(lineRange.end <= document.lineCount) {
                "line range ${lineRange.start}-${lineRange.end} exceeds file line count ${document.lineCount}"
            }
            TextRange(
                document.getLineStartOffset(lineRange.start - 1),
                document.getLineEndOffset(lineRange.end - 1),
            )
        }
        require(selectedRanges.zipWithNext().all { (left, right) -> left.endOffset <= right.startOffset }) {
            "formatting line ranges must not overlap or be out of order"
        }

        val done = CountDownLatch(1)
        val processor = ReformatCodeProcessor(psiFile, selectedRanges.toTypedArray()).apply {
            setPostRunnable(done::countDown)
        }
        ApplicationManager.getApplication().invokeLater(processor::run)
        require(done.await(timeoutSecs.toLong(), TimeUnit.SECONDS)) {
            "formatting `${displayPath(file)}` timed out"
        }
        saveDocument(file)
        return JsonObject().apply {
            addProperty("path", displayPath(file))
            addProperty("formatted", true)
            add("ranges", JsonArray().apply {
                ranges.forEach { range ->
                    add(JsonObject().apply {
                        addProperty("start", range.start)
                        addProperty("end", range.end)
                    })
                }
            })
        }
    }

    fun resolveProjectFile(path: String): VirtualFile = readModel { resolveProjectFileInReadAction(path) }

    fun displayPath(file: VirtualFile): String = readModel { displayPathInReadAction(file) }

    fun saveDocument(file: VirtualFile) {
        val document = readModel { FileDocumentManager.getInstance().getDocument(file) } ?: return
        runOnEdt { FileDocumentManager.getInstance().saveDocument(document) }
    }

    private fun resolveProjectFileInReadAction(path: String): VirtualFile {
        val ioFile = workspace.resolveExistingPath(path)
        val file = findLocalFileInReadAction(ioFile)
            ?: throw ToolException("file `${workspace.displayPath(ioFile)}` was not found in the IDE VFS")
        require(isProjectFileInReadAction(file)) {
            "path `${workspace.displayPath(ioFile)}` is outside the project root"
        }
        return file
    }

    private fun displayPathInReadAction(file: VirtualFile): String {
        return when (file.fileSystem.protocol) {
            LocalFileSystem.PROTOCOL -> workspace.displayPath(File(file.path))
            else -> file.url
        }
    }

    private fun filesResultInReadAction(
        files: Collection<VirtualFile>,
        truncated: Boolean,
        scope: String = "project",
    ): JsonObject =
        JsonObject().apply {
            val array = JsonArray()
            files.forEach { file ->
                array.add(JsonObject().apply {
                    addProperty("path", displayPathInReadAction(file))
                    addProperty("name", file.name)
                    addProperty("scope", scope)
                })
            }
            addProperty("count", array.size())
            addProperty("truncated", truncated)
            add("files", array)
        }

    private fun projectFilesInReadAction(): LinkedHashSet<VirtualFile> {
        val files = linkedSetOf<VirtualFile>()
        val root = project.baseDir ?: return files
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
            checkToolThreadCancellation()
            ProgressManager.checkCanceled()
            if (!file.isDirectory && isProjectFileInReadAction(file)) files.add(file)
            true
        }
        return files
    }

    private fun readTextInReadAction(file: VirtualFile): String =
        FileDocumentManager.getInstance().getDocument(file)?.text
            ?: PsiManager.getInstance(project).findFile(file)?.text
            ?: VfsUtilCore.loadText(file)

    private fun contentKindInReadAction(file: VirtualFile): String = "project"

    private fun resolveWritableProjectFile(path: String): File {
        val relative = WorkspaceTools.normalizeRelativeSegments(path) ?: throw ToolException.invalidPath(path)
        val target = WorkspaceTools.canonicalize(root.resolve(relative))
        WorkspaceTools.ensureInsideWorkspace(target, root)
        return target
    }

    private fun createLocalFileInWriteAction(file: File): VirtualFile {
        val parent = file.parentFile ?: throw ToolException("cannot resolve parent directory for `${file.path}`")
        val parentDir = VfsUtil.createDirectoryIfMissing(parent.path)
            ?: throw ToolException("cannot create directory `${parent.path}`")
        return parentDir.findChild(file.name) ?: parentDir.createChildData(this, file.name)
    }

    private fun findLocalFileInReadAction(file: File): VirtualFile? =
        LocalFileSystem.getInstance().findFileByIoFile(file)

    private fun isProjectFileInReadAction(file: VirtualFile): Boolean {
        val basePath = project.basePath ?: return false
        if (file.fileSystem.protocol != LocalFileSystem.PROTOCOL) return false
        val projectRoot = WorkspaceTools.canonicalize(File(basePath))
        val candidate = WorkspaceTools.canonicalize(File(file.path))
        return candidate.toPath().startsWith(projectRoot.toPath())
    }

    private fun <T> withInterruptAwareIndicator(action: (EmptyProgressIndicator) -> T): T {
        val owner = Thread.currentThread()
        val finished = AtomicBoolean(false)
        val indicator = EmptyProgressIndicator()
        val watcher = Thread({
            while (!finished.get() && !owner.isInterrupted) {
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            if (owner.isInterrupted) indicator.cancel()
        }, "jtools-index-search-cancel").apply {
            isDaemon = true
            start()
        }
        try {
            return action(indicator)
        } finally {
            finished.set(true)
            watcher.interrupt()
        }
    }

    private fun checkToolThreadCancellation() {
        if (Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("tool execution interrupted")
        }
    }

    /** JetBrains search indexes are thread-safe, but concurrent broad queries
     * from one model turn compete for the same project index/read resources.
     * Serialize only indexed searches per Project; cancellation while waiting
     * remains interruptible and different projects can still search in parallel.
     */
    private fun <T> serializedIndexedSearch(action: () -> T): T {
        val semaphore = searchSemaphore(project)
        semaphore.acquire()
        try {
            checkToolThreadCancellation()
            return action()
        } finally {
            semaphore.release()
        }
    }

    private fun <T> smartRead(action: () -> T): T =
        ReadAction.nonBlocking(Callable { action() })
            .inSmartMode(project)
            .executeSynchronously()

    private fun <T> readModel(action: () -> T): T =
        ApplicationManager.getApplication().runReadAction(Computable { action() })

    private fun <T> runOnEdt(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        var result: Result<T>? = null
        application.invokeAndWait { result = runCatching(action) }
        return result!!.getOrThrow()
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


}

internal data class ReadFileRequest(val path: String, val ranges: List<LineRange>, val maxLines: Int)
internal data class WriteFileRequest(val path: String, val content: String, val overwrite: Boolean)
internal data class ReplaceTextRequest(
    val path: String,
    val oldText: String,
    val newText: String,
    val replaceAll: Boolean,
    val caseSensitive: Boolean,
)

private data class PreparedWrite(val request: WriteFileRequest, val target: File, val existing: VirtualFile?)
private data class PreparedReplacement(
    val request: ReplaceTextRequest,
    val file: VirtualFile,
    val document: Document,
    val ranges: List<IntRange>,
)

private val PROJECT_SEARCH_SEMAPHORES = WeakHashMap<Project, Semaphore>()
private val PROJECT_SEARCH_SEMAPHORES_LOCK = Any()

private fun searchSemaphore(project: Project): Semaphore = synchronized(PROJECT_SEARCH_SEMAPHORES_LOCK) {
    PROJECT_SEARCH_SEMAPHORES.getOrPut(project) { Semaphore(1, true) }
}

internal class IndexedSearchViewModel(
    private val searchProject: Project,
    private val searchModel: ChooseByNameModel,
    private val includeGlobal: Boolean,
    private val resultLimit: Int,
) : ChooseByNameViewModel {
    override fun getProject(): Project = searchProject
    override fun getModel(): ChooseByNameModel = searchModel
    override fun isSearchInAnyPlace(): Boolean = includeGlobal
    override fun transformPattern(pattern: String): String = pattern
    override fun canShowListForEmptyPattern(): Boolean = false
    override fun getMaximumListSizeLimit(): Int = resultLimit
}

private data class ChooseByNameResults(val items: List<Any>, val truncated: Boolean) : Iterable<Any> by items
private data class FileMetadata(val path: String, val charset: String)
internal data class LineRange(val start: Int, val end: Int)
