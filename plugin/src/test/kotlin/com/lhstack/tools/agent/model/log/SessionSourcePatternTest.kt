package com.lhstack.tools.agent.model.log

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionSourcePatternTest {
    @Test
    fun `session source pattern includes every agent prefix`() {
        assertEquals("%:11", ModelLogService.sessionSourcePattern(11))
    }
}
