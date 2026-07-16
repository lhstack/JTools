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
        "Read one or more text files from the current project through the IDE. Use focused 1-based line ranges for large files. Unsaved editor content is returned when available.",
        """{"type":"object","properties":{"files":{"type":"array","minItems":1,"maxItems":20,"description":"Project files to read.","items":{"type":"object","properties":{"path":{"type":"string","description":"Project-relative file path."},"line_ranges":{"type":"array","description":"Optional 1-based inclusive line ranges.","items":{"type":"object","properties":{"start_line":{"type":"integer","minimum":1},"end_line":{"type":"integer","minimum":1}},"required":["start_line","end_line"],"additionalProperties":false}},"max_lines":{"type":"integer","minimum":1,"maximum":5000,"description":"Maximum lines returned for this file; default 1000."}},"required":["path"],"additionalProperties":false}}},"required":["files"],"additionalProperties":false}""",
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
        "Create or completely replace one or more text files in the current project through the IDE. Every request is validated before the write command starts. Use replace_project_text for focused edits to existing files.",
        """{"type":"object","properties":{"files":{"type":"array","minItems":1,"maxItems":20,"description":"Complete project files to create or replace.","items":{"type":"object","properties":{"path":{"type":"string","description":"Project-relative file path."},"content":{"type":"string","description":"Complete file content."},"overwrite":{"type":"boolean","description":"Allow replacing an existing file; default false."}},"required":["path","content"],"additionalProperties":false}}},"required":["files"],"additionalProperties":false}""",
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
        "Apply exact text replacements to existing text files in the current project. All edits are validated before any file changes. A non-unique match is rejected unless replace_all is true.",
        """{"type":"object","properties":{"edits":{"type":"array","minItems":1,"maxItems":20,"description":"Exact text replacements; at most one edit per file in a call.","items":{"type":"object","properties":{"path":{"type":"string","description":"Project-relative file path."},"old_text":{"type":"string","minLength":1,"description":"Exact text to find."},"new_text":{"type":"string","description":"Replacement text; may be empty."},"replace_all":{"type":"boolean","description":"Replace every match; default false."},"case_sensitive":{"type":"boolean","description":"Match case; default true."}},"required":["path","old_text","new_text"],"additionalProperties":false}}},"required":["edits"],"additionalProperties":false}""",
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
        "Find files in the current project by file-name queries. Each query uses the same exact, contains, or glob match mode. Use search_project_text to search file contents.",
        """{"type":"object","properties":{"queries":{"type":"array","minItems":1,"maxItems":20,"description":"File-name values or patterns.","items":{"type":"string","minLength":1}},"match_mode":{"type":"string","enum":["exact","contains","glob"],"description":"File-name match mode; default contains."},"max_results_per_query":{"type":"integer","minimum":1,"maximum":1000,"description":"Maximum files returned for each query; default 100."}},"required":["queries"],"additionalProperties":false}""",
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
        "Search text file contents in the current project. Supports literal or regular-expression matching, an optional file-name glob, and bounded line context around each result.",
        """{"type":"object","properties":{"text":{"type":"string","minLength":1,"description":"Literal text or regular expression to search for."},"use_regex":{"type":"boolean","description":"Interpret text as a regular expression; default false."},"case_sensitive":{"type":"boolean","description":"Match case; default true."},"file_name_glob":{"type":"string","description":"Optional file-name glob used to narrow the search."},"context_lines":{"type":"integer","minimum":0,"maximum":20,"description":"Context lines before and after each match; default 2."},"max_results":{"type":"integer","minimum":1,"maximum":1000,"description":"Maximum matches returned; default 100."}},"required":["text"],"additionalProperties":false}""",
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
        "Format explicit 1-based line ranges in one or more current-project files using the formatter supplied by the active IDE and installed file-type support. Only the requested ranges are formatted.",
        """{"type":"object","properties":{"files":{"type":"array","minItems":1,"maxItems":20,"description":"Project files and line ranges to format.","items":{"type":"object","properties":{"path":{"type":"string","description":"Project-relative file path."},"line_ranges":{"type":"array","minItems":1,"description":"Non-overlapping 1-based inclusive line ranges.","items":{"type":"object","properties":{"start_line":{"type":"integer","minimum":1},"end_line":{"type":"integer","minimum":1}},"required":["start_line","end_line"],"additionalProperties":false}},"timeout_secs":{"type":"integer","minimum":1,"maximum":120,"description":"Maximum wait for this file; default 30 seconds."}},"required":["path","line_ranges"],"additionalProperties":false}}},"required":["files"],"additionalProperties":false}""",
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
    override fun definition(prompt: String) = definition(
        NAME,
        "Build the current project through JetBrains ProjectTaskManager and the task runner supplied by the active IDE. Returns the task status plus bounded diagnostics and output when the runner publishes them for this build.",
        """{"type":"object","properties":{"mode":{"type":"string","enum":["build","rebuild"],"description":"Build mode; default build."},"timeout_secs":{"type":"integer","minimum":1,"maximum":3600,"description":"Maximum wait; default 600 seconds."}},"additionalProperties":false}""",
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
        "Run the enabled local inspections from the current IDE inspection profile for one or more current-project files. Available inspections depend on the active IDE and installed file-type support.",
        """{"type":"object","properties":{"paths":{"type":"array","minItems":1,"maxItems":20,"description":"Project-relative file paths to inspect.","items":{"type":"string","minLength":1}},"errors_only":{"type":"boolean","description":"Return only error-level findings; default false."}},"required":["paths"],"additionalProperties":false}""",
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