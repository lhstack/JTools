package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JvmDebugTest {
    @TempDir lateinit var directory: Path

    @Test fun `external debugger binds pending breakpoint reads variables and resumes target`() {
        val source = directory.resolve("DebugTarget.java")
        Files.writeString(source, """
            public class DebugTarget {
                public static void main(String[] args) throws Exception {
                    Thread.sleep(1000);
                    DebugWork.run();
                }
            }
            class DebugWork {
                static void run() {
                    int answer = 42;
                    System.out.println(answer);
                }
            }
        """.trimIndent())
        val javac = Path.of(System.getProperty("debugger.test.jdk"), "bin/javac")
        assertTrue(Files.isExecutable(javac), "JDI集成测试需要完整JDK")
        val compile = ProcessBuilder(javac.toString(), "--release", "17", "-g", "-d", directory.toString(), source.toString())
            .redirectErrorStream(true).redirectOutput(directory.resolve("compile.log").toFile()).start()
        assertTrue(compile.waitFor(20, TimeUnit.SECONDS), "测试目标编译超时")
        assertEquals(0, compile.exitValue(), Files.readString(directory.resolve("compile.log")))
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:$port",
            "-cp", directory.toString(), "DebugTarget")
            .redirectErrorStream(true).redirectOutput(directory.resolve("target.log").toFile()).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(directory.resolve("target.log")) || !Files.readString(directory.resolve("target.log")).contains("Listening")) {
                check(process.isAlive && System.nanoTime() < deadline) { "JDWP未就绪" }; Thread.sleep(25)
            }
            JvmDebug(port).use { debugger ->
                val request = JsonObject().apply { addProperty("class", "DebugWork"); addProperty("line", 11) }
                debugger.call("breakpoint", request)
                debugger.call("resume", JsonObject())
                var hit: Map<*, *>? = null
                val timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (hit == null && System.nanoTime() < timeout) {
                    val result = debugger.call("events", JsonObject()) as Map<*, *>
                    hit = (result["events"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["type"].toString().contains("BreakpointEvent") }
                    if (hit == null) Thread.sleep(25)
                }
                assertNotNull(hit, "未命中真实断点: ${debugger.call("events", JsonObject())}; target=${Files.readString(directory.resolve("target.log"))}")
                val args = JsonObject().apply { addProperty("thread_id", hit["thread_id"].toString()) }
                val variables = debugger.call("variables", args).toString()
                assertContains(variables, "answer")
                assertContains(variables, "42")
                debugger.call("resume", JsonObject())
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            }
        } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
    }
}
