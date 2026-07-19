package com.lhstack.tools.agent.model.provider

/**
 * 工具调用/结果事件收集器。完全照抄 awake-claw common/output.rs 的 TraceHook。
 *
 * awake 用 Arc<Mutex<Vec<TraceEvent>>> 在工具执行时累积事件，请求结束后交给
 * structured_output 生成 tool_calls / tool_results。Kotlin 侧实现 ToolHook 接口
 * 承接 ToolRuntime 的回调，线程安全地收集事件（同轮工具并发执行，需加锁）。
 */
class TraceHook : ToolHook {

    private val lock = Any()
    private val events = mutableListOf<TraceEvent>()

    override fun onToolCall(name: String, callId: String?, internalId: String, args: String) {
        synchronized(lock) {
            events.add(TraceEvent.ToolCall(name, callId, internalId, args))
        }
    }

    override fun onToolResult(
        name: String,
        callId: String?,
        internalId: String,
        args: String,
        result: String,
    ) {
        synchronized(lock) {
            events.add(TraceEvent.ToolResult(name, callId, internalId, args, result))
        }
    }

    /** 快照当前事件列表。 */
    fun events(): List<TraceEvent> = synchronized(lock) { events.toList() }
}

/** 工具追踪事件。对齐 awake 的 TraceEvent 枚举。 */
sealed class TraceEvent {
    data class ToolCall(
        val toolName: String,
        val toolCallId: String?,
        val internalCallId: String,
        val args: String,
    ) : TraceEvent()

    data class ToolResult(
        val toolName: String,
        val toolCallId: String?,
        val internalCallId: String,
        val args: String,
        val result: String,
    ) : TraceEvent()
}
