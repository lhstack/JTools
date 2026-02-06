package com.lhstack.tools.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.concurrency.AppExecutorUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ToolCallLog(
    val name: String,
    val arguments: String,
    val result: String,
)

data class ToolCallStreamEvent(
    val index: Int,
    val name: String,
    val arguments: String,
    val done: Boolean,
)

data class AgentCompletionResult(
    val assistantContent: String?,
    val toolCalls: List<ToolCallLog>,
    val errorMessage: String? = null,
)

class AgentClient {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMinutes(5))
        .build()
    private val toolExecutor = AppExecutorUtil.getAppExecutorService()

    companion object {
        private const val DEFAULT_MAX_TOOL_ITERATIONS = 5
        private const val MAX_REPEAT_TOOL_CALLS = 2
        private const val MAX_TOOL_RESULT_CHARS = 20_000
        private const val DEFAULT_TOOL_TIMEOUT_MS = 120_000L
    }

    fun complete(
        messages: MutableList<JsonObject>,
        toolRegistry: AgentToolRegistry,
        apiKey: String,
        baseUrl: String,
        model: String,
        onDelta: ((String) -> Unit)? = null,
        onReasoningDelta: ((String) -> Unit)? = null,
        onToolCall: ((ToolCallStreamEvent) -> Unit)? = null,
        onToolResult: ((ToolCallLog) -> Unit)? = null,
        maxToolIterations: Int = DEFAULT_MAX_TOOL_ITERATIONS,
        toolTimeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
    ): AgentCompletionResult {
        val toolCalls = mutableListOf<ToolCallLog>()
        val endpoint = normalizeEndpoint(baseUrl)
        var iterations = 0
        var lastToolSignature: String? = null
        var repeatedToolCalls = 0
        val maxIterations = if (maxToolIterations <= 0) DEFAULT_MAX_TOOL_ITERATIONS else maxToolIterations

        while (iterations < maxIterations) {
            val response = try {
                if (onDelta == null) {
                    requestChatCompletion(messages, toolRegistry.toolsJson(), apiKey, endpoint, model)
                } else {
                    requestChatCompletionStream(
                        messages,
                        toolRegistry.toolsJson(),
                        apiKey,
                        endpoint,
                        model,
                        onDelta,
                        onReasoningDelta,
                        onToolCall
                    )
                }
            } catch (e: Throwable) {
                return AgentCompletionResult(
                    assistantContent = null,
                    toolCalls = toolCalls,
                    errorMessage = e.message ?: "请求失败"
                )
            }

            val message = response.second ?: return AgentCompletionResult(
                assistantContent = null,
                toolCalls = toolCalls,
                errorMessage = response.first ?: "返回数据异常"
            )

            val toolCallsArray = message.getAsJsonArray("tool_calls")
            if (toolCallsArray != null && toolCallsArray.size() > 0) {
                val signature = buildToolSignature(toolCallsArray)
                if (signature != null) {
                    if (signature == lastToolSignature) {
                        repeatedToolCalls += 1
                    } else {
                        repeatedToolCalls = 0
                    }
                    lastToolSignature = signature
                    if (repeatedToolCalls >= MAX_REPEAT_TOOL_CALLS) {
                        return AgentCompletionResult(
                            assistantContent = null,
                            toolCalls = toolCalls,
                            errorMessage = "检测到连续相同工具调用，已终止。请提供更多信息或调整问题。"
                        )
                    }
                }
                messages.add(message)
                val toolResults = executeTools(
                    toolRegistry,
                    toolCallsArray,
                    toolCalls,
                    onToolResult,
                    toolTimeoutMs
                )
                toolResults.forEach { messages.add(it) }
                iterations++
                continue
            }

            val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString
            messages.add(message)
            return AgentCompletionResult(
                assistantContent = content,
                toolCalls = toolCalls
            )
        }

        return AgentCompletionResult(
            assistantContent = null,
            toolCalls = toolCalls,
            errorMessage = "函数调用次数过多(上限: $maxIterations)"
        )
    }

    private fun requestChatCompletion(
        messages: List<JsonObject>,
        tools: JsonArray,
        apiKey: String,
        endpoint: String,
        model: String,
    ): Pair<String?, JsonObject?> {
        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply { messages.forEach { add(it) } })
            add("tools", tools)
            addProperty("tool_choice", "auto")
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            return "请求失败: ${response.statusCode()} ${response.body()}" to null
        }
        val root = JsonParser.parseString(response.body()).asJsonObject
        if (root.has("error")) {
            return root.getAsJsonObject("error")?.get("message")?.asString to null
        }
        val choices = root.getAsJsonArray("choices")
            ?: return "未返回 choices" to null
        if (choices.size() == 0) {
            return "未返回 choices" to null
        }
        val message = choices[0].asJsonObject.getAsJsonObject("message")
            ?: return "未返回 message" to null
        return null to message
    }

    private fun requestChatCompletionStream(
        messages: List<JsonObject>,
        tools: JsonArray,
        apiKey: String,
        endpoint: String,
        model: String,
        onDelta: (String) -> Unit,
        onReasoningDelta: ((String) -> Unit)?,
        onToolCall: ((ToolCallStreamEvent) -> Unit)?,
    ): Pair<String?, JsonObject?> {
        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply { messages.forEach { add(it) } })
            add("tools", tools)
            addProperty("tool_choice", "auto")
            addProperty("stream", true)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() !in 200..299) {
            return "请求失败: ${response.statusCode()}" to null
        }

        val toolCalls = linkedMapOf<Int, ToolCallBuilder>()
        val assistantContent = StringBuilder()
        var role: String? = null
        response.body().use { lines ->
            val iterator = lines.iterator()
            while (iterator.hasNext()) {
                val line = iterator.next().trim()
                if (line.isEmpty()) {
                    continue
                }
                if (!line.startsWith("data:")) {
                    continue
                }
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    break
                }
                val chunk = try {
                    JsonParser.parseString(data).asJsonObject
                } catch (_: Throwable) {
                    continue
                }
                if (chunk.has("error")) {
                    val message = chunk.getAsJsonObject("error")?.get("message")?.asString
                    return message to null
                }
                val choices = chunk.getAsJsonArray("choices") ?: continue
                if (choices.size() == 0) {
                    continue
                }
                val choice = choices[0].asJsonObject
                val delta = choice.getAsJsonObject("delta") ?: continue
                val roleDelta = delta.get("role")?.asString
                if (!roleDelta.isNullOrBlank()) {
                    role = roleDelta
                }
                val reasoningDelta = delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                    ?: delta.get("reasoning")?.takeIf { !it.isJsonNull }?.asString
                if (!reasoningDelta.isNullOrEmpty()) {
                    onReasoningDelta?.invoke(reasoningDelta)
                }
                val contentDelta = delta.get("content")?.takeIf { !it.isJsonNull }?.asString
                if (!contentDelta.isNullOrEmpty()) {
                    assistantContent.append(contentDelta)
                    onDelta.invoke(contentDelta)
                }
                val toolCallsDelta = delta.getAsJsonArray("tool_calls")
                if (toolCallsDelta != null) {
                    toolCallsDelta.forEach { element ->
                        val callDelta = element.asJsonObject
                        val index = callDelta.get("index")?.asInt ?: 0
                        val builder = toolCalls.getOrPut(index) { ToolCallBuilder() }
                        callDelta.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { builder.id = it }
                        val function = callDelta.getAsJsonObject("function")
                        val nameFromFunction = function?.get("name")?.takeIf { !it.isJsonNull }?.asString
                        val nameFromDelta = callDelta.get("name")?.takeIf { !it.isJsonNull }?.asString
                        val resolvedName = nameFromFunction ?: nameFromDelta
                        if (!resolvedName.isNullOrBlank()) {
                            builder.name = resolvedName
                            if (!builder.started) {
                                builder.started = true
                                onToolCall?.invoke(
                                    ToolCallStreamEvent(index, builder.name.orEmpty(), builder.arguments.toString(), false)
                                )
                            }
                        }
                        function?.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let {
                            builder.arguments.append(it)
                        }
                    }
                }
            }
        }

        if (toolCalls.isNotEmpty()) {
            toolCalls.toSortedMap().forEach { (index, builder) ->
                if (!builder.name.isNullOrBlank()) {
                    onToolCall?.invoke(
                        ToolCallStreamEvent(index, builder.name.orEmpty(), builder.arguments.toString(), true)
                    )
                }
            }
        }

        val message = JsonObject().apply {
            addProperty("role", role ?: "assistant")
            if (assistantContent.isNotEmpty()) {
                addProperty("content", assistantContent.toString())
            }
            if (toolCalls.isNotEmpty()) {
                val toolArray = JsonArray()
                toolCalls.toSortedMap().forEach { (_, builder) ->
                    toolArray.add(builder.toJson())
                }
                add("tool_calls", toolArray)
            }
        }
        return null to message
    }

    private fun executeTools(
        toolRegistry: AgentToolRegistry,
        toolCallsArray: JsonArray,
        toolLogs: MutableList<ToolCallLog>,
        onToolResult: ((ToolCallLog) -> Unit)?,
        toolTimeoutMs: Long,
    ): List<JsonObject> {
        val toolMessages = mutableListOf<JsonObject>()
        toolCallsArray.forEach { element ->
            val call = element.asJsonObject
            val callId = call.get("id")?.asString.orEmpty()
            val function = call.getAsJsonObject("function")
            val name = function?.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: call.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: ""
            val arguments = function?.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val tool = toolRegistry.findTool(name)
            val result = if (tool == null) {
                """{"ok":false,"error":"未找到函数: $name"}"""
            } else {
                runToolWithTimeout(tool, arguments, toolTimeoutMs)
            }
            val safeResult = truncateForModel(result, MAX_TOOL_RESULT_CHARS)
            val toolLog = ToolCallLog(name, arguments, safeResult)
            toolLogs.add(toolLog)
            onToolResult?.invoke(toolLog)
            toolMessages.add(
                JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", callId)
                    addProperty("content", safeResult)
                }
            )
        }
        return toolMessages
    }

    private fun runToolWithTimeout(tool: AgentTool, arguments: String, timeoutMs: Long): String {
        if (timeoutMs <= 0) {
            return try {
                tool.call(arguments)
            } catch (e: Throwable) {
                """{"ok":false,"error":"调用失败: ${e.message ?: "unknown"}"}"""
            }
        }
        val future = CompletableFuture.supplyAsync({ tool.call(arguments) }, toolExecutor)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            """{"ok":false,"error":"工具执行超时(${timeoutMs}ms)"}"""
        } catch (e: ExecutionException) {
            val message = e.cause?.message ?: "unknown"
            """{"ok":false,"error":"调用失败: $message"}"""
        } catch (e: Throwable) {
            """{"ok":false,"error":"调用失败: ${e.message ?: "unknown"}"}"""
        }
    }

    private fun normalizeEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().ifBlank { "https://api.openai.com/v1" }
        val normalized = trimmed.trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
    }

    private fun truncateForModel(value: String, maxChars: Int): String {
        if (value.length <= maxChars) {
            return value
        }
        return value.take(maxChars) + "...(truncated, maxChars=$maxChars, length=${value.length})"
    }

    private fun buildToolSignature(toolCallsArray: JsonArray): String? {
        val signatureParts = mutableListOf<String>()
        toolCallsArray.forEach { element ->
            val call = element.asJsonObject
            val function = call.getAsJsonObject("function")
            val name = function?.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: call.get("name")?.takeIf { !it.isJsonNull }?.asString
                ?: ""
            if (name.isBlank()) {
                return null
            }
            val arguments = function?.get("arguments")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            signatureParts.add("$name:$arguments")
        }
        return signatureParts.joinToString("|")
    }

    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val arguments: StringBuilder = StringBuilder()
        var started: Boolean = false

        fun toJson(): JsonObject {
            val function = JsonObject().apply {
                addProperty("name", name.orEmpty())
                addProperty("arguments", arguments.toString())
            }
            return JsonObject().apply {
                addProperty("id", id.orEmpty())
                addProperty("type", "function")
                add("function", function)
            }
        }
    }
}
