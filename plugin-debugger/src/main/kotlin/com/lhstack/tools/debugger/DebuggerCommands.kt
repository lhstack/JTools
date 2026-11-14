package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/** Shared dispatch for JTools function calling and external MCP clients. */
internal class DebuggerCommands(
    private val controller: SandboxController,
    private val discovery: JetBrainsDiscovery,
    private val runtimeJar: () -> Path,
    private val sandboxParent: () -> Path,
) {
    fun call(owner: String, command: String, arguments: JsonObject): Any? {
        if (command == "help") return controller.call(owner, command, arguments)
        if (command == "discover") {
            val candidates = discovery.discover()
            return mapOf("candidates" to candidates.map { it.json() },
                "action" to when {
                    candidates.none { it.distributions.isNotEmpty() } -> "未找到已安装的 JTools：请用户确认 JetBrains IDE 或插件位置；未安装时在该 IDE 的 Plugins 中搜索 JTools，或从下载地址安装。"
                    candidates.count { it.distributions.isNotEmpty() } > 1 && candidates.none { it.current && it.distributions.size == 1 } -> "发现多个宿主，请用户选择并显式指定 start 路径。"
                    else -> "已找到宿主，可调用 start 自动使用唯一候选或当前宿主。"
                }, "marketplace" to JetBrainsDiscovery.DOWNLOAD, "releases" to JetBrainsDiscovery.RELEASES)
        }
        if (command == "start") return controller.call(owner, command, startArguments(arguments))
        return controller.call(owner, command, arguments)
    }

    private fun startArguments(original: JsonObject): JsonObject {
        val args = original.deepCopy()
        val keys = listOf("launcher", "vm_options", "jtools_distribution")
        if (keys.any { !args.has(it) }) {
            val candidates = discovery.discover().filter { it.distributions.isNotEmpty() }.filter { candidate ->
                (!args.has("launcher") || Path.of(args.text("launcher")).toRealPath() == candidate.launcher) &&
                    (!args.has("jtools_distribution") || Path.of(args.text("jtools_distribution")).toRealPath() in candidate.distributions)
            }
            val current = candidates.filter { it.current }
            val selected = if (current.size == 1) current.single() else candidates.singleOrNull()
            checkNotNull(selected) {
                "无法唯一定位 JetBrains IDE/JTools，先调用 discover；多个候选请用户选择，未找到请用户确认路径或安装 JTools：${JetBrainsDiscovery.DOWNLOAD}；发行包：${JetBrainsDiscovery.RELEASES}"
            }
            check(selected.distributions.size == 1 || args.has("jtools_distribution")) { "该宿主发现多份 JTools，请通过 discover 选择 jtools_distribution" }
            if (!args.has("launcher")) args.addProperty("launcher", selected.launcher.toString())
            if (!args.has("vm_options")) args.addProperty("vm_options", selected.vmOptions.toString())
            if (!args.has("jtools_distribution")) args.addProperty("jtools_distribution", selected.distributions.single().toString())
        }
        if (!args.has("debugger_jar")) args.addProperty("debugger_jar", runtimeJar().toRealPath().toString())
        if (!args.has("sandbox_parent")) {
            val parent = sandboxParent()
            Files.createDirectories(parent)
            args.addProperty("sandbox_parent", parent.toRealPath().toString())
        }
        return args
    }

    companion object {
        const val TOOL = "sandbox_debug"
        const val DESCRIPTION = "独立 JetBrains IDE 沙箱调试：help 查询命令，discover 自动发现宿主与 JTools；支持生命周期、methods/invoke、Swing、JVM、JCEF/CDP。请求超时不代表取消，不自动重发。"
        const val SCHEMA = """{"type":"object","properties":{"command":{"type":"string"},"arguments":{"type":"object"}},"required":["command"],"additionalProperties":false}"""
    }
}
