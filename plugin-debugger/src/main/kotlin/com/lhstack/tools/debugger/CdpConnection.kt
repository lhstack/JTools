package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 不依赖特定 JCEF 版本的 CDP 传输；仅连接当前沙箱回环端口。 */
internal class CdpEndpoint(private val port: Int) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    init {
        require(port in 1024..65535)
    }

    fun targets(): List<JsonObject> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/list"))
            .timeout(Duration.ofSeconds(5)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "CDP target 列表请求失败: ${response.statusCode()}" }
        return JsonParser.parseString(response.body()).asJsonArray.map { it.asJsonObject }
            .filter { it.get("type")?.asString == "page" }
    }

    fun connect(target: JsonObject): CdpConnection {
        val uri = URI(target.text("webSocketDebuggerUrl"))
        require(
            uri.scheme == "ws" && uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1") &&
                    uri.port == port && uri.rawUserInfo == null
        ) { "CDP target 地址不属于当前沙箱" }
        return CdpConnection(http, uri)
    }
}

internal class CdpConnection(http: HttpClient, uri: URI) : WebSocket.Listener, AutoCloseable {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val lifecycle = Any()
    private val records = ArrayDeque<Map<String, Any?>>()
    private var cursor = 0L
    private val message = StringBuilder()
    @Volatile
    private var failure: Throwable? = null
    private val socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        .buildAsync(uri, this).get(5, TimeUnit.SECONDS)

    private var sends: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    fun call(method: String, params: JsonObject = JsonObject()): JsonObject {
        val result = request(method, params)
        try {
            return result.get(20, TimeUnit.SECONDS)
        } catch (error: java.util.concurrent.ExecutionException) {
            throw (error.cause ?: error)
        } finally {
            // 同步等待结束只释放关联，不宣称取消浏览器内已发送的操作。
            result.cancel(false)
        }
    }

    /** 响应由 WebSocket 回调完成；只有发送阶段限时，不对断点暂停中的响应设超时。 */
    fun request(method: String, params: JsonObject = JsonObject()): CompletableFuture<JsonObject> {
        val id = ids.incrementAndGet()
        val response = CompletableFuture<JsonObject>()
        val result = response.thenApply { value ->
            check(!value.has("error")) { "CDP $method 失败: ${value.get("error")}" }
            value.getAsJsonObject("result") ?: error("CDP 响应缺少 result")
        }
        result.whenComplete { _, _ -> pending.remove(id, response) }
        val message = json.toJson(mapOf("id" to id, "method" to method, "params" to params))
        synchronized(lifecycle) {
            failure?.let { throw IllegalStateException("CDP 连接已断开", it) }
            pending[id] = response
            // JDK WebSocket 不允许重叠发送；串联发送，不串联协议响应或恢复命令。
            sends = sends.handle { _, _ -> Unit }.thenCompose {
                socket.sendText(message, true).orTimeout(5, TimeUnit.SECONDS)
            }
            sends.whenComplete { _, error ->
                if (error != null) response.completeExceptionally(error)
            }
        }
        return result
    }

    fun events(after: Long): Map<String, Any?> = synchronized(records) {
        val page = records.filter { (it["seq"] as Long) > after }.take(200)
        mapOf(
            "events" to page, "cursor" to (page.lastOrNull()?.get("seq") ?: after),
            "latest" to cursor, "oldest" to records.firstOrNull()?.get("seq")
        )
    }

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        try {
            check(message.length + data.length <= 16 * 1024 * 1024) { "CDP 消息超过 16 MiB" }
            message.append(data)
            if (last) {
                accept(JsonParser.parseString(message.toString()).asJsonObject)
                message.setLength(0)
            }
            webSocket.request(1)
        } catch (error: Throwable) {
            disconnected(error); webSocket.abort()
        }
        return null
    }

    private fun accept(response: JsonObject) {
        if (response.has("id")) {
            pending[response.get("id").asLong]?.complete(response)
            return
        }
        val method = response.text("method")
        // 网络事件可能包含凭据，不默认记录其 payload。
        val payload =
            if (method.startsWith("Network.")) json.toJsonTree(mapOf("redacted" to true)) else response.get("params")
        synchronized(records) {
            records.addLast(mapOf("seq" to ++cursor, "method" to method, "params" to payload))
            while (records.size > 1000) records.removeFirst()
        }
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        disconnected(IllegalStateException("CDP 已关闭: $statusCode $reason"))
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        disconnected(error)
    }

    private fun disconnected(error: Throwable) = synchronized(lifecycle) {
        if (failure == null) failure = error
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    override fun close() {
        disconnected(IllegalStateException("CDP 连接已释放"))
        socket.abort()
    }
}
