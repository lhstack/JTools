package com.lhstack.tools.agent.model.tools

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 用户 shell 环境快缓。
 *
 * 问题背景：bash 工具原本用 `-lc` 启动 shell。但 zsh/bash 的 login 模式只读
 * `.zprofile` / `.bash_profile`，不读 `.zshrc` / `.bashrc`，而 sdkman、nvm、fnm、gvm
 * 等版本管理器的初始化几乎都写在后者里。后果是那些工具要么找不到（shell
 * function，如 sdk / gvm），要么拿到全局版本而非版本管理器选定的版本（如 node / go）。
 *
 * 做法：用交互式登录 shell（`-ilc`）读一次完整用户环境，把结果缓存下来供后续
 * 命令复用。不去探测或 source 具体的版本管理器脚本——那需要猜用户装了什么、装在
 * 哪，清单永远不全；rc 文件本身就是用户环境的唯一权威声明，交由 shell 自己读。
 *
 * 缓存而不是每次重读，是因为 rc 里的 `eval $(fnm env)`、`source gvm` 这类初始化
 * 每次都要跑，开销在数十到数百毫秒。代价是用户改了 rc 后缓存会陈旧，因此对外
 * 提供 [refresh] 让调用方主动失效。
 *
 * 具体如何倒出环境由 [SelectedShell.envProbe] 声明：zsh/bash 用交互式登录 shell 读 rc，
 * PowerShell 改为加载 $PROFILE，cmd 与 POSIX sh 无可靠机制因而不探测。
 */
object ShellEnvironment {

    /** 解析超时。rc 里的版本管理器初始化可能较慢，给足余量。 */
    private const val RESOLVE_TIMEOUT_SECONDS = 20L

    /** 环境变量分隔符。用 NUL 而不是换行，因为变量值本身可能包含换行。 */
    private const val ENTRY_SEPARATOR = '\u0000'

    private val cache = AtomicReference<Map<String, String>?>(null)

    /**
     * 获取用户 shell 环境变量。首次调用解析并缓存，后续直接复用。
     * 解析失败时返回空环境并不写入缓存，下次调用会重试。
     */
    fun current(): Map<String, String> {
        cache.get()?.let { return it }
        val resolved = resolve() ?: return emptyMap()
        cache.compareAndSet(null, resolved)
        return cache.get() ?: resolved
    }

    /**
     * 丢弃缓存并立即重新解析，返回最新环境。
     * 用户新装了工具或改了 rc 后，无需重启 IDE 即可生效。
     */
    fun refresh(): Map<String, String> {
        cache.set(null)
        return current()
    }

    /** 当前是否已有缓存。仅用于向模型说明本次是否发生了解析。 */
    fun cached(): Boolean = cache.get() != null

    /**
     * 用交互式登录 shell 执行 `env` 并解析输出。
     *
     * 关键点：
     * - `-ilc`：同时读 login 与 interactive 启动文件，才能拿到 rc 里的 PATH 与函数
     * - `env -0`：用 NUL 分隔，避开多行变量值造成的歧义
     * - stdin 重定向到空：防止 rc 里的交互式读取阻塞进程
     * - stderr 丢弃：rc 在非 TTY 下常打印 banner 或警告，与环境解析无关
     */
    private fun resolve(): Map<String, String>? {
        val shell = SelectedShell.current()
        val probe = shell.envProbe ?: return null
        return runCatching {
            val builder = ProcessBuilder(shell.program, *probe.args, probe.dumpCommand)
                .redirectInput(nullInput())
                .redirectErrorStream(false)
            val process = builder.start()
            // rc/profile 在非 TTY 下常打印 banner 或告警，与环境解析无关，读掉即丢弃。
            process.errorStream.use { it.readBytes() }
            val raw = process.inputStream.use { it.readBytes() }
            if (!process.waitFor(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            parseEnv(raw.toString(shell.outputCharset)).takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** 空输入源：Unix 用 /dev/null，Windows 用 NUL，防止用户配置里的交互式读取阻塞进程。 */
    private fun nullInput(): ProcessBuilder.Redirect {
        val device = File(if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null")
        return ProcessBuilder.Redirect.from(device)
    }

    /** 解析 `env -0` 输出：NUL 分隔的 `KEY=VALUE` 序列。 */
    private fun parseEnv(text: String): Map<String, String> = text
        .split(ENTRY_SEPARATOR)
        .mapNotNull { entry ->
            if (entry.isEmpty()) return@mapNotNull null
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            entry.substring(0, separator) to entry.substring(separator + 1)
        }
        .toMap()
}
