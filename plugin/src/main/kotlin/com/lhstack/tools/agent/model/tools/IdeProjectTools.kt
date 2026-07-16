package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.actions.ReformatCodeProcessor
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
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.PsiManager
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.jetbrains.concurrency.CancellablePromise

internal class ReadFileTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use when you already know the target file path and need its source, documentation, dependency source, JAR resource, JRT source, or on-demand decompiled class text. Prefer this after find_files or search_text; do not use it to discover unknown filenames. Project files are read from the current IDE Document when available, so unsaved editor changes are included. For large files, request only the needed 1-based lines ranges. JAR/classpath content is read only when the path explicitly returned by find_files is provided.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative path, or a jar://, jrt://, or file:// URL returned by find_files."},"lines":{"type":"array","description":"Optional 1-based line ranges. Use this for focused reads instead of loading a large file.","items":{"type":"object","properties":{"start":{"type":"integer"},"end":{"type":"integer"}},"required":["start","end"]}},"max_lines":{"type":"integer","description":"Maximum returned lines, default 1000, hard limit 5000; the response is still bounded for UI safety."}},"required":["path"]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val ranges = input.getAsJsonArray("lines")?.map { element ->
            val range = element.obj("line range")
            LineRange(range.int("start"), range.int("end"))
        }.orEmpty()
        return support.read(input.string("path"), ranges, input.intOr("max_lines", 1000).coerceIn(1, 5000))
    }

    companion object { const val NAME = "read_file" }
}

internal class WriteFileTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use for a new project file or an intentional complete replacement of an existing file. Missing parent directories are created through JetBrains VFS/Document and the IDE controls encoding. For a small change to an existing file, prefer replace_text_in_file; overwrite is false unless explicitly enabled.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative project text-file path; this tool is not for writing dependency/JAR files."},"content":{"type":"string","description":"Complete replacement content; use only when creating or intentionally rewriting the whole file."},"overwrite":{"type":"boolean","description":"Allow replacing an existing file; default false."}},"required":["path","content"]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        return support.write(input.string("path"), input.stringValue("content"), input.booleanOr("overwrite", false))
    }

    companion object { const val NAME = "write_file" }
}

internal class ReplaceTextInFileTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use for a focused edit to an existing project file. It uses the current IDE Document and WriteCommandAction. Include enough surrounding context for old_text to be unique; ambiguity is reported instead of guessing. Prefer this over write_file for local code changes. It cannot edit dependency/JAR content.",
        """{"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string","description":"Exact text to replace; include surrounding context to make it unique."},"new_text":{"type":"string","description":"Replacement text; may be empty."},"replace_all":{"type":"boolean","description":"Replace all matches; default false."},"case_sensitive":{"type":"boolean","description":"Default true."}},"required":["path","old_text","new_text"]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        return support.replace(
            input.string("path"),
            input.stringValue("old_text"),
            input.stringValue("new_text"),
            input.booleanOr("replace_all", false),
            input.booleanOr("case_sensitive", true),
        )
    }

    companion object { const val NAME = "replace_text_in_file" }
}

internal class FindFilesTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use when you need to locate a file by filename, class name, or path-independent name. The default scope is the current project only. Set include_libraries=true only when inspecting dependency source/JAR entries or JDK/JRT content; this may return many library matches and is not a content search. Pass returned jar:// or jrt:// paths to read_file. For text/content matches, use search_text instead.",
        """{"type":"object","properties":{"name":{"type":"string","description":"Filename or class-name pattern; use match=contains for normal discovery, exact for a known filename, or glob for a filename pattern."},"match":{"type":"string","enum":["exact","contains","glob"],"description":"Default contains."},"include_libraries":{"type":"boolean","description":"Include indexed dependency sources, JAR entries, or JRT entries only when library inspection is required; default false to avoid noisy results."},"limit":{"type":"integer","description":"Default 100, hard limit 1000."}},"required":["name"]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val match = when (input.stringOr("match", "contains").lowercase()) {
            "exact" -> NameMatch.EXACT
            "contains" -> NameMatch.CONTAINS
            "glob" -> NameMatch.GLOB
            else -> throw ToolException("match must be exact, contains, or glob")
        }
        return support.findFiles(input.string("name"), match, input.booleanOr("include_libraries", false), input.intOr("limit", 100).coerceIn(1, 1000))
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
        "Use after editing a project source file when formatting should follow the current JetBrains language plugin and project Code Style. It is not a search or a compiler, and should not be used on dependency/JAR files.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative source file path; format the smallest relevant file after modifications."},"timeout_secs":{"type":"integer","description":"Default 30, hard limit 120."}},"required":["path"]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val file = support.resolveProjectFile(input.string("path"))
        val psiFile = ApplicationManager.getApplication().runReadAction(
            Computable { PsiManager.getInstance(support.project).findFile(file) }
        ) ?: throw ToolException("`${support.displayPath(file)}` is not a PSI source file")
        val done = CountDownLatch(1)
        val processor = ApplicationManager.getApplication().runReadAction(
            Computable { ReformatCodeProcessor(psiFile, false).apply { setPostRunnable(done::countDown) } }
        )
        ApplicationManager.getApplication().invokeLater(processor::run)
        val timeout = input.intOr("timeout_secs", 30).coerceIn(1, 120).toLong()
        require(done.await(timeout, TimeUnit.SECONDS)) { "formatting `${support.displayPath(file)}` timed out" }
        support.saveDocument(file)
        return JsonObject().apply {
            addProperty("path", support.displayPath(file))
            addProperty("formatted", true)
        }
    }

    companion object { const val NAME = "format_file" }
}

internal class CompileProjectTool(
    private val support: IdeProjectSupport,
    private val cancel: com.lhstack.tools.agent.model.http.ModelCancel?,
) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use to verify the current IDE project after code changes when a JetBrains project build is appropriate. It saves open documents and uses the IDE project model; use Bash instead for Gradle/Maven/npm tasks, custom build commands, tests, Git, or scripts.",
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
            ProjectCompilationDiagnostics.finish(compilation.context)
        }
        if (cancel?.isCancelled() == true) throw ToolException.cancelled()
        val diagnostics = ProjectCompilationDiagnostics.collect(compilation.context)
        return buildResult(mode, result, diagnostics)
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
            val context = ProjectTaskContext(Any())
            ProjectCompilationDiagnostics.prepare(context)
            val task = manager.createAllModulesBuildTask(mode == "rebuild", support.project)
            execution.set(CompilationExecution(context, manager.run(context, task)))
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) start.run() else application.invokeAndWait(start)
        return requireNotNull(execution.get()) { "JetBrains project compilation did not start" }
    }

    private fun buildResult(
        mode: String,
        result: ProjectTaskManager.Result,
        diagnostics: ProjectCompilationDiagnostics.Snapshot,
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
        addProperty("error_count", diagnostics.errorCount)
        addProperty("warning_count", diagnostics.warningCount)
        add("errors", diagnostics.errors)
        add("warnings", diagnostics.warnings)
        if (result.hasErrors() && diagnostics.errorCount == 0) {
            addProperty(
                "diagnostics_note",
                "The active JetBrains build runner reported errors but did not publish structured compiler diagnostics for this build session. Open the IDE Build tool window for runner output.",
            )
        }
    }

    private data class CompilationExecution(
        val context: ProjectTaskContext,
        val promise: org.jetbrains.concurrency.Promise<ProjectTaskManager.Result>,
    )

    companion object { const val NAME = "compile_project" }
}

internal class GetFileProblemsTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Use after editing a project source file to inspect IDE syntax, unresolved-reference, type, and inspection problems before or alongside Bash compilation. It analyzes one project source file, not dependency/JAR content and not a replacement for project-wide build/test commands. Use errors_only=true when only blocking errors matter.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative source file path; format the smallest relevant file after modifications."},"errors_only":{"type":"boolean","description":"Return only ERROR severity; default false."}},"required":["path"]}"""
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.obj()
        val file = support.resolveProjectFile(input.string("path"))
        DumbService.getInstance(support.project).waitForSmartMode()
        val indicator = EmptyProgressIndicator()
        val problems = ProgressManager.getInstance().runProcess(
            Computable {
                ReadAction.nonBlocking(Callable {
                    inspectFile(support, file, input.booleanOr("errors_only", false))
                })
                    .inSmartMode(support.project)
                    .withDocumentsCommitted(support.project)
                    .wrapProgress(indicator)
                    .executeSynchronously()
            },
            indicator,
        )
        return JsonObject().apply {
            addProperty("path", support.displayPath(file))
            addProperty("count", problems.size())
            add("problems", problems)
        }
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