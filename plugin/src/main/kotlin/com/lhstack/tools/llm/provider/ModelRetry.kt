package com.lhstack.tools.llm.provider

import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.http.ModelRequestCancelledException

const val DEFAULT_MODEL_RETRY_INTERVAL_MS: Long = 2000

fun waitBeforeModelRetry(
    eventSink: ToolEventSink?,
    error: Throwable,
    cancel: ModelCancel?,
    retryIntervalMs: Long,
) {
    eventSink?.onModelRetry(error.message ?: error.toString())
    if (retryIntervalMs <= 0) return
    if (cancel == null) {
        Thread.sleep(retryIntervalMs)
        return
    }
    val monitor = Object()
    val interruptId = cancel.registerInterrupt {
        synchronized(monitor) { monitor.notifyAll() }
    }
    try {
        val deadline = System.currentTimeMillis() + retryIntervalMs
        synchronized(monitor) {
            while (!cancel.isCancelled()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                monitor.wait(remaining)
            }
        }
        if (cancel.isCancelled()) throw ModelRequestCancelledException()
    } finally {
        cancel.clearInterrupt(interruptId)
    }
}
