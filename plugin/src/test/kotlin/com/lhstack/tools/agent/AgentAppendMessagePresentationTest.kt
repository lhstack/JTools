package com.lhstack.tools.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentAppendMessagePresentationTest {
    @Test
    fun `append timestamp displays time without fractional seconds`() {
        assertEquals("15:31:06", compactAppendMessageCreatedAt("2026-07-31 15:31:06.54329900"))
    }

    @Test
    fun `blank append timestamp is not displayed`() {
        assertNull(compactAppendMessageCreatedAt(" "))
    }
}
