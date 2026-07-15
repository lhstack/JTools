package com.lhstack.tools.db

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.log.ModelLogService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModelRequestLogMessageTimeSchemaTest {
    @Test
    fun `user message time is stored in request data without schema columns`() {
        val requestData = ModelLogService.requestLogData(
            providerRequest = JsonObject(),
            requestSnapshot = JsonObject(),
            userMessageAt = "2026-07-15 14:00:00",
        ).asJsonObject
        val createTable = AgentSchema.STATEMENTS.first { it.contains("create table if not exists model_request_logs") }

        assertEquals("2026-07-15 14:00:00", requestData.get("user_message_at").asString)
        assertFalse(createTable.contains("user_message_at text"))
        assertFalse(createTable.contains("assistant_message_at text"))
    }
}
