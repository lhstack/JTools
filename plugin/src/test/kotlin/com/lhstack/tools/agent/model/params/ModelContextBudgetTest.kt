package com.lhstack.tools.agent.model.params

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `history ratio defaults and rejects invalid values`() {
        assertEquals(0.4, ModelParams.configuredHistoryTokenRatio(null))
        assertEquals(0.75, ModelParams.configuredHistoryTokenRatio(JsonParser.parseString("""{"history_token_ratio":0.75}""")))
        assertFailsWith<IllegalArgumentException> {
            ModelParams.configuredHistoryTokenRatio(JsonParser.parseString("""{"history_token_ratio":0}"""))
        }
    }
}
