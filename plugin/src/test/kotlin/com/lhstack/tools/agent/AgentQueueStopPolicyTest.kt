package com.lhstack.tools.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentQueueStopPolicyTest {

    @Test
    fun `active tool calls are stopped without terminating conversation`() {
        assertEquals(
            AgentQueueStopPolicy.Target.ACTIVE_TOOLS,
            AgentQueueStopPolicy.target(hasRunningTools = true),
        )
    }

    @Test
    fun `conversation is terminated when no tool call is active`() {
        assertEquals(
            AgentQueueStopPolicy.Target.CONVERSATION,
            AgentQueueStopPolicy.target(hasRunningTools = false),
        )
    }
}
