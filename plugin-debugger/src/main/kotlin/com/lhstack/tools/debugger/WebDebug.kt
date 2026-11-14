package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.lhstack.tools.plugins.SandboxHost
import org.cef.browser.CefBrowser
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

internal class WebDebug(private val host: SandboxHost, private val evidence: Path, port: Int) : AutoCloseable {
    private class Target(val browser: CefBrowser, val client: CdpConnection)

    private val endpoint = CdpEndpoint(port)
    private val targets = mutableMapOf<String, Target>()
    private val operations = CdpOperations()

    @Synchronized
    private fun target(panel: String): Target {
        val browser = host.browser(panel) as CefBrowser
        val existing = targets[panel]
        if (existing != null) {
            check(existing.browser === browser) { "浏览器已失效" }
            return existing
        }
        // 由宿主公开浏览器执行唯一挑战，不用 URL、标题或列表顺序猜测 target。
        val marker = "__jtools_debug_${UUID.randomUUID().toString().replace("-", "")}"
        browser.executeJavaScript(
            "Object.defineProperty(globalThis, '$marker', {value:true, configurable:true})",
            browser.url,
            0
        )
        val clients = mutableListOf<CdpConnection>()
        try {
            val candidates = endpoint.targets().map { endpoint.connect(it).also(clients::add) }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            var matches: List<CdpConnection>
            do {
                // executeJavaScript 是异步提交；等待挑战执行完成，不把零匹配当作其他页面。
                browser.executeJavaScript("Object.defineProperty(globalThis, '$marker', {value:true, configurable:true})", browser.url, 0)
                matches = candidates.filter { client ->
                    val result = client.call("Runtime.evaluate", JsonObject().apply {
                        addProperty("expression", "globalThis['$marker'] === true")
                        addProperty("returnByValue", true)
                    })
                    result.getAsJsonObject("result")?.get("value")?.asBoolean == true
                }
                if (matches.isNotEmpty()) break
                Thread.sleep(25)
            } while (System.nanoTime() < deadline)
            check(matches.size == 1) { "面板 CDP target 绑定失败（匹配 ${matches.size} 个），请等待页面加载完成后重试" }
            val client = matches.single()
            candidates.filter { it !== client }.forEach { it.close() }
            clients.clear()
            return Target(browser, client).also { targets[panel] = it }
        } finally {
            clients.forEach { it.close() }
            browser.executeJavaScript("delete globalThis['$marker']", browser.url, 0)
        }
    }

    fun call(command: String, args: JsonObject): Any {
        // 查询历史操作不访问宿主、EDT 或 target；关闭面板后仍可读取最终失败原因。
        if (command == "operation") return operations.snapshot(args.text("operation_id"))
        val target = target(args.text("panel_id"))
        return when (command) {
            "cdp" -> {
                val method = args.text("method")
                require(
                    method.substringBefore('.') in setOf(
                        "Runtime",
                        "DOM",
                        "DOMSnapshot",
                        "Accessibility",
                        "Input",
                        "Page",
                        "Network",
                        "Debugger",
                        "Log"
                    )
                ) { "不允许该 CDP 域" }
                val async = args.get("async")?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "async 必须为布尔值" }
                    it.asBoolean
                } ?: false
                val params = args.getAsJsonObject("params") ?: JsonObject()
                if (async) submit(args.text("panel_id"), target, method, params)
                else target.client.call(method, params)
            }

            "events" -> target.client.events(args.get("after")?.asLong ?: 0L)
            "screenshot" -> {
                val result =
                    target.client.call("Page.captureScreenshot", JsonObject().apply { addProperty("format", "png") })
                val bytes = Base64.getDecoder().decode(result.text("data"))
                val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("CDP 未返回有效截图")
                Files.createDirectories(evidence)
                val path = evidence.resolve("jcef-${UUID.randomUUID()}.png")
                Files.write(path, bytes)
                mapOf(
                    "image_path" to path.toString(),
                    "width" to image.width,
                    "height" to image.height,
                    "capture_kind" to "jcef-cdp"
                )
            }

            else -> error("未知 JS 命令: $command")
        }
    }

    @Synchronized
    private fun submit(panel: String, target: Target, method: String, params: JsonObject): Map<String, Any?> {
        check(targets[panel] === target) { "浏览器已失效" }
        return operations.submit(panel, method) { target.client.request(method, params) }
    }

    @Synchronized fun release(panel: String) { targets.remove(panel)?.client?.close() }
    @Synchronized override fun close() { targets.values.forEach { it.client.close() }; targets.clear() }
}
