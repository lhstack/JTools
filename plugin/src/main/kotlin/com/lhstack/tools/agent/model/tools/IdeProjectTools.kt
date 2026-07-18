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
        "读取项目文本文件，支持指定行范围和未保存内容。",
        """{"type":"object","properties":{"files":{"type":"array","minItems":1,"maxItems":20,"description":"必填。 1到20个待读取文件，路径不能重复。","items":{"type":"object","properties":{"path":{"type":"string","minLength":1,"description":"必填。 项目根目录相对路径。"},"line_ranges":{"type":"array","minItems":1,"description":"可选。 一个或多个从1开始的闭区间；省略时从文件开头读取。" ,"items":{"type":"object","properties":{"start_line":{"type":"integer","minimum":1,"description":"必填。 起始行，从1开始。"},"end_line":{"type":"integer","minimum":1,"description":"必填。 结束行，包含该行且不小于起始行。"}},"required":["start_line","end_line"],"additionalProperties":false}},"max_lines":{"type":"integer","minimum":1,"maximum":5000,"default":1000,"description":"可选。 最多返回行数，默认1000，范围1到5000。"}},"required":["path"],"additionalProperties":false}}},"required":["files"],"additionalProperties":false}""",
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
        "创建或完整覆盖项目文本文件。局部修改请使用 replace_project_text。",
        """{"type":"object","properties":{"files":{"type":"array","minItems":1,"maxItems":20,"description":"必填。 1到20个完整文件写入，路径不能重复。","items":{"type":"object","properties":{"path":{"type":"string","minLength":1,"description":"必填。 项目根目录相对目标路径。"},"content":{"type":"string","description":"必填。 文件完整内容，可为空字符串。"},"overwrite":{"type":"boolean","default":false,"description":"可选。 是否覆盖已有文件，默认false。"}},"required":["path","content"],"additionalProperties":false}}},"required":["files"],"additionalProperties":false}""",
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
        "精确替换项目文件中的文本；默认要求旧文本仅出现一次。",
        """{"type":"object","properties":{"edits":{"type":"array","minItems":1,"maxItems":20,"description":"必填。 1到20个精确文本修改，每个路径只能出现一次。","items":{"type":"object","properties":{"path":{"type":"string","minLength":1,"description":"必填。 Existing text-file path relative to the current project root."},"old_text":{"type":"string","minLength":1,"description":"必填。 Non-empty exact text to locate. When case_sensitive is false, matching ignores letter case."},"new_text":{"type":"string","description":"必填。 Text that replaces old_text. May be an empty string to delete the matched text."},"replace_all":{"type":"boolean","default":false,"description":"可选。 Default false requires exactly one match. Set true to replace every match in the file."},"case_sensitive":{"type":"boolean","default":true,"description":"可选。 Whether old_text matching is case-sensitive. Default true."}},"required":["path","old_text","new_text"],"additionalProperties":false}}},"required":["edits"],"additionalProperties":false}""",
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
        "按文件名或路径模糊查找文件。默认只查当前项目；include_global=true 时包含依赖、SDK 和外部文件。",
        """{"type":"object","properties":{"queries":{"type":"array","minItems":1,"maxItems":20,"description":"必填。1到20个文件名或相对路径模糊查询。","items":{"type":"string","minLength":1,"description":"必填。文件名或路径查询。"}},"max_results_per_query":{"type":"integer","minimum":1,"maximum":1000,"default":100,"description":"可选。每个查询最多返回数量，默认100，范围1到1000。"},"include_global":{"type":"boolean","default":false,"description":"可选。是否包含依赖、SDK和外部文件，默认false。"}},"required":["queries"],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val queries = input.stringArray("queries")
        val limit = input.intOr("max_results_per_query", 100).coerceIn(1, 1000)
        val includeGlobal = input.booleanOr("include_global", false)
        val results = queries.map { query ->
            support.findFiles(query, limit, includeGlobal).apply { addProperty("query", query) }
        }
        return batchResult("results", results)
    }

    companion object { const val NAME = "find_project_files" }
}

internal class FindProjectClassesTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "按名称模糊查找类或当前语言插件支持的类型。默认只查当前项目；include_global=true 时包含依赖和 SDK。",
        """{"type":"object","properties":{"queries":{"type":"array","minItems":1,"maxItems":20,"description":"必填。1到20个类名或类型名模糊查询。","items":{"type":"string","minLength":1,"description":"必填。类名、类型名或限定名。"}},"max_results_per_query":{"type":"integer","minimum":1,"maximum":1000,"default":100,"description":"可选。每个查询最多返回数量，默认100，范围1到1000。"},"include_global":{"type":"boolean","default":false,"description":"可选。是否包含依赖和SDK中的类型，默认false。"}},"required":["queries"],"additionalProperties":false}""",
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val queries = input.stringArray("queries")
        val limit = input.intOr("max_results_per_query", 100).coerceIn(1, 1000)
        val includeGlobal = input.booleanOr("include_global", false)
        val results = queries.map { query ->
            support.findClasses(query, limit, includeGlobal).apply { addProperty("query", query) }
        }
        return batchResult("results", results)
    }

    companion object { const val NAME = "find_project_classes" }
}

internal class SearchProjectTextTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "搜索项目文件内容，支持正则、大小写、文件名过滤和上下文行。只查文件名请使用 find_project_files。",
        """{"type":"object","properties":{"text":{"type":"string","minLength":1,"description":"必填。搜索文本；use_regex=true时为正则。"},"use_regex":{"type":"boolean","default":false,"description":"可选。是否使用正则，默认false。"},"case_sensitive":{"type":"boolean","default":true,"description":"可选。是否区分大小写，默认true。"},"file_name_glob":{"type":"string","minLength":1,"description":"可选。文件名过滤，如*.md。"},"context_lines":{"type":"integer","minimum":0,"maximum":20,"default":2,"description":"可选。匹配行前后上下文行数，默认2，范围0到20。"},"max_results":{"type":"integer","minimum":1,"maximum":1000,"default":100,"description":"可选。最多返回数量，默认100，范围1到1000。"}},"required":["text"],"additionalProperties":false}""",
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
        "使用 IDE 格式化项目文件的指定行范围并保存。",
        """{"type":"object","properties":{"files":{"type":"array","minItems":1,"maxItems":20,"description":"必填。 1到20个需要格式化的项目文件。","items":{"type":"object","properties":{"path":{"type":"string","minLength":1,"description":"必填。 项目根目录相对文件路径。"},"line_ranges":{"type":"array","minItems":1,"description":"必填。 有序且不重叠的闭区间，行号从1开始。","items":{"type":"object","properties":{"start_line":{"type":"integer","minimum":1,"description":"必填。 格式化起始行，从1开始。"},"end_line":{"type":"integer","minimum":1,"description":"必填。 格式化结束行，包含该行。"}},"required":["start_line","end_line"],"additionalProperties":false}},"timeout_secs":{"type":"integer","minimum":1,"maximum":120,"default":30,"description":"可选。 格式化超时秒数，默认30，范围1到120。"}},"required":["path","line_ranges"],"additionalProperties":false}}},"required":["files"],"additionalProperties":false}""",
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
        "使用 IDE 构建或重新构建当前项目，并返回状态和诊断。",
        """{"type":"object","properties":{"mode":{"type":"string","enum":["build","rebuild"],"default":"build","description":"可选。 构建模式，默认build；rebuild表示重新构建。"},"timeout_secs":{"type":"integer","minimum":1,"maximum":3600,"default":600,"description":"可选。 构建超时秒数，默认600，范围1到3600。"}},"required":[],"additionalProperties":false}""",
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
            addProperty("output_note", "当前 IDE 构建执行器只返回了构建状态，没有发布诊断或输出。")
        } else if ((result.hasErrors() || result.isAborted) && diagnostics.errorCount == 0 && diagnostics.stdout.isBlank() && diagnostics.stderr.isBlank()) {
            addProperty("output_note", "当前 IDE 构建执行器报告构建失败，但没有发布错误诊断或 stderr。")
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
        "使用当前 IDE 检查配置检查指定项目文件，并返回问题位置和级别。",
        """{"type":"object","properties":{"paths":{"type":"array","minItems":1,"maxItems":20,"description":"必填。 1到20个不重复的项目文件路径。","items":{"type":"string","minLength":1,"description":"必填。 项目根目录相对文件路径。"}},"errors_only":{"type":"boolean","default":false,"description":"可选。 是否只返回错误级问题，默认false。"}},"required":["paths"],"additionalProperties":false}""",
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