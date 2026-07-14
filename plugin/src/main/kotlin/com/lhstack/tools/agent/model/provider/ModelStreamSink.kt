package com.lhstack.tools.agent.model.provider

/**
 * 模型文本事件回调。对齐 awake-claw 的 emit_stream_event("response_delta"/"reasoning_delta")。
 * 流式 SSE 逐 delta 触发；非流式或整轮返回时，在 provider round 完成后立即触发。
 *
 * awake 用线程局部的全局 writer 发事件；Kotlin 侧显式通过该接口把增量文本回调给上层 UI。
 * 工具调用/结果事件走 ToolEventSink，不在这里。
 */
interface ModelStreamSink {
    fun onResponseDelta(text: String)
    fun onReasoningDelta(text: String)

    companion object {
        val NOOP: ModelStreamSink = object : ModelStreamSink {
            override fun onResponseDelta(text: String) {}
            override fun onReasoningDelta(text: String) {}
        }
    }
}
