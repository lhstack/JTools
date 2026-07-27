package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.charset.Charset
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
            description = "使用 ${shell.label} 执行命令并返回 stdout、stderr 和退出码。省略 cwd 时使用系统默认工作目录。输出上限约 8KB，超过后只返回前 8KB 并在 stderr 说明已截断（进程会被终止），完整输出请用 head/tail/grep 缩小或重定向到文件再分段读取。命令改动文件后若要用 read_project_files 读取，请把 refresh_vfs 设为 true 刷新 IDE 缓存。",
            parameters = JsonParser.parseString(
                """
                {
                    "type": "object",
                    "properties": {
                        "command": {
                            "type": "string",
                            "description": "必填。要执行的 Shell 命令；除非必须，否则使用相对路径。"
                        },
                        "cwd": {
                            "type": "string",
                            "description": "可选。工作目录；省略时使用系统默认工作目录。"
                        },
                        "timeout_secs": {
                            "type": "integer",
                            "description": "可选。超时秒数，默认30，最大300。"
                        },
                        "refresh_vfs": {
                            "type": "boolean",
                            "description": "可选。默认false。命令改动了工作区文件且随后要用 read_project_files 等基于 IDE VFS 的工具读取时，设为 true 刷新 IDE 缓存；纯查询命令保持 false 以免影响性能。"
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
        // 部分模型用 0 表达"不指定超时"；0 及负值按未指定处理取默认值，避免夹取成 1 秒导致命令必然超时。
        val timeoutSecs = obj.get("timeout_secs")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asLong?.takeIf { it > 0 }?.coerceIn(1, 300) ?: 30
        val refreshVfs = obj.get("refresh_vfs")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean ?: false

        val shell = SelectedShell.current()
        val builder = ProcessBuilder(shell.program, *shell.args, command)
            .directory(cwd)
            .redirectErrorStream(false)
        builder.environment().putAll(envVars)

        val process = builder.start()
        val outputBudget = ProcessOutputBudget(ToolOutputLimit.MAX_BYTES)
        val stdoutCapture = ProcessOutputCapture(process.inputStream, outputBudget)
        val stderrCapture = ProcessOutputCapture(process.errorStream, outputBudget)
        val stdoutThread = stdoutCapture.start("jtools-bash-stdout")
        val stderrThread = stderrCapture.start("jtools-bash-stderr")
        var timedOut = false
        var cancelled = false
        var outputExceeded = false

        // 轮询等待，兼顾超时、取消和输出上限。输出超过上限时立即销毁进程树，
        // 避免命令继续写满管道导致工具线程永久等待。
        val deadline = System.currentTimeMillis() + timeoutSecs * 1000
        while (true) {
            if (outputBudget.exceeded.get()) {
                outputExceeded = true
                destroyTree(process)
                break
            }
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
        if (outputBudget.exceeded.get()) {
            outputExceeded = true
            destroyTree(process)
        }
        stdoutThread.join(1000)
        stderrThread.join(1000)

        val stdout = stdoutCapture.text(shell.outputCharset)
        var stderr = stderrCapture.text(shell.outputCharset)
        val exitCode = if (process.isAlive) null else process.exitValue()

        if (timedOut) {
            if (stderr.isNotEmpty()) stderr += "\n"
            stderr += "command timed out"
        }
        if (cancelled) {
            if (stderr.isNotEmpty()) stderr += "\n"
            stderr += "用户手动取消"
        }
        // 输出超上限：进程已被终止且只保留了前面部分，这里不再整段丢弃，
        // 而是保留已捕获内容并追加说明，最终字节裁剪由 ToolRuntime 的 truncateToLimit 兜底。
        if (outputExceeded) {
            if (stderr.isNotEmpty()) stderr += "\n"
            stderr += "输出超过上限已停止捕获并终止进程，仅返回前面部分；" +
                "如需完整输出请用 head/tail/grep 缩小结果，或重定向到文件后用 read_project_files 分段读取。"
        }

        // 仅在调用方显式要求时刷新 VFS：避免每次命令都刷新拖累性能。
        // 命令改动了磁盘文件且随后要用 read_project_files 读取时，调用方应传 refresh_vfs=true。
        if (refreshVfs) refreshVfsAfterCommand(cwd)

        return JsonObject().apply {
            if (exitCode != null) addProperty("exit_code", exitCode) else add("exit_code", com.google.gson.JsonNull.INSTANCE)
            addProperty("success", exitCode == 0 && !timedOut && !cancelled && !outputExceeded)
            addProperty("stdout", stdout)
            addProperty("stderr", stderr)
        }
    }

    /**
     * 命令执行后刷新工作目录的 VFS：让 IDE 重新扫描磁盘变更，
     * 使 read_project_files 等基于 VFS 的工具能读到 bash 刚写入/修改的最新内容。
     * async=true 不阻塞工具线程；文件不在 VFS 中（如全新目录）时静默跳过。
     */
    private fun refreshVfsAfterCommand(cwd: File) {
        runCatching {
            ApplicationManager.getApplication().executeOnPooledThread {
                val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(cwd) ?: return@executeOnPooledThread
                VfsUtil.markDirtyAndRefresh(true, true, true, file)
            }
        }
    }

    /** 递归销毁进程树，对齐 awake 的 kill_child_process_tree。 */
    private fun destroyTree(process: Process) {
        runCatching {
            process.descendants().forEach { it.destroyForcibly() }
        }
        process.destroyForcibly()
    }

    private class ProcessOutputBudget(val limit: Int) {
        val consumed = AtomicInteger(0)
        val exceeded = AtomicBoolean(false)

        fun accept(size: Int): Boolean {
            val total = consumed.addAndGet(size)
            if (total > limit) exceeded.set(true)
            return !exceeded.get()
        }
    }

    private class ProcessOutputCapture(
        private val input: java.io.InputStream,
        private val budget: ProcessOutputBudget,
    ) {
        private val output = ByteArrayOutputStream()

        fun start(name: String): Thread = Thread {
            val buffer = ByteArray(1024)
            input.use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (!budget.accept(count)) break
                    output.write(buffer, 0, count)
                }
            }
        }.apply {
            this.name = name
            isDaemon = true
            start()
        }

        fun text(charset: Charset): String = output.toByteArray().toString(charset)
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
