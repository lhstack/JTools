package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import java.awt.Color
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentMarkdownRendererTest {

    @Test
    fun `renders common markdown blocks to html`() {
        val html = render(
            """
            # 标题

            - **重点**

            | A | B |
            |---|---|
            | 1 | 2 |

            ```kotlin
            val answer = 42
            ```
            """.trimIndent()
        )

        assertTrue(html.contains("<h1>标题</h1>"))
        assertTrue(html.contains("<strong>重点</strong>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("val answer = 42"))
    }

    @Test
    fun `escapes raw html from model output`() {
        val html = render("<script>alert(1)</script>")

        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
    }

    @Test
    fun `preserves soft line breaks in assistant text`() {
        val html = render("第一行\n第二行")

        assertTrue(html.contains("第一行<br />"))
        assertTrue(html.contains("第二行"))
    }

    @Test
    fun `wraps raw markdown code blocks with copy controls and soft wrap styles`() {
        val rendered = renderFull(
            """
            ```text
            ${"a".repeat(120)}
            ```
            """.trimIndent()
        )

        assertTrue(rendered.html.contains("jtools-copy-raw:raw-0"))
        assertTrue(rendered.html.contains("white-space: pre-wrap"))
        assertTrue(rendered.rawBlocks["raw-0"]?.contains("a".repeat(120)) == true)
    }

    @Test
    fun `renders top level markdown fence as raw content`() {
        val rendered = renderFull(
            """
            ```markdown
            # 标题

            ```json
            {"name":"demo"}
            ```

            ```xml
            <project name="demo"/>
            ```
            ```
            """.trimIndent()
        )

        assertTrue(rendered.html.contains("jtools-copy-raw:raw-0"))
        assertTrue(rendered.html.contains("# 标题"))
        assertTrue(rendered.html.contains("```json"))
        assertTrue(rendered.html.contains("&lt;project name=\"demo\"/&gt;"))
        assertTrue(rendered.rawBlocks["raw-0"]?.contains("```json") == true)
        assertTrue(rendered.rawBlocks["raw-0"]?.contains("<project name=\"demo\"/>") == true)
        assertFalse(rendered.html.contains("<h1>标题</h1>"))
    }

    @Test
    fun `keeps nested fences raw inside markdown fence within normal markdown`() {
        val rendered = renderFull(
            """
            ## 正常段落

            这里仍然按普通 Markdown 渲染。

            ```markdown
            # Raw 标题

            ```json
            {"name":"demo"}
            ```

            ```yml
            name: demo
            ```

            ```xml
            <project name="demo"/>
            ```
            ```

            **结束**
            """.trimIndent()
        )

        assertTrue(rendered.html.contains("<h2>正常段落</h2>"))
        assertTrue(rendered.html.contains("<strong>结束</strong>"))
        assertTrue(rendered.html.contains("jtools-copy-raw:raw-0"))
        assertTrue(rendered.html.contains("# Raw 标题"))
        assertTrue(rendered.html.contains("```json"))
        assertTrue(rendered.html.contains("```yml"))
        assertTrue(rendered.html.contains("```xml"))
        assertFalse(rendered.html.contains("<h1>Raw 标题</h1>"))
        assertTrue(rendered.rawBlocks["raw-0"]?.contains("<project name=\"demo\"/>") == true)
    }

    private fun render(markdown: String): String {
        return renderFull(markdown).html
    }

    private fun renderFull(markdown: String): AgentMarkdownRenderer.RenderedMarkdown {
        return AgentMarkdownRenderer.render(
            markdown = markdown,
            textColor = Color.BLACK,
            backgroundColor = Color.WHITE,
            borderColor = Color.LIGHT_GRAY,
            codeBackgroundColor = Color(0xF5F5F5),
            linkColor = Color.BLUE,
            fontFamily = "Dialog",
            fontSize = 12,
        )
    }
}
