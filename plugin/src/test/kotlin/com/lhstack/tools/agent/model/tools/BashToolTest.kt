package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.http.ModelCancel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test

class BashToolTest {
    @Test
    fun `schema only exposes normal command execution`() {
        val root = Files.createTempDirectory("jtools-bash-schema-")
        try {
            val definition = BashTool(WorkspaceTools(root.toString())).definition("")
            val schema = definition.parameters.asJsonObject
            assertTrue(schema["properties"].asJsonObject.has("command"))
            assertFalse(schema["properties"].asJsonObject.has("output_encoding"))
            assertTrue(definition.description.contains("启动方式："))
            assertFalse(definition.description.contains("必须都是 UTF-8"))
            assertFalse(definition.description.contains("解码失败"))
            assertFalse(definition.description.contains("AI"))
            val commandDescription = schema["properties"].asJsonObject["command"].asJsonObject["description"].asString
            val shell = SelectedShell.current()
            assertTrue(commandDescription.contains("${shell.program} ${shell.args.joinToString(" ")}"))
            assertTrue(commandDescription.contains("工具名 bash 不表示当前使用 Bash"))
            assertFalse(commandDescription.contains("AI"))
            assertFalse(commandDescription.contains("必须都是 UTF-8 内容"))
            assertTrue(definition.description.contains("${shell.program} ${shell.args.joinToString(" ")}"))
            assertFalse(definition.description.contains("output_encoding"))
            for (name in listOf("output_id", "stream", "offset", "limit")) assertFalse(schema["properties"].asJsonObject.has(name))
            assertEquals(listOf("command"), schema["required"].asJsonArray.map { it.asString })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `overflow completes command and files are readable with normal Bash commands`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val root = Files.createTempDirectory("jtools-bash-test-")
        var outputDirectory: Path? = null
        try {
            val tool = BashTool(WorkspaceTools(root.toString()), maxOutputChars = 1024)
            val result = tool.callJsonBlocking(command("i=0; while [ \"\$i\" -lt 5000 ]; do printf 'hello\\n'; printf 'warn\\n' >&2; i=\$((i+1)); done; printf done > completed; exit 7")).asJsonObject
            val stdoutPath = Path.of(result["files"].asJsonObject["stdout"].asJsonObject["path"].asString)
            outputDirectory = stdoutPath.parent
            assertEquals("done", Files.readString(root.resolve("completed")))
            assertEquals(7, result["exit_code"].asInt)
            assertFalse(result["success"].asBoolean)
            assertEquals("hello\n".repeat(5000), Files.readString(outputDirectory.resolve("stdout.txt")))
            assertEquals("warn\n".repeat(5000), Files.readString(outputDirectory.resolve("stderr.txt")))
            val quoted = "'" + stdoutPath.toString().replace("'", "'\"'\"'") + "'"
            val page = tool.callJsonBlocking(command("sed -n '1,3p' $quoted")).asJsonObject
            assertEquals("hello\n".repeat(3), page["stdout"].asString)
            assertTrue(page["success"].asBoolean)
            assertTrue(ToolOutputText.size(page) <= 1024)
        } finally {
            outputDirectory?.toFile()?.deleteRecursively()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `MCP file is directly readable through Bash without reader registration`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val root = Files.createTempDirectory("jtools-mcp-bash-")
        val original = JsonObject().apply { addProperty("payload", "data".repeat(2000)) }
        val receipt = McpCallOutputSupport().deliver(original, 1024).asJsonObject
        val path = Path.of(receipt["path"].asString)
        try {
            val quoted = "'" + path.toString().replace("'", "'\"'\"'") + "'"
            val result = BashTool(WorkspaceTools(root.toString())).callJsonBlocking(command("sed -n '1p' $quoted")).asJsonObject
            assertTrue(result["success"].asBoolean)
            assertEquals("{\n", result["stdout"].asString)
        } finally {
            path.parent.toFile().deleteRecursively()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `timeout and manual cancellation still terminate commands`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val root = Files.createTempDirectory("jtools-bash-stop-")
        try {
            val timeout = BashTool(WorkspaceTools(root.toString())).callJsonBlocking(command("printf started; sleep 5; touch should-not-run").apply { addProperty("timeout_secs", 1) }).asJsonObject
            assertTrue(timeout["timed_out"].asBoolean)
            assertFalse(timeout["success"].asBoolean)
            assertEquals("started", timeout["stdout"].asString)
            val cancel = ModelCancel().apply { cancel() }
            val cancelled = BashTool(WorkspaceTools(root.toString()), cancel = cancel).callJsonBlocking(command("sleep 5; touch should-not-run")).asJsonObject
            assertTrue(cancelled["cancelled"].asBoolean)
            assertFalse(cancelled["success"].asBoolean)
            assertFalse(Files.exists(root.resolve("should-not-run")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `invalid output setting is rejected before command execution`() {
        val root = Files.createTempDirectory("jtools-bash-limit-")
        try {
            assertFailsWith<IllegalArgumentException> {
                BashTool(WorkspaceTools(root.toString()), maxOutputChars = 100).callJsonBlocking(command("touch should-not-run"))
            }
            assertFalse(Files.exists(root.resolve("should-not-run")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `UTF8 and GBK command output are returned without an encoding parameter`() {
        val root = Files.createTempDirectory("jtools-bash-explicit-encoding-")
        try {
            val tool = BashTool(WorkspaceTools(root.toString()))
            val shell = SelectedShell.current()
            val emit = when (shell.label) {
                "cmd" -> "type stdout.bin & type stderr.bin 1>&2"
                "pwsh", "powershell" ->
                    "\$out = [IO.File]::ReadAllBytes('stdout.bin'); " +
                        "[Console]::OpenStandardOutput().Write(\$out, 0, \$out.Length); " +
                        "\$err = [IO.File]::ReadAllBytes('stderr.bin'); " +
                        "[Console]::OpenStandardError().Write(\$err, 0, \$err.Length)"
                else -> "cat stdout.bin; cat stderr.bin >&2"
            }
            for (name in listOf("GBK", "UTF-8")) {
                val charset = java.nio.charset.Charset.forName(name)
                val stdout = "中文输出\n"
                val stderr = "中文错误\n"
                Files.write(root.resolve("stdout.bin"), stdout.toByteArray(charset))
                Files.write(root.resolve("stderr.bin"), stderr.toByteArray(charset))
                val result = tool.callJsonBlocking(command(emit)).asJsonObject
                assertTrue(result["success"].asBoolean, result.toString())
                assertEquals(stdout, result["stdout"].asString)
                assertEquals(stderr, result["stderr"].asString)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun command(text: String) = JsonObject().apply {
        addProperty("command", text)
    }
}
