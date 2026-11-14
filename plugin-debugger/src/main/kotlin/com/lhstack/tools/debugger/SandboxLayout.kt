package com.lhstack.tools.debugger

import com.google.gson.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal val json = Gson()
internal fun JsonObject.text(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: error("缺少参数: $name")
internal fun JsonObject.number(name: String, default: Int): Int = get(name)?.asInt ?: default

/** 运行数据完全新建；仅可显式使用调试器管理的专用 IDE 配置。 */
internal class SandboxLayout(val root: Path, private val profileRoot: Path? = null) {
    val configPath: Path = (profileRoot ?: root).resolve("config")
    val homePath: Path = (profileRoot ?: root).resolve("home")
    val id = UUID.randomUUID().toString()
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    init {
        require(!Files.exists(root)) { "沙箱目录必须不存在" }
        listOf("config", "system", "log", "plugins", "jtools", "project", "artifacts", "evidence", "home", "tmp").forEach { Files.createDirectories(root.resolve(it)) }
        Files.writeString(root.resolve("project/.sandbox-project"), id)
        SandboxProjectTrust.prepare(configPath, root.resolve("project"))
    }
    fun vmOptions(base: String, jdwpPort: Int, cdpPort: Int): String {
        require(jdwpPort in 1024..65535 && cdpPort in 1024..65535 && jdwpPort != cdpPort)
        val managed = listOf("-Djb.consents.confirmation.enabled=", "-Didea.suppress.statistics.report=", "-Didea.disable.collect.statistics=", "-Dide.show.tips.on.startup.default.value=", "-Dide.experimental.ui.onboarding=")
        val lines = base.lines().filter { it.isNotBlank() && managed.none { prefix -> it.trim().startsWith(prefix) } }
        val reserved = listOf("-javaagent", "-agentlib", "-agentpath", "-Didea.", "-Djtools.", "-Duser.home", "-Djava.io.tmpdir", "-Dide.browser.jcef.")
        require(lines.none { line -> reserved.any { line.trim().startsWith(it) } }) { "基础 VM options 含冲突路径或调试参数" }
        return (lines + listOf(
            "-Didea.config.path=$configPath", "-Didea.system.path=${root.resolve("system")}",
            "-Didea.log.path=${root.resolve("log")}", "-Didea.plugins.path=${root.resolve("plugins")}",
            "-Duser.home=$homePath", "-Djava.io.tmpdir=${root.resolve("tmp")}",
            "-Djb.consents.confirmation.enabled=false",
            "-Didea.suppress.statistics.report=true", "-Didea.disable.collect.statistics=true",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Dide.experimental.ui.onboarding=false",
            "-Djtools.home=${root.resolve("jtools")}", "-Djtools.sandbox=true", "-Djtools.sandbox.root=$root",
            "-Djtools.cdp.port=$cdpPort",
            "-Dide.browser.jcef.debug.port=$cdpPort",
            "-Dide.browser.jcef.extra.args=--remote-debugging-address=127.0.0.1",
            "--add-modules=jdk.jdi,jdk.httpserver",
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$jdwpPort"
        )).joinToString("\n", postfix = "\n")
    }
}
