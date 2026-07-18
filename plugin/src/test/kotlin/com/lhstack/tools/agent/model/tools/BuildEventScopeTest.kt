package com.lhstack.tools.agent.model.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildEventScopeTest {
    @Test
    fun `binds task runner root when ProjectTaskContext session id is not propagated`() {
        val scope = BuildEventScope("project-task-session")

        assertTrue(scope.accept("gradle-build", "start", null, "gradle-descriptor"))
        assertTrue(scope.accept("gradle-build", "compiler-error", "gradle-descriptor", null))
        assertTrue(scope.accept("gradle-build", "stderr", "compiler-error", null))
    }

    @Test
    fun `rejects unrelated event tree after root is bound`() {
        val scope = BuildEventScope("project-task-session")

        assertTrue(scope.accept("gradle-build", "start", null, "gradle-descriptor"))
        assertFalse(scope.accept("other-build", "other-start", null, "other-descriptor"))
        assertFalse(scope.accept("other-build", "other-output", "other-descriptor", null))
    }

    @Test
    fun `accepts runners that propagate ProjectTaskContext session id`() {
        val scope = BuildEventScope("project-task-session")

        assertTrue(scope.accept("project-task-session", "start", null, "descriptor"))
        assertTrue(scope.accept("descriptor", "output", "start", null))
    }
}
