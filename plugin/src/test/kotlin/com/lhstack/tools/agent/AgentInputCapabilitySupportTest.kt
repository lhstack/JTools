package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentInputCapabilitySupportTest {

    @Test
    fun `attachment button hidden for text only model`() {
        val settings = AgentModelSettings().apply {
            modelCapabilities = mutableListOf("text")
        }

        assertFalse(AgentInputCapabilitySupport.attachmentButtonVisible(settings))
    }

    @Test
    fun `attachment button visible when any non text capability is enabled`() {
        val settings = AgentModelSettings().apply {
            modelCapabilities = mutableListOf("text", "image", "file")
        }

        assertTrue(AgentInputCapabilitySupport.attachmentButtonVisible(settings))
        assertEquals(setOf("image", "file"), AgentInputCapabilitySupport.allowedAttachmentKinds(settings))
    }
}
