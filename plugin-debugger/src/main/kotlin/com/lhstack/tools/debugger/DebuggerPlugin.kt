package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.lhstack.tools.plugins.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import java.awt.BorderLayout
import javax.swing.*

/** 此插件只管理基础设施；被调试产物从不安装到控制端 JTools。 */
class DebuggerPlugin : IPlugin {
    private val controller = SandboxController()
    override fun pluginName() = "插件沙箱调试器"
    override fun pluginDesc() = "AI 独立 IDE 沙箱、界面自动化、JCEF 与 JVM 调试"
    override fun pluginVersion() = "0.1.0"
    // 由 IDE 自动选择 _dark.svg 变体，并处理主题切换及高 DPI 缩放。
    override fun pluginIcon(): Icon = IconLoader.getIcon("/icons/debugger.svg", javaClass)
    override fun pluginTabIcon(): Icon = IconLoader.getIcon("/icons/debuggerTab.svg", javaClass)
    override fun createPanel(project: Project): JComponent = JPanel(BorderLayout()).apply {
        add(JLabel("通过 sandbox_debug 函数管理独立 IDE 沙箱；被调试插件不会正式安装。", SwingConstants.CENTER))
    }
    override fun functionCallings(project: Project): List<FunctionCalling> {
        val projectHash = project.locationHash
        return listOf(object : FunctionCalling {
            override fun name() = "sandbox_debug"
            override fun description() = "独立 IDE 沙箱调试。先用 help 获取命令与契约。被调试插件仅临时加载；JVM 断点控制在进程外。"
            override fun parameters() = """{"type":"object","properties":{"command":{"type":"string"},"arguments":{"type":"object"}},"required":["command"],"additionalProperties":false}"""
            override fun call(argumentsJson: String): String = error("沙箱调试必须在有效会话中调用")

            override fun call(argumentsJson: String, sessionName: String?, sessionId: String?): String {
                require(!sessionName.isNullOrBlank() && !sessionId.isNullOrBlank()) { "沙箱调试缺少有效会话名称或 ID" }
                val owner = "$projectHash:$sessionId"
                val args = json.fromJson(argumentsJson, JsonObject::class.java)
                return json.toJson(controller.call(owner, args.text("command"), args.getAsJsonObject("arguments") ?: JsonObject()))
            }
        })
    }
    override fun unInstall() { controller.close() }
    override fun appClose() { controller.close() }
}
