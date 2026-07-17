package com.lhstack.tools.agent.model.log

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionSourcePatternTest {
    @Test
    fun `session source suffix binds history independently from agent`() {
        assertEquals(":11", ModelLogService.sessionSourceSuffix(11))
    }
}
