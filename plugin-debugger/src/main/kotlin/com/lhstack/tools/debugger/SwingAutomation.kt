package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.lhstack.tools.plugins.SandboxHost
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.text.JTextComponent

internal class SwingAutomation(private val host: SandboxHost, private val evidence: Path) {
    private data class Ref(val panel: String, val component: Component)
    private class ClickOperation(val id: String = UUID.randomUUID().toString()) {
        @Volatile var result: Map<String, Any?> = mapOf("state" to "QUEUED")
        fun snapshot(): Map<String, Any?> = mapOf(
            "operation_id" to id, "business_completion_verified" to false
        ) + result
    }
    private val refs = mutableMapOf<String, Ref>()
    private val clicks = ConcurrentHashMap<String, ClickOperation>()
    fun invalidate() = edt { refs.clear() }
    fun call(command: String, args: JsonObject): Any? {
        // 断点可能暂停 EDT；状态查询不能排入 EDT 或等待点击回调。
        if (command == "operation") return (clicks[args.text("operation_id")]
            ?: error("点击操作不存在或记录已过期")).snapshot()
        return edt { callOnEdt(command, args) }
    }
    private fun callOnEdt(command: String, args: JsonObject): Any? {
        val panelId = args.text("panel_id")
        val root = host.panel(panelId)
        return when (command) {
            "snapshot" -> {
                refs.clear()
                var remaining = 2000
                fun describe(component: Component): Map<String, Any?> {
                    check(remaining-- > 0) { "控件数超过2000，请缩小界面" }
                    val ref = UUID.randomUUID().toString()
                    refs[ref] = Ref(panelId, component)
                    val text = when (component) {
                        is JPasswordField -> "[redacted]"
                        is JTextComponent -> component.text.take(2000)
                        is AbstractButton -> component.text
                        is JLabel -> component.text
                        else -> null
                    }
                    return mapOf("ref" to ref, "type" to component.javaClass.name, "name" to component.name,
                        "text" to text, "enabled" to component.isEnabled, "showing" to component.isShowing,
                        "bounds" to listOf(component.x, component.y, component.width, component.height),
                        "selected" to when (component) { is JComboBox<*> -> component.selectedIndex; is JList<*> -> component.selectedIndices.toList(); is JTable -> component.selectedRows.toList(); else -> null },
                        "children" to if (component is Container) component.components.map { describe(it) } else emptyList<Any>())
                }
                describe(root)
            }
            "action" -> {
                val target = refs[args.text("ref")] ?: error("控件引用已失效，请重新snapshot")
                require(target.panel == panelId) { "控件不属于当前面板" }
                val component = validateTarget(target, root)
                when (args.text("action")) {
                    "click" -> return submitClick(target, component as? AbstractButton
                        ?: error("语义click仅支持按钮；自绘控件需真实输入适配"))
                    "input" -> (component as? JTextComponent ?: error("input需要文本控件")).text = args.text("text")
                    "select" -> {
                        val index = args.get("index").asInt
                        when (component) {
                            is JComboBox<*> -> { require(index in 0 until component.itemCount); component.selectedIndex = index }
                            is JList<*> -> { require(index in 0 until component.model.size); component.selectedIndex = index }
                            is JTable -> { require(index in 0 until component.rowCount); component.setRowSelectionInterval(index, index) }
                            is JTabbedPane -> { require(index in 0 until component.tabCount); component.selectedIndex = index }
                            is JTree -> { require(index in 0 until component.rowCount); component.setSelectionRow(index) }
                            else -> error("此控件不支持select")
                        }
                    }
                    "scroll" -> (component as? JComponent ?: error("不可滚动")).scrollRectToVisible(Rectangle(args.number("x", 0), args.number("y", 0), 1, 1))
                    else -> error("未知交互")
                }
                mapOf("dispatched" to true, "business_completion_verified" to false)
            }
            "screenshot" -> {
                check(root.isShowing && root.width > 0 && root.height > 0) { "面板尚未显示" }
                val point = root.locationOnScreen
                val image = Robot(root.graphicsConfiguration.device).createScreenCapture(Rectangle(point.x, point.y, root.width, root.height))
                Files.createDirectories(evidence)
                val path = evidence.resolve("swing-${UUID.randomUUID()}.png")
                check(ImageIO.write(image, "png", path.toFile()))
                mapOf("image_path" to path.toString(), "width" to image.width, "height" to image.height, "capture_kind" to "screen_region", "occlusion_possible" to true)
            }
            else -> error("未知Swing命令: $command")
        }
    }
    private fun validateTarget(target: Ref, root: Component): Component {
        val component = target.component
        require(component === root || SwingUtilities.isDescendingFrom(component, root)) { "控件不属于当前面板" }
        check(component.isEnabled && component.isShowing) { "控件不可见或不可用" }
        return component
    }

    private fun submitClick(target: Ref, button: AbstractButton): Map<String, Any?> {
        // 保留最近操作；不驱逐仍在等待或执行的操作，也不无限累积历史。
        if (clicks.size >= 1000) {
            val completed = clicks.entries.firstOrNull {
                it.value.result["state"] in setOf("COMPLETED", "FAILED")
            } ?: error("待完成点击操作过多，请先恢复或终止沙箱")
            clicks.remove(completed.key)
        }
        val operation = ClickOperation()
        clicks[operation.id] = operation
        val submitted = operation.snapshot() + mapOf("dispatched" to true)
        SwingUtilities.invokeLater {
            operation.result = mapOf("state" to "RUNNING")
            try {
                validateTarget(target, host.panel(target.panel))
                button.doClick(0)
                operation.result = mapOf("state" to "COMPLETED")
            } catch (error: Throwable) {
                operation.result = mapOf(
                    "state" to "FAILED", "error" to error.toString(), "stack" to error.stackTraceToString()
                )
            }
        }
        return submitted
    }

    private fun <T> edt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        try { return task.get(20, TimeUnit.SECONDS) }
        catch (error: java.util.concurrent.TimeoutException) {
            task.cancel(false)
            throw IllegalStateException("EDT操作超时；已开始的操作可能仍在执行，请查询状态或重启沙箱", error)
        }
    }
}
