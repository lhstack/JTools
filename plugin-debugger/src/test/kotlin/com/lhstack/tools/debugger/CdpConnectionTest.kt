package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CdpConnectionTest {
    private class Target : WebSocketServer(InetSocketAddress("127.0.0.1", 0)), AutoCloseable {
        val ready = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        private val held = java.util.concurrent.ConcurrentHashMap<Long, WebSocket>()

        fun finishWaiting() {
            held.forEach { (id, connection) ->
                connection.send(json.toJson(mapOf("id" to id, "result" to mapOf("resumed" to true))))
            }
            held.clear()
        }

        init {
            isReuseAddr = true; start(); check(ready.await(5, TimeUnit.SECONDS))
        }

        fun connect() = CdpConnection(HttpClient.newHttpClient(), URI("ws://127.0.0.1:$port/devtools/page/test"))
        override fun onStart() {
            ready.countDown()
        }

        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) = Unit
        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) = Unit
        override fun onError(connection: WebSocket?, error: Exception) {
            throw error
        }

        override fun onMessage(connection: WebSocket, message: String) {
            val request = JsonParser.parseString(message).asJsonObject
            when (request.text("method")) {
                "wait" -> {
                    held[request.get("id").asLong] = connection
                    connection.send("""{"method":"Debugger.paused","params":{"reason":"test"}}""")
                    waiting.countDown()
                }
                "fail" -> connection.send(
                    json.toJson(
                        mapOf(
                            "id" to request.get("id"),
                            "error" to mapOf("message" to "test failure")
                        )
                    )
                )

                else -> {
                    connection.send("""{"method":"Network.requestWillBeSent","params":{"secret":"not logged"}}""")
                    connection.send("""{"method":"Debugger.paused","params":{"reason":"test"}}""")
                    connection.send(json.toJson(mapOf("id" to request.get("id"), "result" to mapOf("port" to port))))
                }
            }
        }

        override fun close() {
            stop(1000)
        }
    }

    @Test
    fun `websocket commands events errors and explicit disconnect are observable`() {
        Target().use { server ->
            server.connect().use { client ->
                assertEquals(server.port, client.call("Runtime.enable").get("port").asInt)
                val events = client.events(0).toString()
                assertContains(events, "Debugger.paused")
                assertContains(events, "redacted")
                assertFalse(events.contains("not logged"))
                assertFailsWith<IllegalStateException> { client.call("fail") }
                val pending = CompletableFuture.runAsync { client.call("wait") }
                assertTrue(server.waiting.await(5, TimeUnit.SECONDS))
                client.close()
                assertFails { pending.get(5, TimeUnit.SECONDS) }
                assertFailsWith<IllegalStateException> { client.call("Runtime.enable") }
            }
        }
    }

    @Test
    fun `closing one sandbox transport does not close another`() {
        Target().use { a ->
            Target().use { b ->
                a.connect().use { first ->
                    b.connect().use { second ->
                        val calls = listOf(
                            first,
                            second
                        ).map { client -> CompletableFuture.supplyAsync { client.call("Runtime.enable") } }
                        assertEquals(a.port, calls[0].get(5, TimeUnit.SECONDS).get("port").asInt)
                        assertEquals(b.port, calls[1].get(5, TimeUnit.SECONDS).get("port").asInt)
                        first.close()
                        assertEquals(b.port, second.call("Runtime.enable").get("port").asInt)
                    }
                }
            }
        }
    }

    @Test fun `async response survives a pause beyond twenty seconds while control commands remain available`() {
        Target().use { server ->
            server.connect().use { client ->
                val operations = CdpOperations()
                val submitted = operations.submit("panel", "wait") { client.request("wait") }
                val id = submitted["operation_id"] as String
                assertEquals("QUEUED", submitted["state"])
                assertTrue(server.waiting.await(5, TimeUnit.SECONDS))
                Thread.sleep(21_000)
                assertEquals("RUNNING", operations.snapshot(id)["state"])
                assertContains(client.events(0).toString(), "Debugger.paused")
                assertEquals(server.port, client.call("Debugger.resume").get("port").asInt)
                server.finishWaiting()
                awaitState(operations, id, "COMPLETED")
                assertTrue((operations.snapshot(id)["result"] as JsonObject).get("resumed").asBoolean)
            }
        }
    }

    @Test fun `async protocol errors and connection release finish operations without affecting other panels`() {
        Target().use { server ->
            server.connect().use { first ->
                server.connect().use { second ->
                    val operations = CdpOperations()
                    val firstId = operations.submit("one", "wait") { first.request("wait") }["operation_id"] as String
                    val secondId = operations.submit("two", "wait") { second.request("wait") }["operation_id"] as String
                    first.close()
                    awaitState(operations, firstId, "FAILED")
                    assertContains(operations.snapshot(firstId)["error"].toString(), "CDP 连接已释放")
                    assertEquals("RUNNING", operations.snapshot(secondId)["state"])
                    second.close()
                    awaitState(operations, secondId, "FAILED")
                }
            }
            server.connect().use { client ->
                val operations = CdpOperations()
                val id = operations.submit("three", "fail") { client.request("fail") }["operation_id"] as String
                awaitState(operations, id, "FAILED")
                assertContains(operations.snapshot(id)["error"].toString(), "test failure")
                val pending = client.request("wait")
                assertTrue(server.waiting.await(5, TimeUnit.SECONDS))
                server.connections.toList().forEach { it.close(1001, "test disconnect") }
                assertFails { pending.get(5, TimeUnit.SECONDS) }
            }
        }
    }

    private fun awaitState(operations: CdpOperations, id: String, state: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (operations.snapshot(id)["state"] != state) {
            check(System.nanoTime() < deadline) { "Expected $state: ${operations.snapshot(id)}" }
            Thread.sleep(10)
        }
    }

    @Test fun `discovery cannot redirect connection to external or different port`() {
        val endpoint = CdpEndpoint(5005)
        listOf("ws://example.com:5005/page", "ws://127.0.0.1:5006/page", "wss://127.0.0.1:5005/page").forEach { url ->
            assertFailsWith<IllegalArgumentException> {
                endpoint.connect(JsonObject().apply { addProperty("webSocketDebuggerUrl", url) })
            }
        }
    }
}
