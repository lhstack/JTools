package com.lhstack.tools.llm.provider

import com.lhstack.tools.llm.ToolExecutionStatus

/** 工具调用/结果记录回调，对齐 awake 的 TraceHook.on_tool_call / on_tool_result。 */
interface ToolHook {
    fun onToolCall(name: String, callId: String?, internalId: String, args: String)
    fun onToolResult(name: String, callId: String?, internalId: String, args: String, result: String, status: ToolExecutionStatus)

    companion object {
        val NOOP: ToolHook = object : ToolHook {
            override fun onToolCall(name: String, callId: String?, internalId: String, args: String) {}
            override fun onToolResult(
                name: String, callId: String?, internalId: String, args: String, result: String, status: ToolExecutionStatus,
            ) {}
        }
    }
}
