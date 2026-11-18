package com.lhstack.tools.llm.provider

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.tools.BashTool
import com.lhstack.tools.agent.model.tools.WebFetchTool
import com.lhstack.tools.agent.model.tools.WorkspaceTools
import com.lhstack.tools.llm.*
import com.lhstack.tools.llm.anthropic.AnthropicMessages
import com.lhstack.tools.llm.openai.OpenAiMessages
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class ToolTextOutcomeTest {
    @Test
    fun `real command success and failure preserve stdout and stderr with separate status`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        withWorkspace { workspace ->
            val tool = BashTool(workspace)
            fun call(id: String, command: String) = ProviderToolCall(id, id, "bash", JsonObject().apply { addProperty("command", command) })
            val trace = TraceHook()
            val history = ToolRuntime(listOf(tool.definition("")), listOf(tool), trace, null).executeToolCalls(listOf(
                call("good", "printf '%s' '{\"value\":1}'"),
                call("bad", "printf warning >&2; exit 7"),
            )).historyMessages
            val results = results(history)
            assertEquals(listOf("good", "bad"), results.map { it.id })
            assertEquals(listOf(ToolExecutionStatus.COMPLETED, ToolExecutionStatus.FAILED), results.map { it.status })
            assertEquals("stdout:\n{\"value\":1}", text(results[0]))
            assertContains(text(results[1]), "退出码：7")
            assertContains(text(results[1]), "stderr:\nwarning")
            assertEquals(results.map { it.status }, trace.events().filterIsInstance<TraceEvent.ToolResult>().map { it.status })
            assertEquals(results, results(ProviderMessageHistory.fromJson(ProviderMessageHistory.toJson(history))))
            val chat = OpenAiMessages.chatMessages("", history)
            assertEquals(text(results[1]), chat[1].asJsonObject.get("content").asString)
            val responses = OpenAiMessages.responseInput(history)
            assertEquals(text(results[1]), responses[1].asJsonObject.get("output").asString)
            val anthropic = AnthropicMessages.messagesFromRuntime("", history).second.single().getAsJsonArray("content")
            assertFalse(anthropic[0].asJsonObject.has("is_error"))
            assertTrue(anthropic[1].asJsonObject.get("is_error").asBoolean)
        }
    }

    @Test
    fun `HTTP error keeps raw response text and pre-cancelled dispatch never reaches server`() {
        withWorkspace { workspace ->
            val requests = java.util.concurrent.atomic.AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                val bytes = "{\"error\":\"unavailable\"}".toByteArray()
                exchange.sendResponseHeaders(503, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val token = ModelCancel()
                val tool = WebFetchTool(workspace, null, 5, 4096, token)
                val runtime = ToolRuntime(listOf(tool.definition("")), listOf(tool), ToolHook.NOOP, null, toolCancel = token)
                val call = ProviderToolCall("http", "http", "web_fetch", JsonObject().apply {
                    addProperty("url", "http://127.0.0.1:${server.address.port}/")
                })
                val failed = results(runtime.executeToolCalls(listOf(call)).historyMessages).single()
                assertEquals(ToolExecutionStatus.FAILED, failed.status)
                assertContains(text(failed), "状态码：503")
                assertTrue(text(failed).endsWith("{\"error\":\"unavailable\"}"))
                token.cancel()
                val cancelled = results(runtime.executeToolCalls(listOf(call)).historyMessages).single()
                assertEquals(ToolExecutionStatus.CANCELLED, cancelled.status)
                assertEquals("用户手动取消", text(cancelled))
                assertEquals(1, requests.get())
            } finally { server.stop(0) }
        }
    }

    private fun results(messages: List<Message>) = (messages.single() as Message.User).content.map { (it as UserContent.ToolResult).toolResult }
    private fun text(result: ToolResult) = (result.content.single() as ToolResultContent.Text).text
    private fun withWorkspace(block: (WorkspaceTools) -> Unit) {
        val root = Files.createTempDirectory("jtools-text-outcome-")
        try { block(WorkspaceTools(root.toString())) } finally { root.toFile().deleteRecursively() }
    }
}
