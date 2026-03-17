package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentChatRenderingSupportTest {

    @Test
    fun `final assistant content still renders after reasoning stream`() {
        assertTrue(
            AgentChatRenderingSupport.shouldRenderFinalAssistantContent(
                assistantStarted = false,
                assistantContent = "最终答案",
            )
        )
    }

    @Test
    fun `blank final assistant content does not render fallback`() {
        assertFalse(
            AgentChatRenderingSupport.shouldRenderFinalAssistantContent(
                assistantStarted = false,
                assistantContent = "  ",
            )
        )
    }

    @Test
    fun `existing assistant stream does not render duplicate fallback`() {
        assertFalse(
            AgentChatRenderingSupport.shouldRenderFinalAssistantContent(
                assistantStarted = true,
                assistantContent = "最终答案",
            )
        )
    }
}
