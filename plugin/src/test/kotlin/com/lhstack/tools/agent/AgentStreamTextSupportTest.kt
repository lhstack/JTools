package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentStreamTextSupportTest {

    @Test
    fun `returns full text for first chunk`() {
        assertEquals("最终答案", AgentStreamTextSupport.delta(previous = "", current = "最终答案"))
    }

    @Test
    fun `returns appended suffix for cumulative stream`() {
        assertEquals(" 世界", AgentStreamTextSupport.delta(previous = "你好", current = "你好 世界"))
    }

    @Test
    fun `returns empty text when snapshot has not changed`() {
        assertEquals("", AgentStreamTextSupport.delta(previous = "你好", current = "你好"))
    }

    @Test
    fun `falls back to current text when stream is not prefix based`() {
        assertEquals("重写后的内容", AgentStreamTextSupport.delta(previous = "旧内容", current = "重写后的内容"))
    }
}
