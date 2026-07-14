package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * bash 工具。完全照抄 awake-claw tools.rs 的 BashTool。
 *
 * 用系统 shell 执行命令，返回 stdout / stderr / exit_code / success。
 * cwd 省略时用 workspace 根目录；timeout 默认 30s，硬上限 300s。
 * 支持超时与取消：超时/取消时销毁进程树并在 stderr 追加提示，success=false。
 *
 * 与 awake 差异：Rust 用 setsid + SIGKILL 杀进程组，Kotlin 用 Process.destroyForcibly
 * 递归销毁（JDK9 descendants）。shell 选择照抄 selected_shell 的 zsh>bash>sh。
 */
class BashTool(
    private val workspace: WorkspaceTools,
    private val envVars: Map<String, String> = emptyMap(),
    private val cancel: ModelCancel? = null,
) : ToolDyn {

    override fun definition(prompt: String): ToolDefinition {
        val shell = SelectedShell.current()
        return ToolDefinition(
            name = NAME,
            description = "Run a command using ${shell.label} and return stdout, stderr, and exit code. " +
                "If cwd is omitted, the command runs in the system environment's 默认CWD. Only set cwd when the command " +
                "must run from a different directory. Relative paths inside command are resolved from the actual execution cwd.",
            parameters = JsonParser.parseString(
                """
                {
                    "type": "object",
                    "properties": {
                        "command": {
                            "type": "string",
                            "description": "Shell command to execute. Use paths relative to the actual execution cwd unless an absolute path is explicitly required."
                        },
                        "cwd": {
                            "type": "string",
                            "description": "Optional working directory override. Omit this to run in the system environment's 默认CWD. Set it only when the command must execute from another directory; after cwd is set, relative paths in command are based on that cwd."
                        },
                        "timeout_secs": {
                            "type": "integer",
                            "description": "Optional timeout seconds. Default 30, hard limit 300."
                        }
                    },
                    "required": ["command"]
                }
                """.trimIndent()
            ),
        )
    }

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val obj = args.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val command = obj.get("command")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        if (command.isNullOrEmpty()) {
            throw ToolException.emptyCommand()
        }
        val cwdArg = obj.get("cwd")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        val cwd: File = if (cwdArg != null) {
            File(cwdArg)
        } else {
            try {
                workspace.canonicalRoot()
            } catch (e: Throwable) {
                throw ToolException.invalidCwd(e.message ?: e.toString())
            }
        }
        val timeoutSecs = obj.get("timeout_secs")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asLong?.coerceIn(1, 300) ?: 30

        val shell = SelectedShell.current()
        val builder = ProcessBuilder(shell.program, *shell.args, command)
            .directory(cwd)
            .redirectErrorStream(false)
        builder.environment().putAll(envVars)

        val process = builder.start()
        var timedOut = false
        var cancelled = false

        // 轮询等待，兼顾超时与取消（对齐 awake 的 50ms 轮询循环）。
        val deadline = System.currentTimeMillis() + timeoutSecs * 1000
        while (true) {
            if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                break
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                destroyTree(process)
                break
            }
            if (cancel?.isCancelled() == true) {
                cancelled = true
                destroyTree(process)
                break
            }
        }

        val stdout = process.inputStream.readBytes().toString(shell.outputCharset)
        var stderr = process.errorStream.readBytes().toString(shell.outputCharset)
        val exitCode = if (process.isAlive) null else process.exitValue()

        if (timedOut) {
            if (stderr.isNotEmpty()) stderr += "\n"
            stderr += "command timed out"
        }
        if (cancelled) {
            if (stderr.isNotEmpty()) stderr += "\n"
            stderr += "用户手动取消"
        }

        return JsonObject().apply {
            if (exitCode != null) addProperty("exit_code", exitCode) else add("exit_code", com.google.gson.JsonNull.INSTANCE)
            addProperty("success", exitCode == 0 && !timedOut && !cancelled)
            addProperty("stdout", stdout)
            addProperty("stderr", stderr)
        }
    }

    /** 递归销毁进程树，对齐 awake 的 kill_child_process_tree。 */
    private fun destroyTree(process: Process) {
        runCatching {
            process.descendants().forEach { it.destroyForcibly() }
        }
        process.destroyForcibly()
    }

    companion object {
        const val NAME = "bash"
    }
}

/** 选中的 shell。照抄 awake 的 selected_shell：zsh > bash > sh（Windows: pwsh > powershell > cmd）。 */
data class SelectedShell(
    val label: String,
    val program: String,
    val args: Array<String>,
    /** 子进程 stdout/stderr 的实际编码：Windows 用系统 native 编码（代码页，如 GBK），Unix 用 UTF-8。 */
    val outputCharset: Charset,
) {
    companion object {
        fun current(): SelectedShell {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                val winCharset = systemNativeCharset()
                if (commandInPath("pwsh")) {
                    return SelectedShell("pwsh", "pwsh", arrayOf("-NoProfile", "-NonInteractive", "-Command"), winCharset)
                }
                if (commandInPath("powershell")) {
                    return SelectedShell("powershell", "powershell", arrayOf("-NoProfile", "-NonInteractive", "-Command"), winCharset)
                }
                return SelectedShell("cmd", "cmd", arrayOf("/C"), winCharset)
            }
            if (commandInPath("zsh")) {
                return SelectedShell("zsh", "zsh", arrayOf("-lc"), Charsets.UTF_8)
            }
            if (commandInPath("bash")) {
                return SelectedShell("bash", "bash", arrayOf("-lc"), Charsets.UTF_8)
            }
            return SelectedShell("sh", "sh", arrayOf("-lc"), Charsets.UTF_8)
        }

        /**
         * 系统 native 编码：子进程（PowerShell/cmd）输出字节按此编码。
         * Windows 中文环境下为 GBK（代码页 936）。取 sun.jnu.encoding（JVM 推导的平台原生编码），
         * 而不是 file.encoding（JDK18+ 默认 UTF-8，与子进程实际输出不一致）。
         */
        fun systemNativeCharset(): Charset {
            val name = System.getProperty("sun.jnu.encoding") ?: System.getProperty("native.encoding")
            return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charset.defaultCharset()
        }

        /** 照抄 command_in_path：在 PATH 各目录下找可执行文件。 */
        private fun commandInPath(name: String): Boolean {
            val path = System.getenv("PATH") ?: return false
            return path.split(File.pathSeparatorChar).any { dir ->
                dir.isNotBlank() && File(dir, name).isFile
            }
        }
    }
}
