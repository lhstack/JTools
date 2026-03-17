package com.lhstack.tools.agent

import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ThinkingBlock
import org.junit.jupiter.api.Test
import javax.swing.JLabel
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentReasoningSupportTest {

    @Test
    fun `extract thinking prefers thinking blocks only`() {
        val message = Msg.builder()
            .role(MsgRole.ASSISTANT)
            .content(
                listOf(
                    ThinkingBlock.builder().thinking("分析过程").build(),
                    TextBlock.builder().text("最终答案").build(),
                )
            )
            .build()

        assertEquals("分析过程", AgentReasoningSupport.extractThinking(message))
    }

    @Test
    fun `extract thinking returns null when no thinking block exists`() {
        val message = Msg.builder()
            .role(MsgRole.ASSISTANT)
            .content(listOf(TextBlock.builder().text("最终答案").build()))
            .build()

        assertNull(AgentReasoningSupport.extractThinking(message))
    }
}
