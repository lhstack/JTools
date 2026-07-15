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
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.PsiManager
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ReadFileTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Read text from a project file or from a jar://, jrt://, or file:// URL returned by find_files. Reads the current IDE Document when available. Use lines for fine-grained ranges; line numbers are 1-based.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative path or IDE URL returned by find_files."},"lines":{"type":"array","description":"Optional line ranges; omitted reads from line 1 up to max_lines.","items":{"type":"object","properties":{"start":{"type":"integer"},"end":{"type":"integer"}},"required":["start","end"]}},"max_lines":{"type":"integer","description":"Maximum returned lines, default 1000, hard limit 5000."}},"required":["path"]}"""
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
        "Create or fully overwrite a project text file through JetBrains VFS/Document. Missing parent directories are created. The IDE controls the target file encoding; overwrite defaults to false.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative path."},"content":{"type":"string","description":"Complete file content."},"overwrite":{"type":"boolean","description":"Allow replacing an existing file; default false."}},"required":["path","content"]}"""
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
        "Precisely replace text in one project file through the current IDE Document and a WriteCommandAction. old_text must match exactly once unless replace_all=true. Prefer this over rewriting a whole existing file.",
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
        "Find files by file name using the JetBrains filename index. By default searches project files only; set include_libraries=true to include dependency sources and JAR entries. Returned jar:// or jrt:// paths can be passed to read_file.",
        """{"type":"object","properties":{"name":{"type":"string","description":"Name or pattern."},"match":{"type":"string","enum":["exact","contains","glob"],"description":"Default contains."},"include_libraries":{"type":"boolean","description":"Include dependency/JAR files; default false."},"limit":{"type":"integer","description":"Default 100, hard limit 1000."}},"required":["name"]}"""
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
        "Search project file contents with the JetBrains Find engine and return matching line plus fine-grained surrounding lines. By default searches project files; include_libraries also searches indexed dependency source/resource files in JARs (binary .class files are not bulk-decompiled).",
        """{"type":"object","properties":{"query":{"type":"string"},"regex":{"type":"boolean","description":"Treat query as regex; default false."},"case_sensitive":{"type":"boolean","description":"Default true."},"include_libraries":{"type":"boolean","description":"Include dependency source/resource files; default false."},"file_pattern":{"type":"string","description":"Optional file-name glob, such as *.kt."},"context_lines":{"type":"integer","description":"Lines before and after each match, default 2, hard limit 20."},"limit":{"type":"integer","description":"Default 100, hard limit 1000."}},"required":["query"]}"""
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
        "Reformat one project source file with the JetBrains formatter and the project's configured code style.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative source file path."},"timeout_secs":{"type":"integer","description":"Default 30, hard limit 120."}},"required":["path"]}"""
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

internal class GetFileProblemsTool(private val support: IdeProjectSupport) : ToolDyn {
    override fun definition(prompt: String) = definition(
        NAME,
        "Run JetBrains code analysis for one project source file and return structured diagnostics with severity, line, column, and description. This sees IDE/inspection problems that a shell compiler may not report.",
        """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative source file path."},"errors_only":{"type":"boolean","description":"Return only ERROR severity; default false."}},"required":["path"]}"""
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
            inspectionManager.defaultProcessFile(tool, psiFile).asSequence()
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