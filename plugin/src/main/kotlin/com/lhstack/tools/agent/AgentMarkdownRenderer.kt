package com.lhstack.tools.agent

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.Color

internal object AgentMarkdownRenderer {
    private val rawBlockPattern = Regex(
        pattern = "<pre><code([^>]*)>(.*?)</code></pre>",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val rawPlaceholderParagraphPattern = Regex("<p>(JTOOLS_RAW_MARKDOWN_BLOCK_[0-9]+)</p>")

    data class RenderedMarkdown(
        val html: String,
        val rawBlocks: Map<String, String>,
    )

    private data class RawMarkdownSection(
        val id: String,
        val placeholder: String,
        val content: String,
    )

    private data class PreprocessedMarkdown(
        val markdown: String,
        val rawSections: List<RawMarkdownSection>,
    )

    private data class Fence(
        val marker: Char,
        val length: Int,
        val info: String,
    )

    private val extensions = listOf(
        AutolinkExtension.create(),
        TablesExtension.create(),
        TaskListItemsExtension.create(),
    )
    private val parser = Parser.builder()
        .extensions(extensions)
        .build()
    private val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .softbreak("<br />\n")
        .build()

    fun renderHtml(
        markdown: String,
        textColor: Color,
        backgroundColor: Color,
        borderColor: Color,
        codeBackgroundColor: Color,
        linkColor: Color,
        fontFamily: String,
        fontSize: Int,
    ): String {
        return render(
            markdown = markdown,
            textColor = textColor,
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            codeBackgroundColor = codeBackgroundColor,
            linkColor = linkColor,
            fontFamily = fontFamily,
            fontSize = fontSize,
        ).html
    }

    fun render(
        markdown: String,
        textColor: Color,
        backgroundColor: Color,
        borderColor: Color,
        codeBackgroundColor: Color,
        linkColor: Color,
        fontFamily: String,
        fontSize: Int,
    ): RenderedMarkdown {
        val preprocessed = preprocessRawMarkdownSections(markdown)
        val rawBlocks = linkedMapOf<String, String>()
        preprocessed.rawSections.forEach { section ->
            rawBlocks[section.id] = section.content
        }
        val document = parser.parse(preprocessed.markdown)
        val rawBlockLiterals = collectRawBlockLiterals(document)
        val renderedBody = renderer.render(document)
        val (bodyWithCodeBlocks, codeRawBlocks) = injectRawBlockCopyControls(
            body = renderedBody,
            literals = rawBlockLiterals,
            startIndex = rawBlocks.size
        )
        rawBlocks.putAll(codeRawBlocks)
        val body = restoreRawMarkdownSections(bodyWithCodeBlocks, preprocessed.rawSections)
        return """
            <html>
            <head>
            <style>
            body {
              color: ${cssColor(textColor)};
              background-color: ${cssColor(backgroundColor)};
              font-family: "$fontFamily", sans-serif;
              font-size: ${fontSize}pt;
              margin: 0;
              padding: 4px 12px 6px 8px;
              overflow-wrap: break-word;
              word-wrap: break-word;
            }
            p { margin: 0 0 7px 0; }
            h1, h2, h3, h4, h5, h6 { margin: 8px 0 6px 0; font-weight: bold; }
            h1 { font-size: 1.45em; }
            h2 { font-size: 1.28em; }
            h3 { font-size: 1.14em; }
            ul, ol { margin: 0 0 7px 22px; padding: 0; }
            li { margin: 2px 0; }
            blockquote {
              margin: 4px 0 8px 0;
              padding: 2px 0 2px 9px;
              border-left: 3px solid ${cssColor(borderColor)};
            }
            pre {
              margin: 4px 0 8px 0;
              padding: 7px;
              background-color: ${cssColor(codeBackgroundColor)};
              border: 1px solid ${cssColor(borderColor)};
              white-space: pre-wrap;
              overflow-wrap: break-word;
              word-wrap: break-word;
            }
            code {
              font-family: "JetBrains Mono", "Menlo", "Consolas", monospace;
              background-color: ${cssColor(codeBackgroundColor)};
              white-space: pre-wrap;
              overflow-wrap: break-word;
              word-wrap: break-word;
            }
            table { border-collapse: collapse; margin: 4px 0 8px 0; word-wrap: break-word; }
            .raw-block {
              width: 100%;
              background-color: ${cssColor(codeBackgroundColor)};
              border: 1px solid ${cssColor(borderColor)};
              padding: 6px 7px 7px 7px;
            }
            .raw-toolbar {
              text-align: right;
              margin: 0 0 4px 0;
            }
            .raw-block pre {
              margin: 0;
              border: 1px solid ${cssColor(borderColor)};
              background-color: ${cssColor(codeBackgroundColor)};
            }
            th, td { border: 1px solid ${cssColor(borderColor)}; padding: 4px 6px; }
            a { color: ${cssColor(linkColor)}; }
            a.raw-copy-button {
              display: inline-block;
              padding: 1px 7px;
              border: 1px solid ${cssColor(borderColor)};
              background-color: ${cssColor(backgroundColor)};
              color: ${cssColor(linkColor)};
              font-size: 0.9em;
              text-decoration: none;
            }
            </style>
            </head>
            <body>
            $body
            </body>
            </html>
        """.trimIndent().let { RenderedMarkdown(it, rawBlocks) }
    }

    private fun collectRawBlockLiterals(document: Node): List<String> {
        val literals = mutableListOf<String>()
        document.accept(object : AbstractVisitor() {
            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                literals.add(fencedCodeBlock.literal)
            }

            override fun visit(indentedCodeBlock: IndentedCodeBlock) {
                literals.add(indentedCodeBlock.literal)
            }
        })
        return literals
    }

    private fun preprocessRawMarkdownSections(markdown: String): PreprocessedMarkdown {
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val output = mutableListOf<String>()
        val rawSections = mutableListOf<RawMarkdownSection>()
        var index = 0
        while (index < lines.size) {
            val outerFence = parseFence(lines[index])
            if (outerFence != null && fenceLanguage(outerFence) in setOf("markdown", "md")) {
                val id = "raw-${rawSections.size}"
                val placeholder = "JTOOLS_RAW_MARKDOWN_BLOCK_${rawSections.size}"
                val contentLines = mutableListOf<String>()
                val nestedFences = mutableListOf<Fence>()
                index++
                while (index < lines.size) {
                    val line = lines[index]
                    val fence = parseFence(line)
                    if (fence != null) {
                        val nestedFence = nestedFences.lastOrNull()
                        when {
                            nestedFence != null && isClosingFence(fence, nestedFence) -> {
                                nestedFences.removeAt(nestedFences.lastIndex)
                                contentLines.add(line)
                                index++
                                continue
                            }

                            nestedFence == null && isClosingFence(fence, outerFence) -> {
                                index++
                                break
                            }

                            fence.info.isNotBlank() -> {
                                nestedFences.add(fence)
                            }
                        }
                    }
                    contentLines.add(line)
                    index++
                }
                rawSections.add(RawMarkdownSection(id, placeholder, contentLines.joinToString("\n")))
                output.add(placeholder)
            } else {
                output.add(lines[index])
                index++
            }
        }
        return PreprocessedMarkdown(output.joinToString("\n"), rawSections)
    }

    private fun restoreRawMarkdownSections(body: String, sections: List<RawMarkdownSection>): String {
        if (sections.isEmpty()) {
            return body
        }
        val byPlaceholder = sections.associateBy { it.placeholder }
        var restored = rawPlaceholderParagraphPattern.replace(body) { match ->
            val placeholder = match.groupValues[1]
            byPlaceholder[placeholder]?.let { section ->
                wrapRawBlock(section.id, "", escapeHtml(section.content))
            } ?: match.value
        }
        sections.forEach { section ->
            restored = restored.replace(section.placeholder, wrapRawBlock(section.id, "", escapeHtml(section.content)))
        }
        return restored
    }

    private fun parseFence(line: String): Fence? {
        var offset = 0
        while (offset < line.length && line[offset] == ' ' && offset < 4) {
            offset++
        }
        if (offset > 3 || offset >= line.length) {
            return null
        }
        val marker = line[offset]
        if (marker != '`' && marker != '~') {
            return null
        }
        var end = offset
        while (end < line.length && line[end] == marker) {
            end++
        }
        val length = end - offset
        if (length < 3) {
            return null
        }
        return Fence(marker, length, line.substring(end).trim())
    }

    private fun isClosingFence(candidate: Fence, opener: Fence): Boolean {
        return candidate.marker == opener.marker && candidate.length >= opener.length && candidate.info.isBlank()
    }

    private fun fenceLanguage(fence: Fence): String {
        return fence.info.substringBefore(' ').substringBefore('\t').lowercase()
    }

    private fun injectRawBlockCopyControls(
        body: String,
        literals: List<String>,
        startIndex: Int,
    ): Pair<String, Map<String, String>> {
        if (literals.isEmpty()) {
            return body to emptyMap()
        }
        var index = startIndex
        var literalIndex = 0
        val rawBlocks = linkedMapOf<String, String>()
        val wrappedBody = rawBlockPattern.replace(body) { match ->
            val literal = literals.getOrNull(literalIndex++)
                ?: return@replace match.value
            val id = "raw-${index++}"
            rawBlocks[id] = literal
            val codeAttributes = match.groupValues[1]
            val codeBody = match.groupValues[2]
            wrapRawBlock(id, codeAttributes, codeBody)
        }
        return wrappedBody to rawBlocks
    }

    private fun wrapRawBlock(id: String, codeAttributes: String, codeBody: String): String {
        return """
            <div class="raw-block">
              <div class="raw-toolbar"><a class="raw-copy-button" href="jtools-copy-raw:$id">复制</a></div>
              <pre><code$codeAttributes>$codeBody</code></pre>
            </div>
        """.trimIndent()
    }

    private fun cssColor(color: Color): String {
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }

    private fun escapeHtml(text: String): String {
        return buildString(text.length) {
            text.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(char)
                }
            }
        }
    }
}
