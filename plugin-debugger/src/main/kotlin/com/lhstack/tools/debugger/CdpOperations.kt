package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference

/** 沙箱内的 CDP 操作记录；只持有结果，不创建阻塞等待线程。 */
internal class CdpOperations(private val capacity: Int = 1000) {
    private class Operation(val panel: String, val method: String) {
        val id = UUID.randomUUID().toString()
        val outcome = AtomicReference<Map<String, Any?>>(mapOf("state" to "QUEUED"))

        fun snapshot(): Map<String, Any?> = mapOf(
            "operation_id" to id, "panel_id" to panel, "method" to method,
            "business_completion_verified" to false
        ) + outcome.get()

        fun complete(result: JsonObject?, failure: Throwable?) {
            if (failure == null) {
                outcome.set(mapOf("state" to "COMPLETED", "result" to checkNotNull(result)))
                return
            }
            var cause = failure
            while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) {
                cause = cause.cause!!
            }
            outcome.set(mapOf("state" to "FAILED", "error" to cause.toString(), "stack" to cause.stackTraceToString()))
        }
    }

    private val operations = linkedMapOf<String, Operation>()

    init { require(capacity > 0) }

    @Synchronized
    fun submit(panel: String, method: String, send: () -> CompletableFuture<JsonObject>): Map<String, Any?> {
        if (operations.size >= capacity) {
            val expired = operations.entries.firstOrNull {
                it.value.outcome.get()["state"] in setOf("COMPLETED", "FAILED")
            } ?: error("待完成 CDP 操作过多，请先恢复或关闭面板")
            operations.remove(expired.key)
        }
        val operation = Operation(panel, method)
        operations[operation.id] = operation
        val submitted = operation.snapshot()
        operation.outcome.set(mapOf("state" to "RUNNING"))
        try {
            send().whenComplete { result, failure -> operation.complete(result, failure) }
        } catch (error: Exception) {
            operation.complete(null, error)
        }
        return submitted
    }

    @Synchronized
    fun snapshot(id: String): Map<String, Any?> =
        (operations[id] ?: error("CDP 操作不存在或记录已过期")).snapshot()
}
