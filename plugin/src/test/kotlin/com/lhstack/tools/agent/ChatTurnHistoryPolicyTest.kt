package com.lhstack.tools.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.log.ModelLogService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatTurnHistoryPolicyTest {
    @Test
    fun `failed turn with response is included in model history`() {
        assertTrue(ChatTurnHistoryPolicy.shouldInclude(turn("failed", structured(response = "partial answer"))))
    }

    @Test
    fun `failed turn with tool output is included in model history`() {
        val value = structured()
        val structured = value.getAsJsonObject("structured_response")
        structured.add("tool_calls", JsonArray().apply {
            add(JsonObject().apply { addProperty("internal_call_id", "1") })
        })
        structured.add("tool_results", JsonArray().apply {
            add(JsonObject().apply { addProperty("internal_call_id", "1") })
        })
        assertTrue(ChatTurnHistoryPolicy.shouldInclude(turn("failed", value)))
    }

    @Test
    fun `failed turn without model output is excluded from model history`() {
        assertFalse(ChatTurnHistoryPolicy.shouldInclude(turn("failed", structured())))
    }


    @Test
    fun `cancelled turn without model output is excluded from model history`() {
        assertFalse(ChatTurnHistoryPolicy.shouldInclude(turn("cancelled", structured())))
    }

    @Test
    fun `running turn is excluded from model history`() {
        assertFalse(ChatTurnHistoryPolicy.shouldInclude(turn("running", structured(response = "streaming"))))
    }

    private fun structured(response: String = ""): JsonObject = JsonObject().apply {
        add("structured_response", JsonObject().apply {
            addProperty("response", response)
            add("reasoning", JsonArray())
            add("tool_calls", JsonArray())
            add("tool_results", JsonArray())
        })
    }

    private fun turn(status: String, responseData: JsonObject) = ModelLogService.ChatTurn(
        logId = 1,
        agentId = 1,
        status = status,
        requestData = JsonObject(),
        responseData = responseData,
        errorData = null,
        userMessageAt = null,
        assistantMessageAt = null,
        createdAt = null,
        messageType = "chat_turn",
    )
}
