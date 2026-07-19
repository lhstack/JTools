package com.lhstack.tools.agent.model.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeProjectToolRegistrationTest {
    @Test
    fun `all ide project tools are registered and exposed in metadata`() {
        val expected = setOf(
            "read_project_files",
            "write_project_files",
            "replace_project_text",
            "find_project_files",
            "find_project_classes",
            "search_project_text",
            "format_project_files",
            "build_project",
            "inspect_project_files",
        )
        assertEquals(expected, RuntimeTools.REGISTERED_BUILTIN_TOOL_NAMES.intersect(expected))
        assertEquals(expected, BuiltinTools.BUILTIN_TOOLS.map { it.name }.toSet().intersect(expected))
        assertTrue(RuntimeTools.REGISTERED_BUILTIN_TOOL_NAMES.none(LEGACY_IDE_TOOL_NAMES::contains))
        assertTrue(BuiltinTools.BUILTIN_TOOLS.none { it.name in LEGACY_IDE_TOOL_NAMES })
    }

    companion object {
        private val LEGACY_IDE_TOOL_NAMES = setOf(
            "read_file", "write_file", "replace_text_in_file", "find_files",
            "search_text", "format_file", "compile_project", "get_file_problems",
        )
    }
}