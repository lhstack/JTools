package com.lhstack.tools.agent.model.tools

import kotlin.test.Test
import kotlin.test.assertTrue

class IdeProjectToolRegistrationTest {
    @Test
    fun `all ide project tools are registered and exposed in metadata`() {
        val expected = setOf(
            "read_file",
            "write_file",
            "replace_text_in_file",
            "find_files",
            "search_text",
            "format_file",
            "compile_project",
            "get_file_problems",
        )
        assertTrue(RuntimeTools.REGISTERED_BUILTIN_TOOL_NAMES.containsAll(expected))
        assertTrue(BuiltinTools.BUILTIN_TOOLS.map { it.name }.containsAll(expected))
    }
}