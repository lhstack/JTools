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
    private val rawMarkdownFencePattern = Regex("""^\s*`{3,}\s*(markdown|md)\s*\R""", RegexOption.IGNORE_CASE)

    data class RenderedMarkdown(
        val html: String,
        val rawBlocks: Map<String, String>,
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
        val rawMarkdown = extractRawMarkdownFence(markdown)
        val (body, rawBlocks) = if (rawMarkdown != null) {
            val id = "raw-0"
            wrapRawBlock(id = id, codeAttributes = "", codeBody = escapeHtml(rawMarkdown)) to linkedMapOf(id to rawMarkdown)
        } else {
            val document = parser.parse(markdown)
            val rawBlockLiterals = collectRawBlockLiterals(document)
            val renderedBody = renderer.render(document)
            injectRawBlockCopyControls(renderedBody, rawBlockLiterals)
        }
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
            table.raw-block {
              width: 100%;
              background-color: ${cssColor(codeBackgroundColor)};
              border: 1px solid ${cssColor(borderColor)};
            }
            table.raw-block td { border: 0; padding: 0; }
            table.raw-block td.raw-toolbar {
              text-align: right;
              padding: 2px 6px 0 6px;
            }
            table.raw-block pre {
              margin: 0;
              border: 0;
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

    private fun extractRawMarkdownFence(markdown: String): String? {
        val match = rawMarkdownFencePattern.find(markdown) ?: return null
        var content = markdown.substring(match.range.last + 1)
        val lines = content.lines()
        if (lines.isNotEmpty() && lines.last().trim().matches(Regex("""`{3,}"""))) {
            content = lines.dropLast(1).joinToString("\n")
        }
        return content
    }

    private fun injectRawBlockCopyControls(
        body: String,
        literals: List<String>,
    ): Pair<String, Map<String, String>> {
        if (literals.isEmpty()) {
            return body to emptyMap()
        }
        var index = 0
        val rawBlocks = linkedMapOf<String, String>()
        val wrappedBody = rawBlockPattern.replace(body) { match ->
            val literal = literals.getOrNull(index)
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
            <table class="raw-block" cellspacing="0" cellpadding="0">
            <tr><td class="raw-toolbar"><a class="raw-copy-button" href="jtools-copy-raw:$id">复制</a></td></tr>
            <tr><td><pre><code$codeAttributes>$codeBody</code></pre></td></tr>
            </table>
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
