package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentDependencyMigrationTest {

    @Test
    fun `plugin module no longer depends on openai or anthropic java sdk`() {
        val buildFile = locatePluginBuildFile()
        val text = Files.readString(buildFile)

        assertFalse(
            text.contains("implementation(\"com.openai:openai-java:"),
            "plugin/build.gradle.kts should not declare openai-java after migrating chat runtime to AgentScope"
        )
        assertFalse(
            text.contains("implementation(\"com.anthropic:anthropic-java:"),
            "plugin/build.gradle.kts should not declare anthropic-java after migrating chat runtime to AgentScope"
        )
    }

    private fun locatePluginBuildFile(): Path {
        val candidates = listOf(
            Path.of("plugin", "build.gradle.kts"),
            Path.of("build.gradle.kts"),
        )
        val buildFile = candidates.firstOrNull(Files::exists)
        assertTrue(buildFile != null, "plugin/build.gradle.kts should exist for dependency verification")
        return buildFile
    }
}
