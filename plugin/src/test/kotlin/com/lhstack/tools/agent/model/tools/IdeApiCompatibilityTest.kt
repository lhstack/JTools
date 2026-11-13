package com.lhstack.tools.agent.model.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeApiCompatibilityTest {
    @Test
    fun `text search constructs stable process presentation`() {
        val source = source("IdeProjectSupport.kt")
        assertTrue(source.contains("FindUsagesProcessPresentation("))
        assertFalse(source.contains("FindInProjectUtil.setupProcessPresentation("))
    }

    private fun source(file: String): String = Files.readString(
        Path.of("src/main/kotlin/com/lhstack/tools/agent/model/tools/$file"),
    )
}
