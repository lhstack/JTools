package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentStreamTextSupportTest {

    @Test
    fun `returns chunk as is for stream delta`() {
        assertEquals(
            " 世界",
            AgentStreamTextSupport.streamDelta(" 世界")
        )
    }

    @Test
    fun `appends incoming chunk when accumulating stream state`() {
        assertEquals(
            "你好 世界",
            AgentStreamTextSupport.accumulate(existing = "你好", incoming = " 世界")
        )
    }
}
