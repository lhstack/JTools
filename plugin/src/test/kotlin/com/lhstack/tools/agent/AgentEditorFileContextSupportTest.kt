package com.lhstack.tools.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AgentEditorFileContextSupportTest {

    @Test
    fun `formatPrefix for file only is path`() {
        val prefix = AgentEditorFileContextSupport.formatPrefix(
            AgentEditorFileContextSupport.Snapshot(path = "src/Main.kt"),
        )
        assertEquals("src/Main.kt", prefix)
    }

    @Test
    fun `formatPrefix for selection includes offsets`() {
        val prefix = AgentEditorFileContextSupport.formatPrefix(
            AgentEditorFileContextSupport.Snapshot(
                path = "plugin/src/Main.kt",
                startOffset = 12,
                endOffset = 88,
                startLine = 3,
                endLine = 10,
            ),
        )
        assertEquals("plugin/src/Main.kt, startOffset=12, endOffset=88", prefix)
    }

    @Test
    fun `formatPrefix ignores empty selection range`() {
        val prefix = AgentEditorFileContextSupport.formatPrefix(
            AgentEditorFileContextSupport.Snapshot(
                path = "a.kt",
                startOffset = 5,
                endOffset = 5,
            ),
        )
        assertEquals("a.kt", prefix)
    }

    @Test
    fun `formatPrefix null returns null`() {
        assertNull(AgentEditorFileContextSupport.formatPrefix(null))
    }

    @Test
    fun `prependToPrompt places context before user text`() {
        val snapshot = AgentEditorFileContextSupport.Snapshot(path = "docs/AGENTS.md")
        val result = AgentEditorFileContextSupport.prependToPrompt("请总结这个文件", snapshot)
        assertEquals("docs/AGENTS.md\n\n请总结这个文件", result)
    }

    @Test
    fun `prependToPrompt with empty body is context only`() {
        val snapshot = AgentEditorFileContextSupport.Snapshot(
            path = "a.kt",
            startOffset = 1,
            endOffset = 9,
            startLine = 1,
            endLine = 2,
        )
        val result = AgentEditorFileContextSupport.prependToPrompt("   ", snapshot)
        assertEquals("a.kt, startOffset=1, endOffset=9", result)
    }

    @Test
    fun `prependToPrompt without snapshot keeps original prompt`() {
        assertEquals("hello", AgentEditorFileContextSupport.prependToPrompt("hello", null))
    }

    @Test
    fun `settingKey is project-scoped`() {
        val keyA = AgentEditorFileContextSupport.settingKey("/tmp/project-a")
        val keyB = AgentEditorFileContextSupport.settingKey("/tmp/project-b")
        assertEquals(false, keyA == keyB)
        assertEquals(true, keyA.startsWith("agent.chat.file_context_enabled:"))
        assertEquals(true, keyB.startsWith("agent.chat.file_context_enabled:"))
    }

    @Test
    fun `formatChipLabel uses truncated name and offsets`() {
        assertEquals(
            "Main.kt",
            AgentEditorFileContextSupport.formatChipLabel(
                AgentEditorFileContextSupport.Snapshot(path = "src/Main.kt"),
            ),
        )
        assertEquals(
            "...gradle.kts 100,200",
            AgentEditorFileContextSupport.formatChipLabel(
                AgentEditorFileContextSupport.Snapshot(
                    path = "plugin/build.gradle.kts",
                    startOffset = 100,
                    endOffset = 200,
                    startLine = 89,
                    endLine = 94,
                ),
            ),
        )
        assertEquals(
            "Main.kt 1,10",
            AgentEditorFileContextSupport.formatChipLabel(
                AgentEditorFileContextSupport.Snapshot(
                    path = "Main.kt",
                    startOffset = 1,
                    endOffset = 10,
                    startLine = 12,
                    endLine = 12,
                ),
            ),
        )
    }

    @Test
    fun `truncateFileName keeps trailing characters`() {
        assertEquals("short.kt", AgentEditorFileContextSupport.truncateFileName("short.kt"))
        assertEquals("...gradle.kts", AgentEditorFileContextSupport.truncateFileName("build.gradle.kts", 14))
    }
}
