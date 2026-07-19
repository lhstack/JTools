package com.lhstack.tools.agent.model.params

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelContextBudgetTest {
    @Test
    fun `rejects output limit that consumes the full context`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ModelParams.validateContextBudget(32_000, 32_768)
        }
        assertTrue(error.message.orEmpty().contains("没有可用于系统提示词、当前输入和历史会话"))
    }

    @Test
    fun `accepts output limit below context window`() {
        ModelParams.validateContextBudget(32_000, 8_192)
    }
}
