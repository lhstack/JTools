package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.log.ModelLogService
import com.lhstack.tools.agent.model.provider.AssistantOutputPolicy

/** Determines whether a persisted chat turn is valid model context. */
internal object ChatTurnHistoryPolicy {
    fun shouldInclude(turn: ModelLogService.ChatTurn): Boolean {
        if (turn.status == "running") return false
        return hasAssistantOutput(turn.responseData)
    }

    fun hasAssistantOutput(responseData: JsonObject): Boolean =
        AssistantOutputPolicy.hasOutput(responseData)
}
