package com.lhstack.tools.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentBrowserCommandThreadPolicyTest {
    @Test
    fun `append attachment task state commands do not wait for modal EDT`() {
        assertTrue(AgentBrowserCommandThreadPolicy.canRunOnQueryThread("appendAttachment.poll"))
        assertTrue(AgentBrowserCommandThreadPolicy.canRunOnQueryThread("appendAttachment.cancel"))
    }

    @Test
    fun `commands touching UI remain on EDT`() {
        assertFalse(AgentBrowserCommandThreadPolicy.canRunOnQueryThread("appendAttachment.choose.start"))
        assertFalse(AgentBrowserCommandThreadPolicy.canRunOnQueryThread("appendAttachment.paste.start"))
        assertFalse(AgentBrowserCommandThreadPolicy.canRunOnQueryThread("message.send"))
    }
}
