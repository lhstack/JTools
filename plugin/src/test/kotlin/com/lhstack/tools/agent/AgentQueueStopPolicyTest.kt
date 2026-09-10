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

    @Test
    fun `message processor cancel uses live tool slot instead of a permanent token`() {
        val slot = java.util.concurrent.atomic.AtomicReference<com.lhstack.tools.agent.model.http.ModelCancel>()
        val model = com.lhstack.tools.agent.model.http.ModelCancel()
        val view = com.lhstack.tools.agent.model.http.ModelCancel(slot)

        view.cancel()
        kotlin.test.assertFalse(model.isCancelled())
        kotlin.test.assertFalse(view.isCancelled())

        val batch = com.lhstack.tools.agent.model.http.ModelCancel()
        slot.set(batch)
        view.cancel()
        kotlin.test.assertTrue(batch.isCancelled())
        kotlin.test.assertFalse(model.isCancelled())

        slot.set(null)
        view.cancel()
        kotlin.test.assertFalse(model.isCancelled())
    }
}
