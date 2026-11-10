package com.lhstack.tools.agent.model.tools

import kotlin.test.*

class ResourceTimeoutTest {
    @Test
    fun `resource timeout uses configured agent duration without generic tool cap`() {
        assertEquals(3000L, resourceTimeoutSeconds(3000L))
        assertEquals(30L, resourceTimeoutSeconds(30L))
        assertEquals(120L, resourceTimeoutSeconds(null))
        assertFailsWith<IllegalArgumentException> { resourceTimeoutSeconds(0) }
    }

    @Test
    fun `resource timeout override wins and outer tool deadline allows child cleanup`() {
        assertEquals(45L, resourceTimeoutSeconds(45L, 3000L))
        assertEquals(3000L, resourceTimeoutSeconds(null, 3000L))
        assertFailsWith<IllegalArgumentException> { resourceTimeoutSeconds(-1L) }
        assertFailsWith<IllegalArgumentException> {
            resourceTimeoutSeconds(Int.MAX_VALUE.toLong() + 1)
        }
        for (kind in ResourceKind.entries) {
            assertEquals(
                55L,
                ViewResourceTool(kind, 99, "/tmp", null, null, timeoutSecs = 45L)
                    .executionTimeoutSeconds,
            )
        }
        val gson = com.google.gson.Gson()
        val ref = com.lhstack.tools.db.config.AgentViewResourceRef(true, 9, 45)
        assertEquals(
            ref,
            gson.fromJson(
                gson.toJson(ref),
                com.lhstack.tools.db.config.AgentViewResourceRef::class.java,
            ),
        )
        val env = com.lhstack.tools.db.service.CodingViewResourceRef(true, 9, 90)
        assertEquals(
            env,
            gson.fromJson(
                gson.toJson(env),
                com.lhstack.tools.db.service.CodingViewResourceRef::class.java,
            ),
        )
    }
}
