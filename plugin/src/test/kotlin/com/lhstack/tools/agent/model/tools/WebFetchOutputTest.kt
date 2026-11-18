package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.llm.ToolExecutionStatus
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WebFetchOutputTest {
    @Test
    fun `bodies at and below limit stay inline unchanged`() {
        val text = "首行\n\n  尾行\r\n"
        val bytes = text.toByteArray()
        withServer({ it.sendBody(200, bytes) }) { url ->
            for (limit in listOf(bytes.size, bytes.size + 1)) {
                val result = WebFetchTool(workspace, null, 5, limit.toLong()).executeBlocking(request(url))
                assertEquals(ToolExecutionStatus.COMPLETED, result.status)
                assertEquals(text, result.result.asJsonObject["body"].asString)
                assertTrue(result.text.endsWith("\n\n$text"))
                assertFalse(result.result.asJsonObject.has("path"))
            }
        }
    }

    @Test
    fun `oversized chunked response keeps exact bytes and has UTF-8 safe head and tail`() {
        val count = AtomicInteger()
        val bytes = ("甲乙" + "中".repeat(4096) + "丙丁").toByteArray()
        withServer({ exchange ->
            count.incrementAndGet()
            exchange.sendResponseHeaders(503, 0)
            exchange.responseBody.use { output -> bytes.asList().chunked(1024).forEach { output.write(it.toByteArray()) } }
        }) { url ->
            val result = WebFetchTool(workspace, null, 5, 9).executeBlocking(request(url))
            val data = result.result.asJsonObject
            val path = Path.of(data["path"].asString)
            try {
                assertEquals(ToolExecutionStatus.FAILED, result.status)
                assertTrue(path.isAbsolute)
                assertContentEquals(bytes, Files.readAllBytes(path))
                assertEquals(bytes.size.toLong(), data["bytes_read"].asLong)
                assertTrue(data["truncated"].asBoolean)
                assertContains(result.text, "状态码：503")
                assertContains(result.text, "\n\n甲\n\n[响应正文超过 9 字节")
                assertContains(result.text, "\n\n丁\n\n完整输出文件：$path")
                assertFalse('�' in result.text)
                assertEquals(1, count.get())
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }

    @Test
    fun `deadline covers body and manual cancellation interrupts a pending response`() {
        for (cancelManually in listOf(false, true)) {
            val started = CountDownLatch(1)
            val stop = CountDownLatch(1)
            val token = ModelCancel()
            withServer({ exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use {
                    it.write("partial".toByteArray())
                    it.flush()
                    started.countDown()
                    stop.await(8, TimeUnit.SECONDS)
                }
            }) { url ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val tool = WebFetchTool(workspace, null, if (cancelManually) 10 else 1, 3, token)
                    val error = executor.submit<Throwable?> {
                        runCatching { tool.executeBlocking(request(url)) }.exceptionOrNull()
                    }
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    if (cancelManually) token.cancel()
                    if (cancelManually) assertIs<ToolCancelledException>(error.get(4, TimeUnit.SECONDS))
                    else assertIs<HttpTimeoutException>(error.get(4, TimeUnit.SECONDS))
                } finally {
                    stop.countDown()
                    executor.shutdownNow()
                }
            }
        }
    }

    private fun request(url: String) = JsonObject().apply { addProperty("url", url) }

    private fun HttpExchange.sendBody(status: Int, bytes: ByteArray) {
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun withServer(handler: (HttpExchange) -> Unit, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.use(handler) }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/")
        } finally {
            server.stop(0)
        }
    }

    private val workspace = WorkspaceTools(System.getProperty("java.io.tmpdir"))
}
