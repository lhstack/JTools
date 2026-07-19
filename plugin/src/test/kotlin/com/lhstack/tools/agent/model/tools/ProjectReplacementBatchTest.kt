package com.lhstack.tools.agent.model.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectReplacementBatchTest {
    @Test
    fun `multiple edits for one file are applied against original content`() {
        val source = "alpha beta gamma"
        val operations = ProjectReplacementBatch.operations(listOf(
            target("file.txt", "alpha", "A", 0..4),
            target("file.txt", "gamma", "G", 11..15),
        ))

        assertEquals("A beta G", apply(source, operations))
    }

    @Test
    fun `overlapping edits for one file are rejected explicitly`() {
        val source = "alpha beta"
        assertFailsWith<IllegalArgumentException> {
            ProjectReplacementBatch.operations(listOf(
                target("file.txt", "alpha beta", "all", 0..9),
                target("file.txt", "beta", "B", 6..9),
            ))
        }
    }

    private fun target(path: String, old: String, new: String, range: IntRange) = ReplacementTarget(
        ReplaceTextRequest(path, old, new, replaceAll = false, caseSensitive = true),
        listOf(range),
    )

    private fun apply(source: String, operations: List<ReplacementOperation>): String =
        StringBuilder(source).apply {
            operations.forEach { replace(it.range.first, it.endOffset, it.newText) }
        }.toString()
}
