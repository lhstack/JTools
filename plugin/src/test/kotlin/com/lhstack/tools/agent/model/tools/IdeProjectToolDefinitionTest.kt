package com.lhstack.tools.agent.model.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeProjectToolDefinitionTest {
    @Test
    fun `project tool definitions use project-scoped names and language-neutral descriptions`() {
        val metadata = BuiltinTools.BUILTIN_TOOLS.filter { it.category == "ide" }
        assertEquals(9, metadata.size)
        metadata.forEach { tool ->
            assertTrue("project" in tool.name)
            assertTrue(LANGUAGE_SPECIFIC_WORDS.none { word -> tool.description.contains(word, ignoreCase = true) })
        }
    }

    companion object {
        private val LANGUAGE_SPECIFIC_WORDS = setOf(
            "java", "kotlin", "cargo", "gradle", "maven", "npm", "jar", "jrt", ".class",
        )
    }
}
