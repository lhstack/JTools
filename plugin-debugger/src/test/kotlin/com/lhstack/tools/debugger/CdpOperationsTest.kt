package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.lhstack.tools.plugins.SandboxHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.swing.JComponent
import kotlin.test.*

class CdpOperationsTest {
    @TempDir lateinit var directory: Path

    @Test fun `submission preserves queued acknowledgement and completed cdp result`() {
        val operations = CdpOperations()
        val response = CompletableFuture<JsonObject>()
        val submitted = operations.submit("panel", "Input.dispatchMouseEvent") { response }
        val id = submitted["operation_id"] as String
        assertEquals("QUEUED", submitted["state"])
        assertEquals(false, submitted["business_completion_verified"])
        assertEquals("RUNNING", operations.snapshot(id)["state"])
        val result = JsonObject().apply { addProperty("value", "done") }
        response.complete(result)
        assertEquals("COMPLETED", operations.snapshot(id)["state"])
        assertEquals(result, operations.snapshot(id)["result"])
        assertEquals("QUEUED", submitted["state"])
    }

    @Test fun `send exceptions and response failures retain real errors and stacks`() {
        val operations = CdpOperations()
        val failedSend = operations.submit("a", "Runtime.evaluate") { error("socket unavailable") }
        assertContains(operations.snapshot(failedSend["operation_id"] as String)["error"].toString(), "socket unavailable")
        val response = CompletableFuture<JsonObject>()
        val submitted = operations.submit("b", "Runtime.evaluate") { response }
        response.completeExceptionally(java.util.concurrent.CompletionException(IllegalStateException("CDP error")))
        val status = operations.snapshot(submitted["operation_id"] as String)
        assertEquals("FAILED", status["state"])
        assertEquals("java.lang.IllegalStateException: CDP error", status["error"])
        assertContains(status["stack"].toString(), "CdpOperationsTest")
    }

    @Test fun `capacity rejects pending work and evicts only completed records`() {
        val operations = CdpOperations(2)
        val firstResponse = CompletableFuture<JsonObject>()
        val first = operations.submit("a", "wait") { firstResponse }["operation_id"] as String
        val second = operations.submit("b", "wait") { CompletableFuture() }["operation_id"] as String
        var sent = false
        assertFailsWith<IllegalStateException> {
            operations.submit("c", "wait") { sent = true; CompletableFuture() }
        }
        assertFalse(sent)
        firstResponse.complete(JsonObject())
        operations.submit("c", "wait") { CompletableFuture() }
        assertFailsWith<IllegalStateException> { operations.snapshot(first) }
        assertEquals("RUNNING", operations.snapshot(second)["state"])
        assertFailsWith<IllegalStateException> { CdpOperations().snapshot(second) }
    }

    @Test fun `operation query never accesses host or binds target`() {
        val host = object : SandboxHost {
            override fun execute(command: String, argumentsJson: String): String = error("must not execute")
            override fun panel(panelId: String): JComponent = error("must not access EDT")
            override fun browser(panelId: String): Any = error("must not bind target")
        }
        WebDebug(host, directory, 5006).use { web ->
            val error = assertFailsWith<IllegalStateException> {
                web.call("operation", JsonObject().apply { addProperty("operation_id", "unknown") })
            }
            assertContains(error.message.orEmpty(), "CDP 操作不存在")
        }
    }
}
