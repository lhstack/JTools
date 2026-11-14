package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.lhstack.tools.plugins.SandboxHost
import com.lhstack.tools.plugins.SandboxRuntime
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 只在沙箱进程装载；控制通道不能用来接收任意外部文件路径。 */
class SandboxServer : SandboxRuntime {
    override fun start(project: Project, host: SandboxHost) {
        check(java.lang.Boolean.getBoolean("jtools.sandbox"))
        val root = Path.of(System.getProperty("jtools.sandbox.root"))
        val token = Files.readString(root.resolve("token"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "jtools-sandbox-rpc").apply { isDaemon = true } }
        server.executor = workers
        val swing = SwingAutomation(host, root.resolve("evidence"))
        val web = WebDebug(host, root.resolve("evidence"), System.getProperty("jtools.cdp.port").toInt())
        val operations = java.util.concurrent.locks.ReentrantLock()
        server.createContext("/rpc") { exchange ->
            var locked = false
            try {
                require(exchange.requestMethod == "POST") { "只接受 POST" }
                val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                if (!MessageDigest.isEqual(authorization.toByteArray(), "Bearer $token".toByteArray())) {
                    exchange.sendResponseHeaders(403, -1)
                } else {
                    val bytes = exchange.requestBody.readNBytes(1_048_577)
                    require(bytes.size <= 1_048_576) { "请求超过 1 MiB" }
                    val request = json.fromJson(String(bytes, Charsets.UTF_8), JsonObject::class.java)
                    val command = request.text("command")
                    val args = request.getAsJsonObject("arguments") ?: JsonObject()
                    if (!command.startsWith("js.") && command != "ping" && command != "swing.operation") {
                        check(operations.tryLock()) { "沙箱有未结束操作（包括超时或断点暂停的操作）" }
                        locked = true
                    }
                    val result: Any? = when {
                        command == "ping" -> mapOf("ready" to true, "project" to project.basePath)
                        command == "startup.ui" -> {
                            val dialogs = mutableListOf<String>()
                            javax.swing.SwingUtilities.invokeAndWait {
                                java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>()
                                    .filter { it.isShowing && it.isModal }.forEach { dialogs.add(it.title.orEmpty()) }
                            }
                            mapOf("modal_dialogs" to dialogs)
                        }
                        command.startsWith("swing.") -> swing.call(command.removePrefix("swing."), args)
                        command.startsWith("js.") -> web.call(command.removePrefix("js."), args)
                        else -> {
                            if (command == "panel.close") web.release(args.text("panel_id"))
                            if (command == "stop" || command == "run") { web.close(); swing.invalidate() }
                            com.google.gson.JsonParser.parseString(host.execute(command, args.toString()))
                        }
                    }
                    val output = json.toJson(mapOf("ok" to true, "result" to result)).toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                    exchange.sendResponseHeaders(200, output.size.toLong())
                    exchange.responseBody.write(output)
                }
            } catch (error: Throwable) {
                val output = json.toJson(mapOf("ok" to false, "error" to error.toString(), "stack" to error.stackTraceToString())).toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(500, output.size.toLong())
                exchange.responseBody.write(output)
            } finally { if (locked) operations.unlock(); exchange.close() }
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop(0); workers.shutdownNow(); web.close()
        })
        server.start()
        Files.writeString(root.resolve("endpoint"), server.address.port.toString())
    }
}
