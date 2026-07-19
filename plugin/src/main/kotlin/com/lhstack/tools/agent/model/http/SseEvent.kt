package com.lhstack.tools.agent.model.http

/**
 * SSE 事件。对齐 awake 使用的 eventsource_stream::Event：
 * - event：事件名（Anthropic 用，OpenAI 一般为空）
 * - data：数据负载（多行 data: 以换行拼接）
 */
data class SseEvent(
    val event: String,
    val data: String,
)
