package com.lhstack.tools.llm.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.lhstack.tools.llm.ProviderToolCall
import com.lhstack.tools.llm.ToolResult

/** 工具调用/结果流式事件回调，对齐 awake 的 emit_stream_event("tool_call"/"tool_result")。 */
interface ToolEventSink {
    fun onToolCall(call: ProviderToolCall)
    fun onToolResult(result: ToolResult)
    fun onStreamEvent(eventType: String, data: JsonElement) {}
    fun onModelRetry(error: String) {
        onStreamEvent("model_retry", JsonObject().apply { addProperty("error", error) })
    }
}
