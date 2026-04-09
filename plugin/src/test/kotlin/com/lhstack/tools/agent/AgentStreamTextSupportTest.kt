package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentStreamTextSupportTest {

    @Test
    fun `returns current text when previous stream state is empty`() {
        assertEquals(
            " 世界",
            AgentStreamTextSupport.delta(previous = "", current = " 世界")
        )
    }

    @Test
    fun `returns only appended text when current stream extends previous state`() {
        assertEquals(
            " 世界",
            AgentStreamTextSupport.delta(previous = "你好", current = "你好 世界")
        )
    }
}
