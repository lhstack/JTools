package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.lhstack.tools.plugins.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.application.PathManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Path
import javax.swing.*

/** 此插件只管理基础设施；被调试产物从不安装到控制端 JTools。 */
class DebuggerPlugin : IPlugin {
    private val controller = SandboxController()
    // JTools PluginManager keeps this extension instance at application scope.
    private val mcpService = lazy { DebuggerMcpServer(dispatch, com.intellij.openapi.application.ApplicationManager.getApplication().messageBus,
        DebuggerMcpSettings(Path.of(System.getProperty("user.home"), ".jtools", "plugin-debugger", "mcp.json"))) }
    private val discovery by lazy {
        JetBrainsDiscovery(currentIde = Path.of(PathManager.getHomePath()), currentPlugins = Path.of(PathManager.getPluginsPath()),
            currentJtools = PluginManagerCore.getPlugin(PluginId.getId("com.lhstack.tools"))?.pluginPath)
    }
    private val dispatch by lazy {
        DebuggerCommands(controller, discovery,
            ::runtimeJar, { Path.of(PathManager.getSystemPath(), "jtools-debugger", "sandboxes") })
    }
    private fun runtimeJar(): Path {
        val resource = checkNotNull(javaClass.getResource("/com/lhstack/tools/debugger/DebuggerPlugin.class")) { "缺少 debugger 类资源" }
        val connection = resource.openConnection()
        check(connection is java.net.JarURLConnection) { "debugger 必须从完整 MCP JAR 安装；解压目录运行时请显式提供 debugger_jar" }
        return Path.of(connection.jarFileURL.toURI())
    }
    override fun install() {
        try { mcpService.value.restore() }
        catch (failure: Exception) {
            com.intellij.openapi.diagnostic.Logger.getInstance(DebuggerPlugin::class.java)
                .warn("Debugger MCP 自动恢复失败；请查看 debugger 面板", failure)
        }
    }
    override fun pluginName() = "插件沙箱调试器"
    override fun pluginDesc() = "AI 独立 IDE 沙箱、界面自动化、JCEF 与 JVM 调试"
    override fun pluginVersion() = "0.2.0"
    // 由 IDE 自动选择 _dark.svg 变体，并处理主题切换及高 DPI 缩放。
    override fun pluginIcon(): Icon = IconLoader.getIcon("/icons/debugger.svg", javaClass)
    override fun pluginTabIcon(): Icon = IconLoader.getIcon("/icons/debuggerTab.svg", javaClass)
    override fun createPanel(project: Project): JComponent = DebuggerMcpPanel(
        mcpService.value, ::runtimeJar)
    override fun functionCallings(project: Project): List<FunctionCalling> {
        val projectHash = project.locationHash
        return listOf(object : FunctionCalling {
            override fun name() = DebuggerCommands.TOOL
            override fun description() = DebuggerCommands.DESCRIPTION
            override fun parameters() = DebuggerCommands.SCHEMA
            override fun call(argumentsJson: String): String = error("沙箱调试必须在有效会话中调用")

            override fun call(argumentsJson: String, sessionName: String?, sessionId: String?): String {
                require(!sessionName.isNullOrBlank() && !sessionId.isNullOrBlank()) { "沙箱调试缺少有效会话名称或 ID" }
                val owner = "$projectHash:$sessionId"
                val args = json.fromJson(argumentsJson, JsonObject::class.java)
                return json.toJson(dispatch.call(owner, args.text("command"), args.getAsJsonObject("arguments") ?: JsonObject()))
            }
        })
    }
    override fun closeProject(project: Project) {
        controller.closeProject(project.locationHash)
    }
    private fun close() {
        if (mcpService.isInitialized()) mcpService.value.close()
        controller.close()
    }
    override fun unInstall() {
        if (mcpService.isInitialized()) mcpService.value.stop()
        close()
    }
    override fun appClose() = close()
}
