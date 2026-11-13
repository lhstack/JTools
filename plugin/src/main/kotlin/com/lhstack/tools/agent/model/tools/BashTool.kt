package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Shell 执行工具。
 *
 * 用系统 shell 执行命令，返回 stdout / stderr / exit_code / success；超限文件化交付。
 * cwd 省略时用 workspace 根目录；timeout_secs 未设置时默认 300s，设置后按该秒数超时。
 * 超时/取消时销毁进程树，返回明确状态并保留已捕获输出，success=false。
 *
 * 环境变量由 [ShellEnvironment] 提供：登录 shell 不读 .zshrc/.bashrc，而版本管理器
 * 多数安装在那里，因此单靠 -lc 会丢失用户级工具。详见 ShellEnvironment 注释。
 *
 * 与 awake 差异：Rust 用 setsid + SIGKILL 杀进程组，Kotlin 用 Process.destroyForcibly
 * 递归销毁（JDK9 descendants）。shell 选择照抄 selected_shell 的 zsh>bash>sh。
 */
class BashTool(
    private val workspace: WorkspaceTools,
    private val envVars: Map<String, String> = emptyMap(),
    private val cancel: ModelCancel? = null,
    private val maxOutputChars: Int = 8000,
) : ToolDyn {

    private val outputs = BashOutputSupport()

    /** ToolRuntime 外层超时。默认覆盖 300s，参数 timeout_secs 更大时由 ToolRuntime 再抬高。 */
    override val executionTimeoutSeconds: Long = 310L

    override fun definition(prompt: String): ToolDefinition {
        val shell = SelectedShell.current()
        val envHint = shell.envProbe?.let { "（${it.sourceLabel}）" } ?: "（当前 shell 无用户配置可加载）"
        return ToolDefinition(
            name = NAME,
            description = "在本机通过 ${shell.label} 执行 Shell 命令的工具，返回 stdout、stderr、退出码和成功标志。会自动加载用户 shell 环境${envHint}，因此可直接使用用户安装的命令行工具。支持指定工作目录、超时控制和进程树终止，输出超限时只返回摘要和 UTF-8 文件地址，不因输出量终止进程。需要内容时用普通 Shell 命令按文件路径分批读取。",
            parameters = JsonParser.parseString(
                """
                {
                    "type": "object",
                    "properties": {
                        "command": {
                            "type": "string",
                            "description": "必填。要执行的 Shell 命令。超限返回文件地址，可用 head/tail/sed 等按需读取，勿重复执行原命令。"
                        },
                        "cwd": {
                            "type": "string",
                            "description": "可选。命令的工作目录。省略时使用系统默认工作目录。"
                        },
                        "timeout_secs": {
                            "type": "integer",
                            "description": "可选。超时秒数。未设置时默认 300；设置后按该秒数超时并终止整个进程树。"
                        },
                        "refresh_vfs": {
                            "type": "boolean",
                            "description": "可选。默认 false。命令改动了工作区文件、且随后要用 read_project_files 等基于 IDE VFS 的工具读取时设为 true，刷新 IDE 缓存。纯查询命令保持 false 以免影响性能。"
                        },
                        "refresh_env": {
                            "type": "boolean",
                            "description": "可选。默认 false。用户 shell 环境在首次执行时解析并缓存，后续复用。当命令报 command not found 但该工具应当已安装，或用户提到刚安装工具、刚改过 shell 配置时，设为 true 重新解析环境后再执行本命令。"
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
        BashOutputSupport.validateConfiguredLimit(maxOutputChars.toString())
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
        // 未设置、0 或负值按默认 300 秒；设置了正数就按该秒数超时，不再夹取到 300。
        val timeoutSecs = obj.get("timeout_secs")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asLong?.takeIf { it > 0 } ?: 300
        val refreshVfs = obj.get("refresh_vfs")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean ?: false
        val refreshEnv = obj.get("refresh_env")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean ?: false

        val shell = SelectedShell.current()
        val builder = ProcessBuilder(shell.program, *shell.args, command)
            .directory(cwd)
            .redirectErrorStream(false)
        // 先应用用户 shell 环境（含 rc 文件里的 PATH 与版本管理器设置），再叠加调用方传入的
        // envVars，保证显式配置优先级高于推导出的用户环境。
        builder.environment().putAll(if (refreshEnv) ShellEnvironment.refresh() else ShellEnvironment.current())
        builder.environment().putAll(envVars)

        val capture = outputs.begin(maxOutputChars)
        var process: Process? = null
        try {
            process = builder.redirectOutput(capture.raw("stdout").toFile())
                .redirectError(capture.raw("stderr").toFile()).start()
            var timedOut = false
            var cancelled = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSecs)
            while (true) {
                if (cancel?.isCancelled() == true) {
                    cancelled = true
                    destroyTree(process)
                    break
                }
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    destroyTree(process)
                    break
                }
            }
            if (timedOut || cancelled) {
                check(process.waitFor(5, TimeUnit.SECONDS)) { "命令终止后仍未退出" }
            }
            val result = outputs.deliver(capture, shell.outputCharset, process.exitValue(), timedOut, cancelled, maxOutputChars)
            if (refreshVfs) refreshVfsAfterCommand(cwd)
            return result
        } catch (error: Throwable) {
            try {
                if (process?.isAlive == true) {
                    destroyTree(process)
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "命令终止后仍未退出" }
                }
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            try {
                outputs.discard(capture)
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
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

    companion object {
        const val NAME = "bash"
    }
}

/**
 * 用户环境探测策略。各 shell 的用户配置机制不同，因此由 shell 自己声明如何倒出
 * 完整用户环境，而不是在解析侧写死假设。
 *
 * @param args 探测用的 shell 参数，需能加载用户配置文件
 * @param dumpCommand 把环境变量以 NUL 分隔的 KEY=VALUE 写到 stdout 的命令
 * @param sourceLabel 用户配置来源描述，仅用于日志与诊断
 */
data class ShellEnvProbe(
    val args: Array<String>,
    val dumpCommand: String,
    val sourceLabel: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShellEnvProbe) return false
        return args.contentEquals(other.args) &&
            dumpCommand == other.dumpCommand &&
            sourceLabel == other.sourceLabel
    }

    override fun hashCode(): Int =
        31 * (31 * args.contentHashCode() + dumpCommand.hashCode()) + sourceLabel.hashCode()
}

/** 选中的 shell。照抄 awake 的 selected_shell：zsh > bash > sh（Windows: pwsh > powershell > cmd）。 */
data class SelectedShell(
    val label: String,
    val program: String,
    val args: Array<String>,
    /** 子进程 stdout/stderr 的实际编码：Windows 用系统 native 编码（代码页，如 GBK），Unix 用 UTF-8。 */
    val outputCharset: Charset,
    /**
     * 用户环境探测策略；null 表示该 shell 没有可靠的用户配置可加载。
     * 执行命令仍用 [args]，探测只在首次或显式刷新时发生。
     */
    val envProbe: ShellEnvProbe? = null,
) {
    companion object {
        /** zsh：版本管理器几乎都写在 .zshrc，而 login 模式不读它，必须叠加 -i。 */
        private val ZSH_PROBE = ShellEnvProbe(
            args = arrayOf("-ilc"),
            dumpCommand = "env -0",
            sourceLabel = ".zshenv/.zprofile/.zshrc",
        )

        /** bash：同理，.bashrc 仅在交互式下加载。 */
        private val BASH_PROBE = ShellEnvProbe(
            args = arrayOf("-ilc"),
            dumpCommand = "env -0",
            sourceLabel = ".bash_profile/.bashrc",
        )

        /**
         * PowerShell：执行命令时用 -NoProfile 保证确定性与启动速度，但用户安装的工具
         * 往往写在 $PROFILE 里。探测时改为加载 profile，并按 NUL 分隔输出环境变量。
         */
        private val PWSH_PROBE = ShellEnvProbe(
            args = arrayOf("-NonInteractive", "-Command"),
            dumpCommand = "[Console]::Out.Write(((Get-ChildItem env:)." +
                "ForEach-Object { \"\$(\$_.Name)=\$(\$_.Value)\" } -join \"`0\"))",
            sourceLabel = "\$PROFILE",
        )

        fun current(): SelectedShell {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                val winCharset = systemNativeCharset()
                if (commandInPath("pwsh")) {
                    return SelectedShell(
                        "pwsh",
                        "pwsh",
                        arrayOf("-NoProfile", "-NonInteractive", "-Command"),
                        winCharset,
                        envProbe = PWSH_PROBE,
                    )
                }
                if (commandInPath("powershell")) {
                    return SelectedShell(
                        "powershell",
                        "powershell",
                        arrayOf("-NoProfile", "-NonInteractive", "-Command"),
                        winCharset,
                        envProbe = PWSH_PROBE,
                    )
                }
                // cmd 没有用户级启动脚本机制（AutoRun 注册表项不可靠，也不应依赖）。
                return SelectedShell("cmd", "cmd", arrayOf("/C"), winCharset)
            }
            if (commandInPath("zsh")) {
                return SelectedShell("zsh", "zsh", arrayOf("-lc"), Charsets.UTF_8, envProbe = ZSH_PROBE)
            }
            if (commandInPath("bash")) {
                return SelectedShell("bash", "bash", arrayOf("-lc"), Charsets.UTF_8, envProbe = BASH_PROBE)
            }
            // POSIX sh 不读 .zshrc/.bashrc：其交互式启动文件由 $ENV 指定且默认不存在，
            // 加 -i 只会引入 "no job control" 噪音而拿不到任何额外环境；
            // 且降到 sh 意味着系统连 zsh/bash 都没有，本身不会有用户级工具链。
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
