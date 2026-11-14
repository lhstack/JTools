package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.jetbrains.cef.JCefAppConfig
import com.lhstack.tools.plugins.SandboxHost
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.awt.GridLayout
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

/** 显式启用的真实 JCEF 验收；独立子 JVM，避免污染测试进程的 CEF 全局状态。 */
class JcefRuntimeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    @EnabledIfSystemProperty(named = "debugger.test.jcef", matches = "true")
    fun `parallel jcef processes bind distinct pages evaluate javascript and capture pixels`() {
        val classpath = System.getProperty("debugger.test.classpath")
        val runs = listOf("a", "b").map { name ->
            val root = Files.createDirectory(directory.resolve(name))
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--add-modules=jcef", "-Duser.home=$root", "-Djava.io.tmpdir=$root",
                "-cp", classpath, JcefProbe::class.java.name, root.toString()
            )
                .redirectErrorStream(true).redirectOutput(root.resolve("jcef.log").toFile()).start()
            root to process
        }
        try {
            runs.forEach { (root, process) ->
                assertTrue(
                    process.waitFor(60, TimeUnit.SECONDS),
                    "JCEF子进程超时: ${Files.readString(root.resolve("jcef.log"))}"
                )
                assertEquals(0, process.exitValue(), Files.readString(root.resolve("jcef.log")))
                assertTrue(Files.exists(root.resolve("verified")), "子进程未完成真实页面验收")
            }
        } finally {
            runs.forEach { (_, process) ->
                process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}

object JcefProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        try {
            verify(Path.of(arguments.single()))
            System.exit(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            System.exit(1)
        }
    }

    private fun verify(root: Path) {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        check(CefApp.startup(emptyArray()))
        val config = JCefAppConfig.getInstance()
        val settings = config.cefSettings
        settings.cache_path = root.resolve("cef-cache").toString()
        val app = CefApp.getInstance(
            config.appArgs + arrayOf(
                "--remote-debugging-port=$port",
                "--remote-debugging-address=127.0.0.1"
            ), settings
        )
        val client = app.createClient()
        val browsers = mutableMapOf<String, CefBrowser>()
        val panels = mutableMapOf<String, JPanel>()
        SwingUtilities.invokeAndWait {
            val frame = JFrame("JTools JCEF 验收")
            frame.layout = GridLayout(1, 2)
            listOf("one", "two").forEach { id ->
                val page = root.resolve("$id.html")
                Files.writeString(
                    page,
                    "<html><body style='background:#bada55'><h1>$id</h1><script>window.probe='$id'</script></body></html>"
                )
                val browser = client.createBrowser(page.toUri().toString(), false, false)
                val panel = JPanel(java.awt.BorderLayout()).apply { add(browser.uiComponent) }
                browsers[id] = browser
                panels[id] = panel
                frame.add(panel)
            }
            frame.setSize(800, 400)
            frame.isVisible = true
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        val endpoint = CdpEndpoint(port)
        while (true) {
            try {
                if (endpoint.targets()
                        .count { it.get("url")?.asString?.startsWith(root.toUri().toString()) == true } == 2
                ) break
            } catch (error: java.io.IOException) {
                if (System.nanoTime() >= deadline) throw error
            }
            check(System.nanoTime() < deadline) { "真实 JCEF 页面未就绪" }
            Thread.sleep(100)
        }
        val host = object : SandboxHost {
            override fun execute(command: String, argumentsJson: String): String = error("不用于生命周期测试")
            override fun panel(panelId: String): JComponent = panels.getValue(panelId)
            override fun browser(panelId: String): Any = browsers.getValue(panelId)
        }
        WebDebug(host, root.resolve("evidence"), port).use { web ->
            listOf("one", "two").forEach { id ->
                val args = JsonObject().apply {
                    addProperty("panel_id", id)
                    addProperty("method", "Runtime.evaluate")
                    add(
                        "params",
                        JsonObject().apply {
                            addProperty("expression", "window.probe"); addProperty(
                            "returnByValue",
                            true
                        )
                        })
                }
                val result = web.call("cdp", args) as JsonObject
                check(result.getAsJsonObject("result").get("value").asString == id) { "绑定到了错误页面: $result" }
                args.addProperty("method", "Debugger.enable")
                args.remove("params")
                web.call("cdp", args)
                args.addProperty("method", "Runtime.evaluate")
                args.add(
                    "params",
                    JsonObject().apply {
                        addProperty("expression", "debugger; window.probe")
                        addProperty("returnByValue", true)
                    })
                args.addProperty("async", true)
                val submitted = web.call("cdp", args) as Map<*, *>
                check(submitted["state"] == "QUEUED")
                val query = JsonObject().apply { addProperty("operation_id", submitted["operation_id"].toString()) }
                args.remove("async")
                val breakpointDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!web.call("events", args).toString().contains("Debugger.paused")) {
                    check(System.nanoTime() < breakpointDeadline) { "真实 JS 断点未命中" }
                    Thread.sleep(25)
                }
                Thread.sleep(21_000)
                check((web.call("operation", query) as Map<*, *>)["state"] == "RUNNING")
                args.addProperty("method", "Debugger.resume")
                args.remove("params")
                web.call("cdp", args)
                val completedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var operation: Map<*, *>
                do {
                    operation = web.call("operation", query) as Map<*, *>
                    check(System.nanoTime() < completedDeadline) { "异步 CDP 未完成: $operation" }
                    Thread.sleep(10)
                } while (operation["state"] == "RUNNING")
                check(operation["state"] == "COMPLETED") { "$operation" }
                check((operation["result"] as JsonObject).getAsJsonObject("result").get("value").asString == id)
                val image = web.call("screenshot", args) as Map<*, *>
                check((image["width"] as Int) > 0 && Files.size(Path.of(image["image_path"].toString())) > 0)
            }
            val pendingArgs = JsonObject().apply {
                addProperty("panel_id", "one")
                addProperty("method", "Runtime.evaluate")
                addProperty("async", true)
                add("params", JsonObject().apply { addProperty("expression", "debugger; window.probe") })
            }
            val pendingOperation = web.call("cdp", pendingArgs) as Map<*, *>
            web.release("one")
            val query = JsonObject().apply { addProperty("operation_id", pendingOperation["operation_id"].toString()) }
            check((web.call("operation", query) as Map<*, *>)["state"] == "FAILED")
            val args = JsonObject().apply { addProperty("panel_id", "two"); addProperty("method", "Runtime.enable") }
            web.call("cdp", args)
            pendingArgs.addProperty("panel_id", "two")
            val stopped = web.call("cdp", pendingArgs) as Map<*, *>
            web.close()
            query.addProperty("operation_id", stopped["operation_id"].toString())
            check((web.call("operation", query) as Map<*, *>)["state"] == "FAILED")
        }
        Files.writeString(
            root.resolve("verified"),
            "distinct-targets,evaluate,async-pause-over-20s,result,resume,screenshot,independent-release,close-fails-pending"
        )
    }
}
