package com.lhstack.tools.db.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatSessionTypeTest {
    @Test
    fun `parses supported session types`() {
        assertEquals(ChatSessionType.PROJECT, ChatSessionType.from("project"))
        assertEquals(ChatSessionType.GLOBAL, ChatSessionType.from("global"))
    }

    @Test
    fun `rejects unsupported session type`() {
        assertFailsWith<IllegalArgumentException> { ChatSessionType.from("workspace") }
    }
}
