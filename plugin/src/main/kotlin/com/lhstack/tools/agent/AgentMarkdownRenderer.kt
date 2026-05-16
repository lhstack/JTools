package com.lhstack.tools.agent

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.Color

internal object AgentMarkdownRenderer {
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
        val body = renderer.render(parser.parse(markdown))
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
            }
            code {
              font-family: "JetBrains Mono", "Menlo", "Consolas", monospace;
              background-color: ${cssColor(codeBackgroundColor)};
            }
            table { border-collapse: collapse; margin: 4px 0 8px 0; }
            th, td { border: 1px solid ${cssColor(borderColor)}; padding: 4px 6px; }
            a { color: ${cssColor(linkColor)}; }
            </style>
            </head>
            <body>
            $body
            </body>
            </html>
        """.trimIndent()
    }

    private fun cssColor(color: Color): String {
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }
}
