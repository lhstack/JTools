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

internal class ReadProjectFilesTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Read one or more existing text files under the current project root through the IDE. Paths must be project-root-relative. Reads unsaved editor text when available and returns numbered lines, charset, total line count, and truncation state. Use line_ranges to limit large files; if omitted, reading starts at line 1 and is bounded by max_lines.",
        """{"type":"object","properties":{"files":{"type":"array","minItems":1,"maxItems":20,"description":"Required. One to 20 existing project files to read. Duplicate paths are rejected.","items":{"type":"object","properties":{"path":{"type":"string","minLength":1,"description":"Required. Path relative to the current project root. Absolute paths and paths outside the project root are rejected."},"line_ranges":{"type":"array","minItems":1,"description":"Optional. One or more 1-based inclusive line ranges to return, processed in the supplied order. Each range must satisfy end_line >= start_line. When omitted, lines are read from the beginning of the file." ,"items":{"type":"object","properties":{"start_line":{"type":"integer","minimum":1,"description":"Required. First line in this range, using 1-based numbering."},"end_line":{"type":"integer","minimum":1,"description":"Required. Last line in this range, inclusive and not less than start_line."}},"required":["start_line","end_line"],"additionalProperties":false}},"max_lines":{"type":"integer","minimum":1,"maximum":5000,"default":1000,"description":"Optional. Maximum total number of lines returned for this file across all requested ranges. Default 1000; allowed range 1-5000."}},"required":["path"],"additionalProperties":false}}},"required":["files"],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val requests = args.obj().objectArray("files").map { input ->
            val ranges = input.getAsJsonArray("line_ranges")?.map { element ->
                val range = element.obj("line range")
                LineRange(range.int("start_line"), range.int("end_line"))
            }.orEmpty()
            ReadFileRequest(input.string("path"), ranges, input.intOr("max_lines", 1000).coerceIn(1, 5000))
        }
        return batchResult("files", support.readFiles(requests))
    }

    companion object { const val NAME = "read_project_files" }
}

internal class WriteProjectFilesTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Create new text files or completely replace existing text files under the current project root through one IDE write command. All paths and overwrite permissions are validated before any file is changed. Parent directories are created when needed. Use replace_project_text instead when only a focused part of an existing file should change.",
        """{"type":"object","properties":{"files":{"type":"array","minItems":1,"maxItems":20,"description":"Required. One to 20 complete file writes. Duplicate paths are rejected and the batch is validated before writing.","items":{"type":"object","properties":{"path":{"type":"string","minLength":1,"description":"Required. Destination path relative to the current project root. Absolute paths and paths outside the project root are rejected."},"content":{"type":"string","description":"Required. Complete text content for the destination file. May be an empty string to create or replace with an empty file."},"overwrite":{"type":"boolean","default":false,"description":"Optional. Set true to replace an existing file. Default false; when false, an existing destination causes the entire call to fail before writing."}},"required":["path","content"],"additionalProperties":false}}},"required":["files"],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val requests = args.obj().objectArray("files").map { input ->
            WriteFileRequest(
                input.string("path"),
                input.stringValue("content"),
                input.booleanOr("overwrite", false),
            )
        }
        return batchResult("files", support.writeFiles(requests))
    }

    companion object { const val NAME = "write_project_files" }
}

internal class ReplaceProjectTextTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Apply exact text replacements to existing text files under the current project root. The complete batch is validated before one IDE write command changes any file. By default old_text must occur exactly once; provide more surrounding text for a unique match or set replace_all=true. At most one edit per file is accepted in a call.",
        """{"type":"object","properties":{"edits":{"type":"array","minItems":1,"maxItems":20,"description":"Required. One to 20 exact text edits. Each path may appear only once, and all edits are validated before writing.","items":{"type":"object","properties":{"path":{"type":"string","minLength":1,"description":"Required. Existing text-file path relative to the current project root."},"old_text":{"type":"string","minLength":1,"description":"Required. Non-empty exact text to locate. When case_sensitive is false, matching ignores letter case."},"new_text":{"type":"string","description":"Required. Text that replaces old_text. May be an empty string to delete the matched text."},"replace_all":{"type":"boolean","default":false,"description":"Optional. Default false requires exactly one match. Set true to replace every match in the file."},"case_sensitive":{"type":"boolean","default":true,"description":"Optional. Whether old_text matching is case-sensitive. Default true."}},"required":["path","old_text","new_text"],"additionalProperties":false}}},"required":["edits"],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val requests = args.obj().objectArray("edits").map { input ->
            ReplaceTextRequest(
                input.string("path"),
                input.stringValue("old_text"),
                input.stringValue("new_text"),
                input.booleanOr("replace_all", false),
                input.booleanOr("case_sensitive", true),
            )
        }
        return batchResult("files", support.replaceFiles(requests))
    }

    companion object { const val NAME = "replace_project_text" }
}

internal class FindProjectFilesTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Find files under the current project root by file name, not by file contents or full path. Runs one result set per query using a shared match mode and returns project-relative paths, result counts, and truncation flags. Use search_project_text when the target is text inside files.",
        """{"type":"object","properties":{"queries":{"type":"array","minItems":1,"maxItems":20,"description":"Required. One to 20 unique, non-blank file-name values or patterns. Queries match file names only, not directory paths.","items":{"type":"string","minLength":1,"description":"Required. A file name, file-name fragment, or glob pattern interpreted according to match_mode."}},"match_mode":{"type":"string","enum":["exact","contains","glob"],"default":"contains","description":"Optional. How every query is matched: exact requires the complete file name, contains searches for the query inside the file name, and glob uses file-name glob syntax such as *.json. Default contains."},"max_results_per_query":{"type":"integer","minimum":1,"maximum":1000,"default":100,"description":"Optional. Maximum files returned independently for each query. Default 100; allowed range 1-1000. A truncated flag reports when this bound is reached."}},"required":["queries"],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val queries = input.stringArray("queries")
        val match = when (input.stringOr("match_mode", "contains").lowercase()) {
            "exact" -> NameMatch.EXACT
            "contains" -> NameMatch.CONTAINS
            "glob" -> NameMatch.GLOB
            else -> throw ToolException("match_mode must be exact, contains, or glob")
        }
        val limit = input.intOr("max_results_per_query", 100).coerceIn(1, 1000)
        val results = queries.map { query ->
            support.findFiles(query, match, limit).apply { addProperty("query", query) }
        }
        return batchResult("results", results)
    }

    companion object { const val NAME = "find_project_files" }
}

internal class SearchProjectTextTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Search text contents of readable files under the current project root. Supports literal or regular-expression matching, optional case-insensitive matching, optional filtering by file-name glob, bounded surrounding lines, and a global result limit. Returns project-relative file paths and 1-based line numbers. Use find_project_files when searching by file name only.",
        """{"type":"object","properties":{"text":{"type":"string","minLength":1,"description":"Required. Non-empty literal text to find, or a regular expression when use_regex is true."},"use_regex":{"type":"boolean","default":false,"description":"Optional. Interpret text as a regular expression when true; otherwise search it literally. Default false. Invalid regular expressions cause the call to fail."},"case_sensitive":{"type":"boolean","default":true,"description":"Optional. Whether matching distinguishes letter case. Default true."},"file_name_glob":{"type":"string","minLength":1,"description":"Optional. File-name-only glob such as *.md used to restrict scanned files. It does not match the project-relative directory path."},"context_lines":{"type":"integer","minimum":0,"maximum":20,"default":2,"description":"Optional. Number of surrounding lines included before and after each matching line. Default 2; allowed range 0-20."},"max_results":{"type":"integer","minimum":1,"maximum":1000,"default":100,"description":"Optional. Maximum matching lines returned across the complete project search. Default 100; allowed range 1-1000. A truncated flag reports when this bound is reached."}},"required":["text"],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        return support.searchText(
            input.stringValue("text"),
            input.booleanOr("use_regex", false),
            input.booleanOr("case_sensitive", true),
            input.optionalString("file_name_glob"),
            input.intOr("context_lines", 2).coerceIn(0, 20),
            input.intOr("max_results", 100).coerceIn(1, 1000),
        )
    }

    companion object { const val NAME = "search_project_text" }
}

internal class FormatProjectFilesTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Format only explicit line ranges in one or more files under the current project root, using the formatter supplied by the active IDE and installed file-type support. Files must be recognized as text files with formatter support. Ranges use 1-based inclusive line numbers, must be ordered and non-overlapping, and the formatted document is saved.",
        """{"type":"object","properties":{"files":{"type":"array","minItems":1,"maxItems":20,"description":"Required. One to 20 project files with explicit ranges to format.","items":{"type":"object","properties":{"path":{"type":"string","minLength":1,"description":"Required. Existing file path relative to the current project root. The active IDE must provide file-type and formatter support."},"line_ranges":{"type":"array","minItems":1,"description":"Required. Ordered, non-overlapping 1-based inclusive ranges. Every end_line must be greater than or equal to start_line and must not exceed the file line count.","items":{"type":"object","properties":{"start_line":{"type":"integer","minimum":1,"description":"Required. First line to format, using 1-based numbering."},"end_line":{"type":"integer","minimum":1,"description":"Required. Last line to format, inclusive and not less than start_line."}},"required":["start_line","end_line"],"additionalProperties":false}},"timeout_secs":{"type":"integer","minimum":1,"maximum":120,"default":30,"description":"Optional. Maximum seconds to wait for formatting this file. Default 30; allowed range 1-120."}},"required":["path","line_ranges"],"additionalProperties":false}}},"required":["files"],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val results = args.obj().objectArray("files").map { input ->
            val ranges = input.objectArray("line_ranges").map { range ->
                LineRange(range.int("start_line"), range.int("end_line"))
            }
            support.format(
                input.string("path"),
                ranges,
                input.intOr("timeout_secs", 30).coerceIn(1, 120),
            )
        }
        return batchResult("files", results)
    }

    companion object { const val NAME = "format_project_files" }
}

internal class BuildProjectTool(
    private val support: IdeProjectSupport,
    private val cancel: com.lhstack.tools.agent.model.http.ModelCancel?,
) : ToolDyn {
    override val executionTimeoutSeconds: Long = 660L
    override fun definition(prompt: String) = definition(
        NAME,
        "Save all open documents, then build the complete current project through JetBrains ProjectTaskManager and the task runner supplied by the active IDE. This tool does not choose or invoke a language-specific command. Returns completed, failed, or aborted status plus bounded diagnostics and output only when the active runner publishes them.",
        """{"type":"object","properties":{"mode":{"type":"string","enum":["build","rebuild"],"default":"build","description":"Optional. build requests the IDE runner's normal project build; rebuild requests its full rebuild behavior. Default build."},"timeout_secs":{"type":"integer","minimum":1,"maximum":3600,"default":600,"description":"Optional. Maximum seconds to wait for the project task result. Default 600; allowed range 1-3600. Timeout cancels the task and fails the tool call."}},"required":[],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val mode = input.stringOr("mode", "build").lowercase()
        require(mode == "build" || mode == "rebuild") { "mode must be build or rebuild" }
        val timeoutSecs = input.intOr("timeout_secs", 600).coerceIn(1, 3600)
        saveDocumentsBeforeBuild()
        val execution = startBuild(mode)
        val interruptId = (execution.promise as? CancellablePromise<*>)?.let { cancellable ->
            cancel?.registerInterrupt(cancellable::cancel)
        }
        val result = try {
            requireNotNull(execution.promise.blockingGet(timeoutSecs, TimeUnit.SECONDS)) {
                "JetBrains project build completed without a result"
            }
        } catch (error: TimeoutException) {
            (execution.promise as? CancellablePromise<*>)?.cancel()
            throw ToolException("project build timed out after $timeoutSecs seconds")
        } catch (error: java.util.concurrent.ExecutionException) {
            val cause = error.cause ?: error
            throw ToolException("project build failed to execute: ${cause.message ?: cause}")
        } catch (error: java.util.concurrent.CancellationException) {
            throw ToolException.cancelled()
        } finally {
            interruptId?.let { cancel?.clearInterrupt(it) }
            Disposer.dispose(execution.collector)
        }
        if (cancel?.isCancelled() == true) throw ToolException.cancelled()
        return buildResult(mode, result, execution.collector.snapshot())
    }

    private fun saveDocumentsBeforeBuild() {
        val save = Runnable { FileDocumentManager.getInstance().saveAllDocuments() }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) save.run() else application.invokeAndWait(save)
    }

    private fun startBuild(mode: String): BuildExecution {
        val execution = java.util.concurrent.atomic.AtomicReference<BuildExecution>()
        val start = Runnable {
            val manager = ProjectTaskManager.getInstance(support.project)
            val sessionId = Any()
            val context = ProjectTaskContext(sessionId)
            val collector = ProjectBuildEventCollector.create(support.project, sessionId)
            try {
                val task = manager.createAllModulesBuildTask(mode == "rebuild", support.project)
                execution.set(BuildExecution(collector, manager.run(context, task)))
            } catch (error: Throwable) {
                Disposer.dispose(collector)
                throw error
            }
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) start.run() else application.invokeAndWait(start)
        return requireNotNull(execution.get()) { "JetBrains project build did not start" }
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
        addProperty("diagnostics_available", diagnostics.structuredDiagnosticsAvailable)
        addProperty("output_truncated", diagnostics.outputTruncated)
        addProperty("error_count", diagnostics.errorCount)
        addProperty("warning_count", diagnostics.warningCount)
        add("errors", diagnostics.errors)
        add("warnings", diagnostics.warnings)
        addProperty("stdout", diagnostics.stdout)
        addProperty("stderr", diagnostics.stderr)
        if (!diagnostics.buildEventsAvailable && !diagnostics.structuredDiagnosticsAvailable) {
            addProperty("output_note", "The active IDE task runner reported only the build status; detailed diagnostics and output were not published for this execution.")
        } else if (result.hasErrors() && diagnostics.errorCount == 0 && diagnostics.stderr.isBlank()) {
            addProperty("output_note", "The active IDE task runner reported errors without publishing detailed error diagnostics or stderr for this execution.")
        }
    }

    private data class BuildExecution(
        val collector: ProjectBuildEventCollector,
        val promise: org.jetbrains.concurrency.Promise<ProjectTaskManager.Result>,
    )

    companion object { const val NAME = "build_project" }
}

internal class InspectProjectFilesTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Run enabled local inspections from the current IDE inspection profile for one or more files under the current project root. Each file must be recognized by installed file-type support. Returns inspection descriptions, severity, 1-based line and column, and absolute text offsets. Results cover local inspections published by the active IDE; they are not a replacement for every editor highlighter or external build diagnostic.",
        """{"type":"object","properties":{"paths":{"type":"array","minItems":1,"maxItems":20,"description":"Required. One to 20 unique existing file paths relative to the current project root.","items":{"type":"string","minLength":1,"description":"Required. Project-root-relative path to a text file recognized by an installed IDE file-type plugin."}},"errors_only":{"type":"boolean","default":false,"description":"Optional. When true, return only findings whose IDE highlight type is error-level. Default false returns every finding produced by enabled local inspections."}},"required":["paths"],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val paths = input.stringArray("paths")
        val files = paths.map(support::resolveProjectFile)
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
        return batchResult("files", results)
    }

    companion object { const val NAME = "inspect_project_files" }
}


private fun inspectFile(
    support: IdeProjectSupport,
    file: com.intellij.openapi.vfs.VirtualFile,
    errorsOnly: Boolean,
): JsonArray {
    val document = FileDocumentManager.getInstance().getDocument(file)
        ?: throw ToolException("`${support.displayPath(file)}` is not a text file")
    val psiFile = PsiManager.getInstance(support.project).findFile(file)
        ?: throw ToolException("`${support.displayPath(file)}` is not supported by an installed IDE file-type plugin")
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

private fun JsonObject.objectArray(name: String): List<JsonObject> {
    val element = get(name) ?: throw ToolException("missing argument `$name`")
    require(element.isJsonArray) { "$name must be an array" }
    return element.asJsonArray.map { it.obj("$name item") }.also { values ->
        require(values.isNotEmpty()) { "$name must not be empty" }
        require(values.size <= MAX_BATCH_ITEMS) { "$name supports at most $MAX_BATCH_ITEMS items" }
    }
}

private fun JsonObject.stringArray(name: String): List<String> {
    val element = get(name) ?: throw ToolException("missing argument `$name`")
    require(element.isJsonArray) { "$name must be an array" }
    return element.asJsonArray.mapIndexed { index, item ->
        item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf { it.isNotBlank() }
            ?: throw ToolException("$name item ${index + 1} must be a non-blank string")
    }.also { values ->
        require(values.isNotEmpty()) { "$name must not be empty" }
        require(values.size <= MAX_BATCH_ITEMS) { "$name supports at most $MAX_BATCH_ITEMS items" }
        require(values.distinct().size == values.size) { "$name must not contain duplicates" }
    }
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