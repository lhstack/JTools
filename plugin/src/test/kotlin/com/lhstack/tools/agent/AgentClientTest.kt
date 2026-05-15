package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentClientTest {

    @Test
    fun `emit text delta keeps whitespace chunks`() {
        val client = AgentClient()
        var captured: AgentTextStreamEvent? = null
        val method = AgentClient::class.java.getDeclaredMethod(
            "emitTextDelta",
            String::class.java,
            String::class.java,
            MutableMap::class.java,
            kotlin.jvm.functions.Function1::class.java,
        ).apply {
            isAccessible = true
        }

        method.invoke(
            client,
            "assistant",
            "\n",
            mutableMapOf<String, String>(),
            { event: AgentTextStreamEvent ->
                captured = event
                Unit
            }
        )

        assertEquals("\n", captured?.text)
    }
}
