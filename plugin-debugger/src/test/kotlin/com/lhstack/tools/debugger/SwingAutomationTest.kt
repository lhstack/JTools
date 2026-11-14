package com.lhstack.tools.debugger

import com.google.gson.JsonObject
import com.lhstack.tools.plugins.SandboxHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

class SwingAutomationTest {
    @TempDir lateinit var evidence: Path

    private class Button : JButton("apply") {
        override fun isShowing() = true
    }

    private class Host(val root: JPanel) : SandboxHost {
        override fun panel(panelId: String): JComponent {
            require(panelId == "panel") { "面板不存在" }
            check(SwingUtilities.isEventDispatchThread())
            return root
        }
        override fun execute(command: String, argumentsJson: String): String = error("unused")
        override fun browser(panelId: String): Any = error("unused")
    }

    private fun setup(button: Button): Pair<SwingAutomation, JsonObject> {
        val root = JPanel()
        SwingUtilities.invokeAndWait { root.add(button) }
        val swing = SwingAutomation(Host(root), evidence)
        val snapshot = json.toJsonTree(swing.call("snapshot", args())).asJsonObject
        val ref = snapshot.getAsJsonArray("children").single().asJsonObject.text("ref")
        return swing to args().apply { addProperty("ref", ref); addProperty("action", "click") }
    }

    private fun args() = JsonObject().apply { addProperty("panel_id", "panel") }
    private fun status(swing: SwingAutomation, id: String): JsonObject =
        json.toJsonTree(swing.call("operation", JsonObject().apply { addProperty("operation_id", id) })).asJsonObject

    private fun finished(swing: SwingAutomation, id: String): JsonObject {
        SwingUtilities.invokeAndWait { }
        return status(swing, id)
    }

    @Test
    fun `click returns queued id and completes after callback without claiming business success`() {
        val button = Button()
        var clicks = 0
        button.addActionListener { clicks++ }
        val (swing, args) = setup(button)
        lateinit var submitted: JsonObject
        SwingUtilities.invokeAndWait {
            submitted = json.toJsonTree(swing.call("action", args)).asJsonObject
            assertEquals("QUEUED", submitted.text("state"))
            assertEquals("QUEUED", status(swing, submitted.text("operation_id")).text("state"))
            assertEquals(0, clicks)
        }
        val completed = finished(swing, submitted.text("operation_id"))
        assertEquals("COMPLETED", completed.text("state"))
        assertFalse(completed.get("business_completion_verified").asBoolean)
        assertEquals(1, clicks)
    }

    @Test
    fun `paused callback beyond edt timeout stays queryable and completes after resume`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val button = Button().apply {
            addActionListener {
                entered.countDown()
                check(resume.await(40, TimeUnit.SECONDS)) { "test resume timeout" }
            }
        }
        val (swing, args) = setup(button)
        val submission = CompletableFuture.supplyAsync { json.toJsonTree(swing.call("action", args)).asJsonObject }
        try {
            val submitted = submission.get(5, TimeUnit.SECONDS)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val id = submitted.text("operation_id")
            assertEquals("RUNNING", status(swing, id).text("state"))
            Thread.sleep(21_000)
            assertEquals("RUNNING", CompletableFuture.supplyAsync { status(swing, id) }.get(2, TimeUnit.SECONDS).text("state"))
            resume.countDown()
            assertEquals("COMPLETED", finished(swing, id).text("state"))
        } finally {
            resume.countDown()
            SwingUtilities.invokeAndWait { }
        }
    }

    @Test
    fun `callback exception is retained as failed with stack trace`() {
        val button = Button().apply { addActionListener { error("callback failure") } }
        val (swing, args) = setup(button)
        val submitted = json.toJsonTree(swing.call("action", args)).asJsonObject
        val failed = finished(swing, submitted.text("operation_id"))
        assertEquals("FAILED", failed.text("state"))
        assertContains(failed.text("error"), "callback failure")
        assertContains(failed.text("stack"), "SwingAutomationTest")
    }

    @Test
    fun `invalid reference and disabled button fail before submission`() {
        val button = Button()
        val (swing, args) = setup(button)
        SwingUtilities.invokeAndWait { button.isEnabled = false }
        assertContains(assertFailsWith<ExecutionException> { swing.call("action", args) }.cause!!.message!!, "不可用")
        args.addProperty("ref", "invalid")
        assertContains(assertFailsWith<ExecutionException> { swing.call("action", args) }.cause!!.message!!, "引用已失效")
    }

    @Test
    fun `button detached after submission is failed without invoking callback`() {
        val button = Button()
        var clicks = 0
        button.addActionListener { clicks++ }
        val (swing, args) = setup(button)
        lateinit var submitted: JsonObject
        SwingUtilities.invokeAndWait {
            submitted = json.toJsonTree(swing.call("action", args)).asJsonObject
            button.parent.remove(button)
        }
        val failed = finished(swing, submitted.text("operation_id"))
        assertEquals("FAILED", failed.text("state"))
        assertContains(failed.text("error"), "不属于当前面板")
        assertEquals(0, clicks)
    }

    @Test
    fun `unknown operation fails explicitly without accessing edt`() {
        val (swing, _) = setup(Button())
        assertFailsWith<IllegalStateException> { status(swing, "missing") }
    }
}
