package com.lhstack.tools.llm.provider

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.lhstack.tools.concurrent.AgentExecutors
import com.lhstack.tools.llm.ProviderToolCall
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn
import com.lhstack.tools.llm.ToolResult
import com.lhstack.tools.llm.ToolResultContent
import com.lhstack.tools.llm.UserContent
import com.lhstack.tools.agent.model.tools.ToolOutputLimit
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 工具调用运行时。完全照抄 awake-claw 的 ToolRuntime（src/service/model_provider.rs）。
 *
 * awake 用 tokio 的 join_all 并发执行同轮工具调用并保序返回；Kotlin 侧改用
 * AgentExecutors 的（优先虚拟）线程池提交任务，再按原顺序收集结果，语义一致。
 *
 * 流式事件（tool_call / tool_result）通过 ToolEventSink 回调发出，对齐 awake 的
 * emit_stream_event；工具调用/结果记录通过 ToolHook 回调，对齐 TraceHook。
 */
class ToolRuntime(
    private val definitions: List<ToolDefinition>,
    private val tools: List<ToolDyn>,
    private val hook: ToolHook,
    private val eventSink: ToolEventSink?,
    private val toolCancel: com.lhstack.tools.agent.model.http.ModelCancel? = null,
    private val conversationCancel: com.lhstack.tools.agent.model.http.ModelCancel? = null,
    private val toolCancelSlot: java.util.concurrent.atomic.AtomicReference<com.lhstack.tools.agent.model.http.ModelCancel>? = null,
) {

    /** 对齐 execute_tool_calls：先发 tool_call 事件，并发执行，再发 tool_result 事件。 */
    fun executeToolCalls(calls: List<ProviderToolCall>): List<UserContent> {
        emitToolCalls(calls)
        val batch = beginToolBatch()
        try {
            val results = callTools(calls)
            emitToolResults(results)
            return results
        } finally {
            batch.close()
        }
    }

    private fun beginToolBatch(): AutoCloseable {
        val slot = toolCancelSlot ?: return AutoCloseable {}
        val token = com.lhstack.tools.agent.model.http.ModelCancel()
        slot.set(token)
        return AutoCloseable { slot.compareAndSet(token, null) }
    }

    private fun callTools(calls: List<ProviderToolCall>): List<UserContent> {
        recordToolCalls(calls)
        val outputs = executeToolCallsConcurrently(calls)
        recordToolResults(calls, outputs)
        return toolResults(calls, outputs)
    }

    private fun recordToolCalls(calls: List<ProviderToolCall>) {
        for (call in calls) {
            hook.onToolCall(call.name, call.callId, call.id, call.argsString())
        }
    }

    /**
     * 照抄 execute_tool_calls_concurrently：同轮多个工具并发执行，保序返回。
     * 用共享线程池提交，futures 按提交顺序 get，等价 join_all 的保序语义。
     */
    private fun executeToolCallsConcurrently(calls: List<ProviderToolCall>): List<String> {
        if (calls.isEmpty()) return emptyList()
        val pending = calls.map { call ->
            val index = definitions.indexOfFirst { it.name == call.name }
            val timeoutSeconds = timeoutSecondsFor(call, index)
            PendingTool(call, timeoutSeconds, AgentExecutors.shared.submit(Callable { executeToolCall(call) }))
        }
        val futures = pending.map { it.future }
        val cancelFutures = { futures.forEach { it.cancel(true) }; Unit }
        val toolInterruptId = toolCancel?.registerInterrupt(cancelFutures)
        val conversationInterruptId = conversationCancel?.registerInterrupt(cancelFutures)
        val deadlines = pending.associate { it.future to System.nanoTime() + TimeUnit.SECONDS.toNanos(it.timeoutSeconds) }
        try {
            return pending.map { invocation ->
                try {
                    val remaining = deadlines.getValue(invocation.future) - System.nanoTime()
                    if (remaining <= 0L) throw TimeoutException()
                    invocation.future.get(remaining, TimeUnit.NANOSECONDS)
                } catch (_: CancellationException) {
                    if (isCancellationRequested()) "用户手动取消"
                    else "工具调用失败: `${invocation.call.name}` 被执行器取消"
                } catch (_: TimeoutException) {
                    cancelFutures()
                    if (isCancellationRequested()) {
                        "用户手动取消"
                    } else {
                        "工具调用失败: `${invocation.call.name}` 超过 ${invocation.timeoutSeconds} 秒未完成"
                    }
                } catch (error: ExecutionException) {
                    val cause = error.cause ?: error
                    "工具调用失败: ${describeThrowable(cause)}"
                }
            }
        } finally {
            toolInterruptId?.let { toolCancel?.clearInterrupt(it) }
            conversationInterruptId?.let { conversationCancel?.clearInterrupt(it) }
        }
    }

    private fun recordToolResults(calls: List<ProviderToolCall>, outputs: List<String>) {
        for ((call, output) in calls.zip(outputs)) {
            hook.onToolResult(call.name, call.callId, call.id, call.argsString(), output)
        }
    }

    private fun timeoutSecondsFor(call: ProviderToolCall, index: Int): Long {
        val declared = if (index >= 0) tools[index].executionTimeoutSeconds else 120L
        val requested = requestedTimeoutSeconds(call)
        return maxOf(declared, requested).coerceAtLeast(1L)
    }

    /** 工具参数里的 timeout_secs 若更长，外层必须让内部超时先返回。 */
    private fun requestedTimeoutSeconds(call: ProviderToolCall): Long {
        val args = call.arguments.takeIf { it.isJsonObject }?.asJsonObject ?: return 0L
        val value = args.get("timeout_secs") ?: return 0L
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return 0L
        val seconds = value.asLong
        if (seconds <= 0L) return 0L
        return seconds + 10L
    }

    /** 照抄 execute_tool_call：按名找工具，未注册返回错误文本，异常包成错误文本。 */
    private fun executeToolCall(call: ProviderToolCall): String {
        val index = definitions.indexOfFirst { it.name == call.name }
        if (index < 0) {
            return "工具调用失败: 模型请求了未注册的工具 `${call.name}`"
        }
        return try {
            val rawOutput = stringifyToolOutput(tools[index].callJsonBlocking(call.arguments))
            val output = if (call.name in LIMITED_OUTPUT_TOOLS) {
                ToolOutputLimit.truncateToLimit(call.name, rawOutput)
            } else {
                rawOutput
            }
            if (isCancellationRequested()) "用户手动取消" else output
        } catch (e: Throwable) {
            if (isCancellationRequested()) {
                "用户手动取消"
            } else {
                "工具调用失败: ${describeThrowable(e)}"
            }
        }
    }

    private fun isCancellationRequested(): Boolean =
        toolCancel?.isCancelled() == true ||
            conversationCancel?.isCancelled() == true ||
            toolCancelSlot?.get()?.isCancelled() == true

 private fun emitToolCalls(calls: List<ProviderToolCall>) {
        val sink = eventSink ?: return
        for (call in calls) {
            sink.onToolCall(call)
        }
    }

    private fun emitToolResults(results: List<UserContent>) {
        val sink = eventSink ?: return
        for (result in results) {
            val toolResult = (result as? UserContent.ToolResult)?.toolResult ?: continue
            sink.onToolResult(toolResult)
        }
    }

    private data class PendingTool(
        val call: ProviderToolCall,
        val timeoutSeconds: Long,
        val future: java.util.concurrent.Future<String>,
    )

    companion object {
        private val LIMITED_OUTPUT_TOOLS = setOf("search_project_text", "find_project_files", "find_project_classes", "bash")

        /** 照抄 tool_results：把 outputs 按 call 顺序包成 ToolResult UserContent。 */
        private fun toolResults(calls: List<ProviderToolCall>, outputs: List<String>): List<UserContent> =
            calls.zip(outputs).map { (call, output) -> toolResult(call, output) }

        private fun toolResult(call: ProviderToolCall, output: String): UserContent =
            UserContent.ToolResult(
                ToolResult(
                    id = call.id,
                    callId = call.callId,
                    content = listOf(ToolResultContent.text(output)),
                )
            )

        /**
         * 展开异常的 cause 链拼接可读信息。部分库（如 MCP SDK）把真实失败原因包在外层
         * 包装异常里（例如 "Client failed to initialize by explicit API call"），只取顶层
         * message 会掩盖根因，这里把整条 cause 链串起来暴露给模型和用户。
         */
        private fun describeThrowable(error: Throwable): String {
            val messages = LinkedHashSet<String>()
            var current: Throwable? = error
            while (current != null) {
                val text = current.message?.trim().takeUnless { it.isNullOrBlank() }
                    ?: current.javaClass.simpleName
                messages.add(text)
                current = current.cause.takeIf { it !== current }
            }
            return messages.joinToString(" -> ")
        }

        /** 照抄 stringify_tool_output：null -> ""，字符串取原文，其余 toString。 */
        private fun stringifyToolOutput(value: JsonElement): String = when {
            value.isJsonNull -> ""
            value is JsonPrimitive && value.isString -> value.asString
            else -> value.toString()
        }
    }

}
