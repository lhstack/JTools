package com.lhstack.tools.agent.model.provider

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.lhstack.tools.concurrent.AgentExecutors
import com.lhstack.tools.agent.model.llm.ProviderToolCall
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import com.lhstack.tools.agent.model.llm.ToolResult
import com.lhstack.tools.agent.model.llm.ToolResultContent
import com.lhstack.tools.agent.model.llm.UserContent
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

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
) {

    /** 对齐 execute_tool_calls：先发 tool_call 事件，并发执行，再发 tool_result 事件。 */
    fun executeToolCalls(calls: List<ProviderToolCall>): List<UserContent> {
        emitToolCalls(calls)
        val results = callTools(calls)
        emitToolResults(results)
        return results
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
        if (calls.isEmpty()) {
            return emptyList()
        }
        if (calls.size == 1) {
            return listOf(executeToolCall(calls[0]))
        }
        val futures = calls.map { call ->
            AgentExecutors.shared.submit(Callable { executeToolCall(call) })
        }
        val cancelFutures = { futures.forEach { it.cancel(true) }; Unit }
        val toolInterruptId = toolCancel?.registerInterrupt(cancelFutures)
        val conversationInterruptId = conversationCancel?.registerInterrupt(cancelFutures)
        try {
            return futures.map { future ->
                try {
                    future.get()
                } catch (_: CancellationException) {
                    "用户手动取消"
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

    /** 照抄 execute_tool_call：按名找工具，未注册返回错误文本，异常包成错误文本。 */
    private fun executeToolCall(call: ProviderToolCall): String {
        val index = definitions.indexOfFirst { it.name == call.name }
        if (index < 0) {
            return "工具调用失败: 模型请求了未注册的工具 `${call.name}`"
        }
        return try {
            val output = stringifyToolOutput(tools[index].callJsonBlocking(call.arguments))
            if (toolCancel?.isCancelled() == true) "用户手动取消" else output
        } catch (e: Throwable) {
            if (toolCancel?.isCancelled() == true) {
                "用户手动取消"
            } else {
                "工具调用失败: ${describeThrowable(e)}"
            }
        }
    }

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

    companion object {
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

/** 工具调用/结果记录回调，对齐 awake 的 TraceHook.on_tool_call / on_tool_result。 */
interface ToolHook {
    fun onToolCall(name: String, callId: String?, internalId: String, args: String)
    fun onToolResult(name: String, callId: String?, internalId: String, args: String, result: String)

    companion object {
        val NOOP: ToolHook = object : ToolHook {
            override fun onToolCall(name: String, callId: String?, internalId: String, args: String) {}
            override fun onToolResult(
                name: String, callId: String?, internalId: String, args: String, result: String,
            ) {}
        }
    }
}

/** 工具调用/结果流式事件回调，对齐 awake 的 emit_stream_event("tool_call"/"tool_result")。 */
interface ToolEventSink {
    fun onToolCall(call: ProviderToolCall)
    fun onToolResult(result: ToolResult)
}
