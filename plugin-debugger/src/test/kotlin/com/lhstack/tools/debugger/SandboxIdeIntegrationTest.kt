package com.lhstack.tools.debugger

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

/** 仅在显式传入独立 IDE 后运行；保留验收证据，finally 终止所有子进程。 */
class SandboxIdeIntegrationTest {
    @Test
    @EnabledIfSystemProperty(named = "debugger.test.ide", matches = ".+")
    fun `full ide sandboxes execute lifecycle debug and terminate independently`() {
        val ide = Path.of(System.getProperty("debugger.test.ide"))
        val root = Path.of(System.getProperty("debugger.test.output"))
            .resolve("ide-acceptance-${System.currentTimeMillis()}")
        Files.createDirectories(root)
        println("IDE acceptance evidence: $root")
        val distributionPath = System.getProperty("debugger.test.distribution")
        require(!distributionPath.isNullOrBlank()) { "真实 IDE 验收必须显式指定 debuggerTestDistribution，避免加载旧产物" }
        val distribution = Path.of(distributionPath)
        require(Files.isDirectory(distribution.resolve("lib"))) { "验收发行目录缺少 lib：$distribution" }
        val base = root.resolve("base.vmoptions")
        // 测试专用启动选项，禁止首次导入个人配置及遥测确认对话框。
        Files.writeString(base, "-Xmx1536m\n-Djb.consents.confirmation.enabled=false\n-Dide.show.tips.on.startup.default.value=false\n")
        val artifact = javaArtifact(root)
        SandboxController().use { controller ->
            fun start(): JsonObject {
                val args = JsonObject().apply {
                    addProperty("launcher", ide.resolve("MacOS/idea").toString())
                    addProperty("vm_options", base.toString())
                    addProperty("jtools_distribution", distribution.toString())
                    addProperty("debugger_jar", System.getProperty("debugger.test.jar"))
                    addProperty("sandbox_parent", root.toString())
                    addProperty("fresh", true)
                }
                return tree(controller.call("acceptance", "start", args))
            }
            fun call(id: String, command: String, args: JsonObject = JsonObject()): JsonObject {
                args.addProperty("sandbox_id", id)
                return tree(controller.call("acceptance", command, args))
            }
            fun ready(session: JsonObject) {
                val path = Path.of(session.text("root"))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
                while (!Files.exists(path.resolve("endpoint"))) {
                    if (Files.exists(path.resolve("startup-error.txt"))) error(Files.readString(path.resolve("startup-error.txt")))
                    val processState = call(session.text("sandbox_id"), "status.process")
                    check(processState.get("alive").asBoolean) {
                        "IDE已退出: $processState; log=${Files.readString(path.resolve("process.log")).takeLast(18000)}"
                    }
                    check(System.nanoTime() < deadline) {
                        "IDE端点超时: ${Files.readString(path.resolve("process.log")).takeLast(18000)}"
                    }
                    Thread.sleep(250)
                }
                assertTrue(call(session.text("sandbox_id"), "ping").get("ready").asBoolean)
            }
            val a = start()
            ready(a)
            val b = start()
            ready(b)
            val aid = a.text("sandbox_id")
            val bid = b.text("sandbox_id")
            assertNotEquals(a.text("root"), b.text("root"))
            assertFailsWith<IllegalArgumentException> {
                controller.call("wrong-owner", "status.process", JsonObject().apply { addProperty("sandbox_id", aid) })
            }
            val breakpoint = call(aid, "jvm.breakpoint", JsonObject().apply { addProperty("class", "fixture.SandboxFixture"); addProperty("line", 15) })
            val running = CompletableFuture.supplyAsync {
                call(aid, "run", JsonObject().apply { addProperty("artifact", artifact.toString()); addProperty("timeout_secs", 120) })
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var hit: JsonObject? = null
            while (hit == null && System.nanoTime() < deadline) {
                val events = call(aid, "jvm.events").getAsJsonArray("events")
                hit = events.map { it.asJsonObject }.firstOrNull { it.get("type").asString.contains("BreakpointEvent") }
                if (hit == null) Thread.sleep(50)
            }
            if (hit == null) {
                controller.call("acceptance", "jvm.pause", JsonObject().apply { addProperty("sandbox_id", aid) })
                val threads = json.toJsonTree(controller.call("acceptance", "jvm.threads", JsonObject().apply { addProperty("sandbox_id", aid) })).asJsonArray
                val diagnostic = threads.map { it.asJsonObject }.filter { it.text("name").contains("AWT-EventQueue") || it.text("name").contains("sandbox") }.map { thread ->
                    thread.toString() + "\n" + controller.call("acceptance", "jvm.frames", JsonObject().apply { addProperty("sandbox_id", aid); addProperty("thread_id", thread.text("thread_id")) })
                }
                Files.writeString(root.resolve("blocked-threads.txt"), diagnostic.joinToString("\n"))
                call(aid, "jvm.resume")
            }
            assertNotNull(hit, "真实 IDE 中未命中插件初始化断点: ${call(aid, "jvm.events")}; run=${if (running.isDone) running.get() else "pending"}")
            assertFalse(running.isDone, "断点暂停应阻塞 run，但不能阻塞控制端")
            assertTrue(call(aid, "status.process").get("alive").asBoolean)
            val variables = controller.call("acceptance", "jvm.variables", JsonObject().apply {
                addProperty("sandbox_id", aid); addProperty("thread_id", hit.text("thread_id"))
            })
            assertContains(variables.toString(), "42")
            call(aid, "jvm.resume")
            val status = running.get(30, TimeUnit.SECONDS)
            call(aid, "jvm.remove", JsonObject().apply { addProperty("request_id", breakpoint.text("request_id")) })
            assertEquals("RUNNING", status.text("state"))
            val pluginObject = status.getAsJsonObject("plugin_object").text("object_id")
            val projectObject = status.getAsJsonObject("project_object").text("object_id")
            fun invoke(id: String, method: String, types: String = "[]", values: String = "[]", thread: String = "worker"): JsonObject =
                call(aid, "invoke", JsonParser.parseString("""{"object_id":"$id","method":"$method","parameter_types":$types,"arguments":$values,"thread":"$thread"}""").asJsonObject)
            val methods = call(aid, "methods", JsonObject().apply { addProperty("object_id", pluginObject) })
            assertContains(methods.toString(), "functionCallings")
            assertEquals("worker", invoke(pluginObject, "executionThread").text("value"))
            assertEquals("edt", invoke(pluginObject, "executionThread", thread = "edt").text("value"))
            assertEquals(1, invoke(pluginObject, "increment").get("value").asInt)
            assertEquals(2, invoke(pluginObject, "increment").get("value").asInt)
            val projectHash = invoke(projectObject, "getLocationHash").text("value")
            val functions = invoke(pluginObject, "functionCallings", """["java.lang.String"]""", """[{"value":"$projectHash"}]""")
            val function = invoke(functions.text("object_id"), "get", """["int"]""", """[{"value":0}]""")
            assertEquals("called:payload", invoke(function.text("object_id"), "call", """["java.lang.String"]""", """[{"value":"payload"}]""").text("value"))
            val static = call(aid, "invoke", JsonParser.parseString("""{"class_name":"fixture.SandboxFixture","method":"staticEcho","parameter_types":["java.lang.String"],"arguments":[{"value":"verified"}]}""").asJsonObject)
            assertEquals("static:verified", static.text("value"))
            assertContains(assertFailsWith<IllegalStateException> { invoke(pluginObject, "fail") }.message.orEmpty(), "reflection-fixture-failure")
            val panel = status.getAsJsonArray("panels").single().asString
            val snapshot = call(aid, "swing.snapshot", JsonObject().apply { addProperty("panel_id", panel) })
            val input = find(snapshot, "fixture-input")
            val button = find(snapshot, "fixture-apply")
            call(aid, "swing.action", JsonObject().apply {
                addProperty("panel_id", panel); addProperty("ref", input.text("ref")); addProperty("action", "input"); addProperty("text", "verified-input")
            })
            call(aid, "swing.action", JsonObject().apply {
                addProperty("panel_id", panel); addProperty("ref", button.text("ref")); addProperty("action", "click")
            })
            assertContains(call(aid, "swing.snapshot", JsonObject().apply { addProperty("panel_id", panel) }).toString(), "verified-input")
            val image = call(aid, "swing.screenshot", JsonObject().apply { addProperty("panel_id", panel) })
            assertTrue(Files.size(Path.of(image.text("image_path"))) > 0)
            call(bid, "run", JsonObject().apply { addProperty("artifact", artifact.toString()) })
            assertEquals("STOPPED", call(aid, "stop").text("state"))
            assertFailsWith<IllegalStateException> { invoke(pluginObject, "pluginName") }
            val rerun = call(aid, "run", JsonObject().apply { addProperty("artifact", artifact.toString()) })
            assertNotEquals(pluginObject, rerun.getAsJsonObject("plugin_object").text("object_id"))
            assertFailsWith<IllegalStateException> { invoke(pluginObject, "pluginName") }
            call(aid, "stop")
            val history = controller.call("acceptance", "events", JsonObject().apply { addProperty("sandbox_id", aid) }).toString()
            listOf("install", "openProject", "createPanel", "showPanel", "closePanel", "closeProject", "unInstall").forEach { assertContains(history, it) }
            call(aid, "terminate")
            assertEquals("RUNNING", call(bid, "status").text("state"))
            call(bid, "stop")
            call(bid, "terminate")
            Files.writeString(root.resolve("verified.json"), json.toJson(mapOf("a" to a, "b" to b, "lifecycle" to history)))
        }
    }

    private fun javaArtifact(root: Path): Path {
        val source = root.resolve("SandboxFixture.java")
        javaClass.getResourceAsStream("/fixtures/SandboxFixture.java")!!.use { Files.copy(it, source) }
        val classes = Files.createDirectory(root.resolve("fixture-classes"))
        val compiler = ProcessBuilder(Path.of(System.getProperty("debugger.test.jdk"), "bin/javac").toString(),
            "--release", "17", "-g", "-cp", System.getProperty("debugger.test.classpath"), "-d", classes.toString(), source.toString())
            .redirectErrorStream(true).redirectOutput(root.resolve("fixture-compile.log").toFile()).start()
        assertTrue(compiler.waitFor(20, TimeUnit.SECONDS))
        assertEquals(0, compiler.exitValue(), Files.readString(root.resolve("fixture-compile.log")))
        val artifact = root.resolve("fixture.jar")
        JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/ToolsPlugin.txt")); jar.write("fixture.SandboxFixture".toByteArray()); jar.closeEntry()
            Files.walk(classes).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { path ->
                    jar.putNextEntry(JarEntry(classes.relativize(path).toString().replace('\\', '/')))
                    Files.copy(path, jar)
                    jar.closeEntry()
                }
            }
        }
        return artifact
    }

    private fun tree(value: Any?): JsonObject = JsonParser.parseString(json.toJson(value)).asJsonObject
    private fun find(node: JsonObject, name: String): JsonObject {
        if (node.get("name")?.asString == name) return node
        node.getAsJsonArray("children")?.forEach { child ->
            try { return find(child.asJsonObject, name) } catch (_: NoSuchElementException) { }
        }
        throw NoSuchElementException(name)
    }
}
