package com.lhstack.tools.debugger

import com.lhstack.tools.plugins.JToolsPaths
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

class SandboxIsolationTest {
    @TempDir lateinit var directory: Path
    private fun properties() = Properties().apply { setProperty("user.home", directory.resolve("daily").toString()) }

    @Test fun `daily data path remains unchanged without sandbox configuration`() {
        assertEquals(directory.resolve("daily/.jtools"), JToolsPaths.home(properties()))
    }
    @Test fun `sandbox cannot silently fall back to daily data`() {
        assertFailsWith<IllegalArgumentException> { JToolsPaths.home(properties().apply { setProperty("jtools.sandbox", "true") }) }
        assertFailsWith<IllegalArgumentException> {
            JToolsPaths.home(properties().apply {
                setProperty("jtools.sandbox", "true")
                setProperty("jtools.sandbox.root", directory.toString())
                setProperty("jtools.home", directory.resolve("daily/.jtools/unsafe").toString())
            })
        }
    }
    @Test fun `parallel layouts have disjoint writable roots and unique authentication`() {
        val a = SandboxLayout(directory.resolve("a"))
        val b = SandboxLayout(directory.resolve("b"))
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.token, b.token)
        val props = properties().apply {
            setProperty("jtools.sandbox", "true")
            setProperty("jtools.sandbox.root", a.root.toString())
            setProperty("jtools.home", a.root.resolve("jtools").toString())
        }
        assertEquals(a.root.resolve("jtools").toRealPath(), JToolsPaths.home(props))
        val options = a.vmOptions("-Xmx512m", 5005, 5006)
        assertContains(options, "-Didea.config.path=${a.root.resolve("config")}")
        assertContains(options, "--remote-debugging-port=5006 --remote-debugging-address=127.0.0.1")
        assertContains(options, "-Djtools.cdp.port=5006")
        assertContains(options, "-Duser.home=${a.root.resolve("home")}")
        assertFalse(options.contains(b.root.toString()))
        assertFailsWith<IllegalArgumentException> { SandboxLayout(a.root) }
        assertTrue(Files.isDirectory(b.root.resolve("project")))
    }
    @Test fun `conflicting vm injection is rejected`() {
        val layout = SandboxLayout(directory.resolve("sandbox"))
        assertFailsWith<IllegalArgumentException> { layout.vmOptions("-agentlib:jdwp=bad", 5005, 5006) }
        assertFailsWith<IllegalArgumentException> { layout.vmOptions("-Djtools.home=/daily", 5005, 5006) }
        assertFailsWith<IllegalArgumentException> { layout.vmOptions("  -Dide.browser.jcef.extra.args=--remote-debugging-port=9222", 5005, 5006) }
        assertFailsWith<IllegalArgumentException> { layout.vmOptions("-Didea.system.path=/daily", 5005, 5006) }
        assertFailsWith<IllegalArgumentException> { layout.vmOptions("", 5005, 5005) }
    }
}
