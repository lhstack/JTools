package com.lhstack.tools.agent.model.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectFileGlobTest {
    @Test
    fun `path glob matches top-level and nested Rust files`() {
        val glob = ProjectFileGlob("src/**/*.rs")
        assertTrue(glob.matches("src/web.rs", "web.rs"))
        assertTrue(glob.matches("src/service/agent.rs", "agent.rs"))
        assertFalse(glob.matches("tests/agent.rs", "agent.rs"))
    }

    @Test
    fun `name glob matches files in any project directory`() {
        val glob = ProjectFileGlob("*.rs")
        assertTrue(glob.matches("src/service/agent.rs", "agent.rs"))
        assertFalse(glob.matches("src/service/agent.kt", "agent.kt"))
    }

    @Test
    fun `missing glob accepts every file`() {
        assertTrue(ProjectFileGlob(null).matches("src/service/agent.rs", "agent.rs"))
    }
}
