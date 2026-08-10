package com.lhstack.tools.agent.model.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellEnvironmentTest {

    private fun bashToolSource(): String = Files.readString(
        Path.of("src/main/kotlin/com/lhstack/tools/agent/model/tools/BashTool.kt"),
    )

    private fun shellEnvironmentSource(): String = Files.readString(
        Path.of("src/main/kotlin/com/lhstack/tools/agent/model/tools/ShellEnvironment.kt"),
    )

    @Test
    fun `user environment is resolved with interactive login shell`() {
        // login shell 只读 .zprofile/.bash_profile，交互式才读 .zshrc/.bashrc，
        // 而 sdkman/nvm/fnm/gvm 的初始化通常写在后者。必须同时带 -i 才能拿到。
        val bash = bashToolSource()
        assertTrue(bash.contains("args = arrayOf(\"-ilc\")"))
        assertTrue(bash.contains("dumpCommand = \"env -0\""))
    }

    @Test
    fun `environment resolution does not block on stdin and drops rc noise`() {
        val source = shellEnvironmentSource()
        // rc 文件可能包含交互式读取，stdin 必须给空，否则进程会挂住。
        assertTrue(source.contains("Redirect.from"))
        assertTrue(source.contains("/dev/null"))
        // Windows 的空设备名不同，不能写死 /dev/null。
        assertTrue(source.contains("\"NUL\""))
        // rc 在非 TTY 下常打印 banner/告警，与环境解析无关，不能混进结果。
        assertTrue(source.contains("process.errorStream.use"))
    }

    @Test
    fun `only shells with rc files are probed`() {
        // pwsh/cmd 没有 rc 机制；POSIX sh 的 -i 行为在各实现间不一致。
        val source = shellEnvironmentSource()
        assertTrue(source.contains("val probe = shell.envProbe ?: return null"))

        val bash = bashToolSource()
        assertTrue(bash.contains("envProbe = ZSH_PROBE"))
        assertTrue(bash.contains("envProbe = BASH_PROBE"))
        // PowerShell 执行时用 -NoProfile，但探测时必须加载 profile，否则拿不到用户工具。
        assertTrue(bash.contains("envProbe = PWSH_PROBE"))
        // sh 与 cmd 无可靠用户配置机制，不传 envProbe。
        assertTrue(bash.contains("\"sh\", \"sh\", arrayOf(\"-lc\"), Charsets.UTF_8)"))
        assertTrue(bash.contains("\"cmd\", \"cmd\", arrayOf(\"/C\"), winCharset)"))
    }

    @Test
    fun `refresh_env parameter is exposed to the model`() {
        val source = bashToolSource()
        assertTrue(source.contains("\"refresh_env\""))
        assertTrue(source.contains("command not found"))
    }

    @Test
    fun `explicit env vars win over resolved user environment`() {
        // 调用方显式传入的 envVars 必须后写入，覆盖推导出的用户环境。
        val source = bashToolSource()
        val envBlock = source.substringAfter("val refreshEnv").substringBefore("val process")
        val userEnvAt = envBlock.indexOf("ShellEnvironment")
        val explicitAt = envBlock.indexOf("putAll(envVars)")
        assertTrue(userEnvAt in 0 until explicitAt)
    }

    @Test
    fun `refresh discards cache and reresolves`() {
        val source = shellEnvironmentSource()
        val refresh = source.substringAfter("fun refresh()").substringBefore("fun cached()")
        assertTrue(refresh.contains("cache.set(null)"))
        assertTrue(refresh.contains("return current()"))
    }

    @Test
    fun `failed resolution is not cached`() {
        // 解析失败返回空环境但不写缓存，下次调用会重试；
        // 否则一次偶发失败会把空环境固化到整个 IDE 会话。
        val source = shellEnvironmentSource()
        val current = source.substringAfter("fun current()").substringBefore("fun refresh()")
        assertTrue(current.contains("?: return emptyMap()"))
        assertTrue(current.contains("compareAndSet(null, resolved)"))
    }

    @Test
    fun `tool description states identity and capability without parameter usage`() {
        val source = bashToolSource()
        val desc = source.substringAfter("description = \"在本机通过").substringBefore("\",")
        // 工具描述只说明这是什么工具、用哪个 shell、有哪些能力。
        assertTrue(desc.contains("返回 stdout、stderr、退出码"))
        assertTrue(desc.contains("自动加载用户 shell 环境"))
        // 参数用法不应出现在工具描述里。
        assertFalse(desc.contains("refresh_env"))
        assertFalse(desc.contains("refresh_vfs"))
        assertFalse(desc.contains("省略 cwd"))
        assertFalse(desc.contains("head/tail/grep"))
    }

    @Test
    fun `parameter descriptions carry the usage contract`() {
        val source = bashToolSource()
        val params = source.substringAfter("parameters = JsonParser").substringBefore("trimIndent")
        assertTrue(params.contains("head/tail/grep"))
        assertTrue(params.contains("command not found"))
        assertTrue(params.contains("终止整个进程树"))
    }

    @Test
    fun `env output is split on NUL to survive multiline values`() {
        val source = shellEnvironmentSource()
        assertTrue(source.contains("'\\u0000'"))
        // 值里可能带 '='，只能按首个 '=' 切分。
        assertTrue(source.contains("entry.indexOf('=')"))
        assertTrue(source.contains("if (separator <= 0) return@mapNotNull null"))
    }

    @Test
    fun `resolved environment contains rc level tooling on this machine`() {
        // 真实解析一次：验证拿到的 PATH 比 login-only 更完整。
        // 无 zsh/bash 的环境下跳过，不做无意义断言。
        val shell = SelectedShell.current()
        if (shell.envProbe == null) return

        val env = ShellEnvironment.refresh()
        if (env.isEmpty()) return

        assertTrue(env.containsKey("PATH"))
        assertTrue(ShellEnvironment.cached())

        val loginOnly = ProcessBuilder(shell.program, "-lc", "printf %s \"\$PATH\"")
            .redirectErrorStream(false)
            .start()
            .let { p ->
                val text = p.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                p.waitFor()
                text
            }
        val interactive = env.getValue("PATH")
        // 交互式解析出的 PATH 不应短于 login-only，否则说明 -ilc 没生效。
        assertTrue(interactive.split(':').size >= loginOnly.split(':').size)
    }

    @Test
    fun `cached flag reflects resolution state`() {
        val shell = SelectedShell.current()
        if (shell.envProbe == null) return
        ShellEnvironment.refresh()
        assertEquals(true, ShellEnvironment.cached())
    }

    @Test
    fun `command execution keeps login shell args and injects resolved env`() {
        // 执行命令仍用 -lc；用户级工具通过注入解析出的环境获得，
        // 而不是把执行参数改成 -ilc，否则每条命令都要重跑一遍 rc 初始化。
        val source = bashToolSource()
        assertTrue(source.contains("ShellEnvironment.current()"))
        assertTrue(source.contains("ShellEnvironment.refresh()"))

        val currentFn = source
            .substringAfter("fun current(): SelectedShell {")
            .substringBefore("fun systemNativeCharset")
        assertTrue(currentFn.contains("arrayOf(\"-lc\")"))
        // -ilc 只属于探测策略，不得泄漏到执行路径。
        assertFalse(currentFn.contains("-ilc"))
    }

}
