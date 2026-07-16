package com.lhstack.tools.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRunReceiverTest {
    @Test
    fun `user receiver is assistant history and ai receiver is queued user delivery`() {
        assertTrue(AgentRunReceiver.USER.isAssistantMessage())
        assertFalse(AgentRunReceiver.USER.requiresQueueDelivery())
        assertFalse(AgentRunReceiver.AI.isAssistantMessage())
        assertTrue(AgentRunReceiver.AI.requiresQueueDelivery())
    }
}
