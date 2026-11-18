package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.resources.ScopedSkillsTool
import com.lhstack.tools.agent.resources.SkillSource
import com.lhstack.tools.llm.provider.ToolResultText
import java.nio.file.Files
import kotlin.test.*

class ToolResultLayoutTest {
    @Test
    fun `plain renderer separates multiline records and keeps single-line lists compact`() {
        val value = JsonParser.parseString("""{"entries":[{"name":"first","paths":["a","b"]},{"name":"second","paths":["c","d"]}]}""")
        assertEquals("entries:\n  name: first\n  paths:\n    a\n    b\n\n  name: second\n  paths:\n    c\n    d", ToolResultText.render(value))
        val raw = "  {\"a\":1}\r\n\r\n    tail\n"
        assertEquals(raw, ToolResultText.render(com.google.gson.JsonPrimitive(raw)))
    }

    @Test
    fun `ordinary and scoped skill catalogs separate entries and keep view content verbatim`() {
        val root = Files.createTempDirectory("jtools-skill-result-layout-").toFile()
        try {
            val workspace = WorkspaceTools(root.path)
            val original = "---\ndescription: First summary\n---\n\n  indented\n\ncontent\n"
            root.resolve("global/alpha").mkdirs()
            root.resolve("global/alpha/SKILL.md").writeText(original)
            root.resolve("global/alpha/notes.txt").writeText("note")
            root.resolve("global/beta").mkdirs()
            root.resolve("global/beta/SKILL.md").writeText("---\ndescription: Second summary\n---\n")
            root.resolve("project/alpha").mkdirs()
            root.resolve("project/alpha/SKILL.md").writeText(original)
            val global = SkillSource("global", root.resolve("global"), setOf("alpha", "beta"))
            val project = SkillSource("project", root.resolve("project"), setOf("alpha"))
            val ordinary = SkillsListTool(workspace, global.root, global.enabled).executeBlocking(JsonObject()).text
            assertContains(ordinary, "可读文件：\nSKILL.md\nnotes.txt\n\n技能：beta")
            val scoped = ScopedSkillsTool(false, workspace, listOf(global, project)).executeBlocking(JsonObject()).text
            assertContains(scoped, "\n\n技能：alpha\n作用域：project\n")
            assertContains(scoped, "技能：alpha\n作用域：global\n")
            val args = JsonObject().apply { addProperty("scope", "global"); addProperty("name", "alpha") }
            val view = ScopedSkillsTool(true, workspace, listOf(global, project)).executeBlocking(args).text
            assertTrue(view.endsWith("\n\n$original"))
            val limited = ScopedSkillsTool(true, workspace, listOf(global, project)).executeBlocking(args.apply { addProperty("max_bytes", 20) }).text
            assertContains(limited, "[内容已截断；原文件：")
        } finally {
            root.deleteRecursively()
        }
    }
}
