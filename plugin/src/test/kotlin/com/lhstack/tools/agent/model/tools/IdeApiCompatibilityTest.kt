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

    @Test
    fun `build collector avoids deprecated and experimental event APIs`() {
        val source = source("ProjectBuildEventCollector.kt")
        assertFalse(source.contains("BuildIssueEvent"))
        assertFalse(source.contains(".isStdOut"))
        assertFalse(source.contains("position.file"))
        assertFalse(source.contains("BuildViewManager::class.java).addListener"))
        assertFalse(source.contains("BuildProgressObservable"))
        assertTrue(source.contains("ExternalSystemProgressNotificationManager"))
    }

    private fun source(file: String): String = Files.readString(
        Path.of("src/main/kotlin/com/lhstack/tools/agent/model/tools/$file"),
    )
}
