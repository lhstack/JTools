package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 显式授权的已安装 IDE/发行目录；不复用日常配置，不自动接受许可协议。 */
class SandboxStartupIntegrationTest {
    @Test
    @EnabledIfSystemProperty(named = "debugger.test.distribution", matches = ".+")
    fun `installed ide starts without consent clicks and reuses dedicated configuration after restart`() {
        val ide = Path.of(System.getProperty("debugger.test.ide"))
        val output = Path.of(System.getProperty("debugger.test.output"))
        Files.createDirectories(output)
        val parent = Files.createTempDirectory(Path.of("/tmp"), "jt-")
        val args = JsonObject().apply {
            addProperty("launcher", ide.resolve("MacOS/idea").toString())
            addProperty("vm_options", ide.resolve("bin/idea.vmoptions").toString())
            addProperty("jtools_distribution", System.getProperty("debugger.test.distribution"))
            addProperty("debugger_jar", System.getProperty("debugger.test.jar"))
            addProperty("sandbox_parent", parent.toAbsolutePath().toString())
        }
        SandboxController().use { controller ->
            fun start() = json.toJsonTree(controller.call("startup-acceptance", "start", args)).asJsonObject
            fun call(session: JsonObject, command: String): JsonObject = json.toJsonTree(controller.call(
                "startup-acceptance", command, JsonObject().apply { addProperty("sandbox_id", session.text("sandbox_id")) }
            )).asJsonObject
            fun ready(session: JsonObject) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
                while (!Files.isRegularFile(Path.of(session.text("root")).resolve("endpoint"))) {
                    val status = call(session, "status.process")
                    check(status.get("alive").asBoolean) { "IDE 已退出: $status; evidence=$parent" }
                    check(System.nanoTime() < deadline) { "IDE 启动超时: $status; evidence=$parent" }
                    Thread.sleep(250)
                }
                val ping = call(session, "ping")
                assertTrue(ping.get("ready").asBoolean)
                val ui = call(session, "startup.ui")
                assertEquals(0, ui.getAsJsonArray("modal_dialogs").size(), "启动仍存在模态确认框：$ui")
                try {
                    val running = json.toJsonTree(controller.call("startup-acceptance", "run", JsonObject().apply {
                        addProperty("sandbox_id", session.text("sandbox_id"))
                        addProperty(
                            "artifact", Path.of(System.getProperty("debugger.test.examplesRoot"))
                                .resolve("maven/target/jtools-maven-example-1.0.0-plugin.jar").toString()
                        )
                    })).asJsonObject
                    assertEquals("RUNNING", running.text("state"))
                    assertEquals(1, running.getAsJsonArray("panels").size())
                    assertEquals("STOPPED", call(session, "stop").text("state"))
                } catch (failure: Throwable) {
                    println("Blocked startup acceptance session: $session")
                    println(Files.readString(Path.of(session.text("root")).resolve("process.log")).takeLast(16000))
                    call(session, "jvm.pause")
                    try {
                        val threads =
                            json.toJsonTree(controller.call("startup-acceptance", "jvm.threads", JsonObject().apply {
                                addProperty("sandbox_id", session.text("sandbox_id"))
                            })).asJsonArray
                        threads.map { it.asJsonObject }.filter { it.text("name").contains("AWT-EventQueue") }
                            .forEach { thread ->
                                println(controller.call("startup-acceptance", "jvm.frames", JsonObject().apply {
                                    addProperty("sandbox_id", session.text("sandbox_id"))
                                    addProperty("thread_id", thread.text("thread_id"))
                                }))
                            }
                    } finally {
                        call(session, "jvm.resume")
                    }
                    throw failure
                }
            }

            val first = start()
            ready(first)
            val reused = start()
            assertEquals(first.text("sandbox_id"), reused.text("sandbox_id"))
            assertTrue(reused.get("reused").asBoolean)
            val marker = Path.of(first.text("config_path")).resolve("debugger-test-marker")
            Files.writeString(marker, "initialized")
            call(first, "terminate")
            val next = start()
            assertNotEquals(first.text("sandbox_id"), next.text("sandbox_id"))
            assertEquals(first.text("config_path"), next.text("config_path"))
            assertEquals("initialized", Files.readString(marker))
            ready(next)
            call(next, "terminate")
            args.addProperty("fresh", true)
            val fresh = start()
            assertNotEquals(first.text("config_path"), fresh.text("config_path"))
            ready(fresh)
            call(fresh, "terminate")
            Files.writeString(
                parent.resolve("verified.json"),
                json.toJson(mapOf("first" to first, "next" to next, "fresh" to fresh))
            )
            println("Startup/restart acceptance verified: $parent")
        }
    }
}
