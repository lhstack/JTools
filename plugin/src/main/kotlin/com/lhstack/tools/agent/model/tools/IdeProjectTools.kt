package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.PsiManager
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.jetbrains.concurrency.CancellablePromise

internal class ReadFileTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use when you already know one or more target file paths and need their source, documentation, dependency source, JAR resource, JRT source, or on-demand decompiled class text. Use path for one file or files for a batch of up to 20 files; do not provide both. Project files are read from the current IDE Document when available, so unsaved editor changes are included. For large files, request only the needed 1-based line ranges.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Single project-relative path, or a jar://, jrt://, or file:// URL returned by find_files."},"lines":{"type":"array","description":"Optional 1-based line ranges for the single path.","items":{"type":"object","properties":{"start":{"type":"integer"},"end":{"type":"integer"}},"required":["start","end"]}},"max_lines":{"type":"integer","description":"Maximum returned lines for the single path; default 1000, hard limit 5000."},"files":{"type":"array","minItems":1,"maxItems":20,"description":"Batch read requests. Do not combine with path.","items":{"type":"object","properties":{"path":{"type":"string"},"lines":{"type":"array","items":{"type":"object","properties":{"start":{"type":"integer"},"end":{"type":"integer"}},"required":["start","end"]}},"max_lines":{"type":"integer","description":"Default 1000, hard limit 5000."}},"required":["path"]}}},"anyOf":[{"required":["path"]},{"required":["files"]}]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val batch = input.fileBatch("path", "files")
        val requests = batch.items.map(::parseReadRequest)
        val results = support.readFiles(requests)
        return if (batch.isBatch) batchResult("files", results) else results.single()
    }

    private fun parseReadRequest(input: JsonObject): ReadFileRequest {
        val ranges = input.getAsJsonArray("lines")?.map { element ->
            val range = element.obj("line range")
            LineRange(range.int("start"), range.int("end"))
        }.orEmpty()
        return ReadFileRequest(input.string("path"), ranges, input.intOr("max_lines", 1000).coerceIn(1, 5000))
    }

    companion object { const val NAME = "read_file" }
}

internal class WriteFileTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use for one or more new project files or intentional complete replacements. Use path/content for one file or files for a batch of up to 20 files; do not provide both. All batch requests are validated before the IDE write command starts. For focused changes to existing files, prefer replace_text_in_file.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Single project-relative project text-file path."},"content":{"type":"string","description":"Complete content for the single path."},"overwrite":{"type":"boolean","description":"Allow replacing the single existing file; default false."},"files":{"type":"array","minItems":1,"maxItems":20,"description":"Batch write requests. Do not combine with path/content.","items":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"overwrite":{"type":"boolean","description":"Default false."}},"required":["path","content"]}}},"anyOf":[{"required":["path","content"]},{"required":["files"]}]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val batch = input.fileBatch("path", "files")
        val requests = batch.items.map { item ->
            WriteFileRequest(
                item.string("path"),
                item.stringValue("content"),
                item.booleanOr("overwrite", false),
            )
        }
        val results = support.writeFiles(requests)
        return if (batch.isBatch) batchResult("files", results) else results.single()
    }

    companion object { const val NAME = "write_file" }
}

internal class ReplaceTextInFileTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use for focused edits to one or more existing project files. Use path/old_text/new_text for one file or files for a batch of up to 20 edits; do not provide both. Every batch edit is validated before any replacement starts, and ambiguity is reported instead of guessed. It cannot edit dependency/JAR content.",
        """{"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string","description":"Exact text to replace in the single path."},"new_text":{"type":"string","description":"Replacement text for the single path; may be empty."},"replace_all":{"type":"boolean","description":"Single path only; default false."},"case_sensitive":{"type":"boolean","description":"Single path only; default true."},"files":{"type":"array","minItems":1,"maxItems":20,"description":"Batch edit requests. Do not combine with path/old_text/new_text.","items":{"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},"new_text":{"type":"string"},"replace_all":{"type":"boolean","description":"Default false."},"case_sensitive":{"type":"boolean","description":"Default true."}},"required":["path","old_text","new_text"]}}},"anyOf":[{"required":["path","old_text","new_text"]},{"required":["files"]}]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val batch = input.fileBatch("path", "files")
        val requests = batch.items.map { item ->
            ReplaceTextRequest(
                item.string("path"),
                item.stringValue("old_text"),
                item.stringValue("new_text"),
                item.booleanOr("replace_all", false),
                item.booleanOr("case_sensitive", true),
            )
        }
        val results = support.replaceFiles(requests)
        return if (batch.isBatch) batchResult("files", results) else results.single()
    }

    companion object { const val NAME = "replace_text_in_file" }
}

internal class FindFilesTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use when you need to locate files by one or more filename, class-name, or glob patterns. Use name for one query or names for up to 20 queries; do not provide both. The match, scope, and per-query limit apply to every name. Set include_libraries=true only for dependency/JAR/JRT inspection. For text/content matches, use search_text instead.",
        """{"type":"object","properties":{"name":{"type":"string","description":"Single filename or class-name pattern."},"names":{"type":"array","minItems":1,"maxItems":20,"description":"Multiple filename or class-name patterns. Do not combine with name.","items":{"type":"string"}},"match":{"type":"string","enum":["exact","contains","glob"],"description":"Default contains; applies to every name."},"include_libraries":{"type":"boolean","description":"Include dependency/JAR/JRT files; default false."},"limit":{"type":"integer","description":"Per-name result limit; default 100, hard limit 1000."}},"anyOf":[{"required":["name"]},{"required":["names"]}]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val names = input.singleOrStringBatch("name", "names")
        val match = when (input.stringOr("match", "contains").lowercase()) {
            "exact" -> NameMatch.EXACT
            "contains" -> NameMatch.CONTAINS
            "glob" -> NameMatch.GLOB
            else -> throw ToolException("match must be exact, contains, or glob")
        }
        val includeLibraries = input.booleanOr("include_libraries", false)
        val limit = input.intOr("limit", 100).coerceIn(1, 1000)
        val results = names.values.map { name ->
            support.findFiles(name, match, includeLibraries, limit).apply {
                addProperty("query", name)
            }
        }
        return if (names.isBatch) batchResult("results", results) else results.single()
    }

    companion object { const val NAME = "find_files" }
}

internal class SearchTextTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use for text or regular-expression matches inside project source, tests, documentation, and configuration files. The default scope is project content and should be used for normal code work. Set include_libraries=true only to inspect indexed dependency source/resource files or JAR text; binary .class files are not bulk-decompiled. Use find_files for filename discovery and read_file for a known file. If there are no results, check the path scope, file_pattern, case_sensitive, and regex settings rather than repeatedly calling the same search. Results contain bounded line context; read the relevant file ranges for more code.",
        """{"type":"object","properties":{"query":{"type":"string"},"regex":{"type":"boolean","description":"Treat query as a regular expression; keep false for literal text search."},"case_sensitive":{"type":"boolean","description":"Default true."},"include_libraries":{"type":"boolean","description":"Search indexed dependency source/resource files and JAR text only when explicitly needed; default false. This is not a bulk search of every binary class."},"file_pattern":{"type":"string","description":"Optional filename glob to narrow the search, such as *.kt, *Test.java, or *.md."},"context_lines":{"type":"integer","description":"Number of bounded context lines before and after each match; default 2, hard limit 20."},"limit":{"type":"integer","description":"Default 100, hard limit 1000."}},"required":["query"]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        return support.searchText(
            input.stringValue("query"),
            input.booleanOr("regex", false),
            input.booleanOr("case_sensitive", true),
            input.booleanOr("include_libraries", false),
            input.optionalString("file_pattern"),
            input.intOr("context_lines", 2).coerceIn(0, 20),
            input.intOr("limit", 100).coerceIn(1, 1000),
        )
    }

    companion object { const val NAME = "search_text" }
}

internal class FormatFileTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use after editing a project source file. You must provide the exact 1-based line ranges containing this task's changes; only those ranges are formatted. Do not omit ranges and do not select the whole file merely to normalize a collaborator's style. It follows the current JetBrains language plugin and project Code Style, and cannot format dependency/JAR files.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative source file path."},"ranges":{"type":"array","minItems":1,"description":"Required non-overlapping 1-based inclusive line ranges for only the code changed in this task. Use the full file range only for a newly created or intentionally fully rewritten file.","items":{"type":"object","properties":{"start":{"type":"integer","minimum":1},"end":{"type":"integer","minimum":1}},"required":["start","end"],"additionalProperties":false}},"timeout_secs":{"type":"integer","description":"Default 30, hard limit 120."}},"required":["path","ranges"],"additionalProperties":false}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val ranges = input.get("ranges")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.map { element ->
                val range = element.obj("line range")
                LineRange(range.int("start"), range.int("end"))
            }
            ?.takeIf { it.isNotEmpty() }
            ?: throw ToolException("ranges must contain at least one line range")
        val timeout = input.intOr("timeout_secs", 30).coerceIn(1, 120)
        return support.format(input.string("path"), ranges, timeout)
    }

    companion object { const val NAME = "format_file" }
}

internal class CompileProjectTool(
    private val support: IdeProjectSupport,
    private val cancel: com.lhstack.tools.agent.model.http.ModelCancel?,
) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use when the current project should be built through JetBrains ProjectTaskManager. It returns the authoritative task status and collects bounded platform BuildEvents (messages, file positions, failures, stdout, and stderr) when the active ProjectTaskRunner publishes them under this build session. Use Bash for build commands that do not run through ProjectTaskManager.",
        """{"type":"object","properties":{"mode":{"type":"string","enum":["build","rebuild"],"description":"Compilation mode; default build."},"timeout_secs":{"type":"integer","description":"Maximum wait time in seconds; default 600, hard limit 3600."}},"required":[]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val mode = input.stringOr("mode", "build").lowercase()
        require(mode == "build" || mode == "rebuild") { "mode must be build or rebuild" }
        val timeoutSecs = input.intOr("timeout_secs", 600).coerceIn(1, 3600)
        saveDocumentsBeforeCompilation()
        val compilation = startCompilation(mode)
        val interruptId = (compilation.promise as? CancellablePromise<*>)?.let { cancellable ->
            cancel?.registerInterrupt(cancellable::cancel)
        }
        val result = try {
            requireNotNull(compilation.promise.blockingGet(timeoutSecs, TimeUnit.SECONDS)) {
                "JetBrains project compilation completed without a result"
            }
        } catch (error: TimeoutException) {
            (compilation.promise as? CancellablePromise<*>)?.cancel()
            throw ToolException("project compilation timed out after $timeoutSecs seconds")
        } catch (error: java.util.concurrent.ExecutionException) {
            val cause = error.cause ?: error
            throw ToolException("project compilation failed to execute: ${cause.message ?: cause}")
        } catch (error: java.util.concurrent.CancellationException) {
            throw ToolException.cancelled()
        } finally {
            interruptId?.let { cancel?.clearInterrupt(it) }
            Disposer.dispose(compilation.collector)
        }
        if (cancel?.isCancelled() == true) throw ToolException.cancelled()
        return buildResult(mode, result, compilation.collector.snapshot())
    }

    private fun saveDocumentsBeforeCompilation() {
        val save = Runnable { FileDocumentManager.getInstance().saveAllDocuments() }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) save.run() else application.invokeAndWait(save)
    }

    private fun startCompilation(mode: String): CompilationExecution {
        val execution = java.util.concurrent.atomic.AtomicReference<CompilationExecution>()
        val start = Runnable {
            val manager = ProjectTaskManager.getInstance(support.project)
            val sessionId = Any()
            val context = ProjectTaskContext(sessionId)
            val collector = ProjectBuildEventCollector.create(support.project, sessionId)
            try {
                val task = manager.createAllModulesBuildTask(mode == "rebuild", support.project)
                execution.set(CompilationExecution(context, collector, manager.run(context, task)))
            } catch (error: Throwable) {
                Disposer.dispose(collector)
                throw error
            }
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) start.run() else application.invokeAndWait(start)
        return requireNotNull(execution.get()) { "JetBrains project compilation did not start" }
    }

    private fun buildResult(
        mode: String,
        result: ProjectTaskManager.Result,
        diagnostics: ProjectBuildEventCollector.Snapshot,
    ) = JsonObject().apply {
        addProperty("project", support.project.name)
        addProperty("mode", mode)
        addProperty("status", when {
            result.isAborted -> "aborted"
            result.hasErrors() -> "failed"
            else -> "completed"
        })
        addProperty("aborted", result.isAborted)
        addProperty("has_errors", result.hasErrors())
        addProperty("success", !result.isAborted && !result.hasErrors())
        addProperty("build_events_available", diagnostics.buildEventsAvailable)
        addProperty("build_output_truncated", diagnostics.outputTruncated)
        addProperty("error_count", diagnostics.errorCount)
        addProperty("warning_count", diagnostics.warningCount)
        add("errors", diagnostics.errors)
        add("warnings", diagnostics.warnings)
        addProperty("stdout", diagnostics.stdout)
        addProperty("stderr", diagnostics.stderr)
        if (!diagnostics.buildEventsAvailable) {
            addProperty(
                "output_note",
                "The active ProjectTaskRunner did not publish BuildEvents under this ProjectTaskContext session id. The ProjectTaskManager status is authoritative, but detailed output is unavailable for this execution channel.",
            )
        } else if (result.hasErrors() && diagnostics.errorCount == 0 && diagnostics.stderr.isBlank()) {
            addProperty(
                "output_note",
                "The IDE build reported errors but did not publish an error MessageEvent, FailureResult, or stderr output for this session.",
            )
        }
    }

    private data class CompilationExecution(
        val context: ProjectTaskContext,
        val collector: ProjectBuildEventCollector,
        val promise: org.jetbrains.concurrency.Promise<ProjectTaskManager.Result>,
    )

    companion object { const val NAME = "compile_project" }
}

internal class GetFileProblemsTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use after editing one or more project source files to inspect IDE syntax, unresolved-reference, type, and inspection problems before or alongside compilation. Use path for one file or paths for up to 20 files; do not provide both. This is file-scoped IDE analysis, not a replacement for project builds, tests, Cargo, Gradle, Maven, npm, or custom commands.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Single project-relative source file path."},"paths":{"type":"array","minItems":1,"maxItems":20,"description":"Multiple project-relative source file paths. Do not combine with path.","items":{"type":"string"}},"errors_only":{"type":"boolean","description":"Return only ERROR severity; default false."}},"anyOf":[{"required":["path"]},{"required":["paths"]}]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val paths = input.singleOrStringBatch("path", "paths")
        val files = paths.values.map(support::resolveProjectFile)
        require(files.distinctBy { it.url }.size == files.size) { "paths must not contain duplicates" }
        DumbService.getInstance(support.project).waitForSmartMode()
        val indicator = EmptyProgressIndicator()
        val errorsOnly = input.booleanOr("errors_only", false)
        val results = ProgressManager.getInstance().runProcess(
            Computable {
                ReadAction.nonBlocking(Callable {
                    files.map { file ->
                        val problems = inspectFile(support, file, errorsOnly)
                        JsonObject().apply {
                            addProperty("path", support.displayPath(file))
                            addProperty("count", problems.size())
                            add("problems", problems)
                        }
                    }
                })
                    .inSmartMode(support.project)
                    .withDocumentsCommitted(support.project)
                    .wrapProgress(indicator)
                    .executeSynchronously()
            },
            indicator,
        )
        return if (paths.isBatch) batchResult("files", results) else results.single()
    }

    companion object { const val NAME = "get_file_problems" }
}

private fun inspectFile(
    support: IdeProjectSupport,
    file: com.intellij.openapi.vfs.VirtualFile,
    errorsOnly: Boolean,
): JsonArray {
    val document = FileDocumentManager.getInstance().getDocument(file)
        ?: throw ToolException("`${support.displayPath(file)}` is not a text file")
    val psiFile = PsiManager.getInstance(support.project).findFile(file)
        ?: throw ToolException("`${support.displayPath(file)}` is not a PSI source file")
    val inspectionManager = InspectionManager.getInstance(support.project)
    val profile = InspectionProfileManager.getInstance(support.project).currentProfile
    val descriptors = profile.getInspectionTools(psiFile)
        .asSequence()
        .filter { wrapper -> profile.isToolEnabled(wrapper.displayKey, psiFile) }
        .mapNotNull { wrapper -> wrapper.tool as? com.intellij.codeInspection.LocalInspectionTool }
        .filter { tool -> tool.isAvailableForFile(psiFile) }
        .flatMap { tool ->
            ProgressManager.checkCanceled()
            tool.processFile(psiFile, inspectionManager).asSequence()
        }
        .toList()
    return inspectionProblems(descriptors, document, errorsOnly)
}

private fun inspectionProblems(
    descriptors: List<ProblemDescriptor>,
    document: com.intellij.openapi.editor.Document,
    errorsOnly: Boolean,
): JsonArray = JsonArray().apply {
    descriptors.forEach { descriptor ->
        val severity = descriptor.highlightType.name
        if (errorsOnly && descriptor.highlightType !in ERROR_HIGHLIGHT_TYPES) return@forEach
        val element = descriptor.psiElement ?: return@forEach
        val elementRange = element.textRange ?: return@forEach
        val relativeRange = descriptor.textRangeInElement
        val absoluteRange = if (relativeRange == null) {
            elementRange
        } else {
            com.intellij.openapi.util.TextRange(
                elementRange.startOffset + relativeRange.startOffset,
                elementRange.startOffset + relativeRange.endOffset,
            )
        }
        val startOffset = absoluteRange.startOffset.coerceIn(0, document.textLength)
        val endOffset = absoluteRange.endOffset.coerceIn(startOffset, document.textLength)
        val line = document.getLineNumber(startOffset)
        add(JsonObject().apply {
            addProperty("severity", severity)
            addProperty("description", descriptor.descriptionTemplate.orEmpty())
            addProperty("line", line + 1)
            addProperty("column", startOffset - document.getLineStartOffset(line) + 1)
            addProperty("start_offset", startOffset)
            addProperty("end_offset", endOffset)
        })
    }
}

private val ERROR_HIGHLIGHT_TYPES = setOf(
    com.intellij.codeInspection.ProblemHighlightType.ERROR,
    com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR,
    com.intellij.codeInspection.ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
)

private data class BatchObjects(val items: List<JsonObject>, val isBatch: Boolean)
private data class BatchStrings(val values: List<String>, val isBatch: Boolean)

private fun JsonObject.fileBatch(singleName: String, batchName: String): BatchObjects {
    val hasSingle = get(singleName)?.let { !it.isJsonNull } == true
    val batch = get(batchName)?.let { element ->
        require(element.isJsonArray) { "$batchName must be an array" }
        element.asJsonArray.map { it.obj("$batchName item") }.also { items ->
            require(items.isNotEmpty()) { "$batchName must not be empty" }
            require(items.size <= MAX_BATCH_ITEMS) { "$batchName supports at most $MAX_BATCH_ITEMS items" }
        }
    }
    require(hasSingle.xor(batch != null)) { "provide exactly one of `$singleName` or `$batchName`" }
    return if (batch != null) BatchObjects(batch, true) else BatchObjects(listOf(this), false)
}

private fun JsonObject.singleOrStringBatch(singleName: String, batchName: String): BatchStrings {
    val single = optionalString(singleName)?.takeIf { it.isNotBlank() }
    val batch = get(batchName)?.let { element ->
        require(element.isJsonArray) { "$batchName must be an array" }
        element.asJsonArray.mapIndexed { index, item ->
            item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.takeIf { it.isNotBlank() }
                ?: throw ToolException("$batchName item ${index + 1} must be a non-blank string")
        }.also { items ->
            require(items.isNotEmpty()) { "$batchName must not be empty" }
            require(items.size <= MAX_BATCH_ITEMS) { "$batchName supports at most $MAX_BATCH_ITEMS items" }
            require(items.distinct().size == items.size) { "$batchName must not contain duplicates" }
        }
    }
    require((single != null).xor(batch != null)) { "provide exactly one of `$singleName` or `$batchName`" }
    return if (batch != null) BatchStrings(batch, true) else BatchStrings(listOf(single!!), false)
}

private fun batchResult(property: String, results: List<JsonObject>) = JsonObject().apply {
    addProperty("count", results.size)
    add(property, JsonArray().apply { results.forEach(::add) })
}

private const val MAX_BATCH_ITEMS = 20

private fun definition(name: String, description: String, schema: String) = ToolDefinition(
    name = name,
    description = description,
    parameters = JsonParser.parseString(schema),
)

private fun JsonElement.obj(name: String = "arguments"): JsonObject =
    takeIf { it.isJsonObject }?.asJsonObject ?: throw ToolException("$name must be a JSON object")

private fun JsonObject.string(name: String): String = optionalString(name)?.takeIf { it.isNotBlank() }
    ?: throw ToolException("missing argument `$name`")

private fun JsonObject.stringValue(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw ToolException("missing string argument `$name`")

private fun JsonObject.optionalString(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.stringOr(name: String, default: String): String = optionalString(name) ?: default
private fun JsonObject.int(name: String): Int = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    ?: throw ToolException("missing integer argument `$name`")
private fun JsonObject.intOr(name: String, default: Int): Int = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: default
private fun JsonObject.booleanOr(name: String, default: Boolean): Boolean = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default