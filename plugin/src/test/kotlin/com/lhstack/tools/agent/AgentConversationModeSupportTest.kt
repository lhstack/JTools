package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentConversationModeSupportTest {

    @Test
    fun `responses selector stays hidden until runtime is implemented`() {
        assertFalse(AgentConversationModeSupport.selectorVisible())
        assertEquals(listOf(AgentConversationMode.CHAT), AgentConversationModeSupport.availableModes(true))
    }
}
