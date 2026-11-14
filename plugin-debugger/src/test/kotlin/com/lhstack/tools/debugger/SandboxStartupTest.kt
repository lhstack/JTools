package com.lhstack.tools.debugger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SandboxStartupTest {
    @TempDir lateinit var directory: Path

    @Test fun `dedicated profile survives restart but runtime state is always new`() {
        val firstRoot = SandboxProfile.acquire(directory, "project:session:ide").use { profile ->
            val first = SandboxLayout(directory.resolve("first"), profile.root)
            Files.writeString(profile.root.resolve("config/initialized"), "saved")
            assertEquals(profile.root.resolve("config"), first.configPath)
            assertEquals(profile.root.resolve("home"), first.homePath)
            profile.root
        }
        SandboxProfile.acquire(directory, "project:session:ide").use { profile ->
            assertEquals(firstRoot, profile.root)
            assertEquals("saved", Files.readString(profile.root.resolve("config/initialized")))
            val next = SandboxLayout(directory.resolve("next"), profile.root)
            val fresh = SandboxLayout(directory.resolve("fresh"))
            assertNotEquals(next.root, fresh.root)
            assertNotEquals(next.id, fresh.id)
            assertNotEquals(next.token, fresh.token)
            assertEquals(fresh.root.resolve("config"), fresh.configPath)
            assertEquals(fresh.root.resolve("home"), fresh.homePath)
            val options = next.vmOptions("-Xmx512m", 5005, 5006)
            assertContains(options, "-Didea.config.path=${profile.root.resolve("config")}")
            assertContains(options, "-Djtools.home=${next.root.resolve("jtools")}")
            assertFalse(options.contains("daily"))
        }
    }

    @Test fun `jcef settings and debugger share the managed cdp port without combined switches`() {
        val layout = SandboxLayout(directory.resolve("cdp"))
        val options = layout.vmOptions("-Xmx512m", 5005, 5006).lines()
        assertEquals(listOf("-Djtools.cdp.port=5006"), options.filter { it.startsWith("-Djtools.cdp.port=") })
        assertEquals(listOf("-Dide.browser.jcef.debug.port=5006"), options.filter { it.startsWith("-Dide.browser.jcef.debug.port=") })
        assertEquals(
            listOf("-Dide.browser.jcef.extra.args=--remote-debugging-address=127.0.0.1"),
            options.filter { it.startsWith("-Dide.browser.jcef.extra.args=") }
        )
        assertFalse(options.any { it.contains("--remote-debugging-port=") })
        listOf("-Dide.browser.jcef.debug.port=9222", "-Dide.browser.jcef.extra.args=--remote-debugging-port=9222").forEach { conflict ->
            assertFailsWith<IllegalArgumentException> { layout.vmOptions("-Xmx512m\n$conflict", 5005, 5006) }
        }
    }

    @Test fun `profile lock prevents parallel reuse and releases on close`() {
        SandboxProfile.acquire(directory, "same").use {
            assertFailsWith<IllegalStateException> { SandboxProfile.acquire(directory, "same") }
            SandboxProfile.acquire(directory, "different").use { other ->
                assertNotEquals(it.root, other.root)
            }
        }
        SandboxProfile.acquire(directory, "same").use { assertTrue(Files.isDirectory(it.root)) }
    }

    @Test fun `unmarked and symlink profiles cannot import personal settings`() {
        val store = Files.createDirectory(directory.resolve(".jtools-debug-profiles"))
        Files.createDirectory(store.resolve(SandboxProfile.key("unmarked")))
        assertFailsWith<IllegalArgumentException> { SandboxProfile.acquire(directory, "unmarked") }
        val personal = Files.createDirectory(directory.resolve("personal"))
        Files.createSymbolicLink(store.resolve(SandboxProfile.key("linked")), personal)
        assertFailsWith<IllegalArgumentException> { SandboxProfile.acquire(directory, "linked") }
    }

    @Test fun `sandbox disables optional prompts collection and reporting without accepting agreements`() {
        val options = SandboxLayout(directory.resolve("runtime")).vmOptions(
            "-Xmx512m\n-Djb.consents.confirmation.enabled=true\n-Didea.disable.collect.statistics=false", 5005, 5006
        )
        assertContains(options, "-Djb.consents.confirmation.enabled=false")
        assertContains(options, "-Didea.suppress.statistics.report=true")
        assertContains(options, "-Didea.disable.collect.statistics=true")
        assertContains(options, "-Dide.show.tips.on.startup.default.value=false")
        assertContains(options, "-Dide.experimental.ui.onboarding=false")
        assertFalse(options.contains("-Djb.consents.confirmation.enabled=true"))
        assertFalse(options.contains("privacy.policy"))
        assertFalse(options.contains("eua"))
    }

    @Test fun `only generated project is trusted in fresh and reused configurations`() {
        val profile = Files.createDirectory(directory.resolve("profile"))
        val first = SandboxLayout(directory.resolve("first & \"quoted\""), profile)
        fun paths(layout: SandboxLayout): List<String> {
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(layout.configPath.resolve("options/trusted-paths.xml").toFile())
            val options = document.getElementsByTagName("option")
            return (0 until options.length).map { options.item(it) as org.w3c.dom.Element }
                .filter { it.hasAttribute("value") }.map { it.getAttribute("value") }
        }
        assertEquals(listOf(first.root.resolve("project").toRealPath().toString()), paths(first))
        val next = SandboxLayout(directory.resolve("next"), profile)
        assertEquals(listOf(next.root.resolve("project").toRealPath().toString()), paths(next))
        val fresh = SandboxLayout(directory.resolve("fresh"))
        assertEquals(listOf(fresh.root.resolve("project").toRealPath().toString()), paths(fresh))
        assertFalse(next.vmOptions("-Xmx512m", 5005, 5006).contains("trust.all"))
    }

    @Test fun `invalid trust configuration fails instead of silently bypassing security`() {
        val config = Files.createDirectories(directory.resolve("config/options"))
        Files.writeString(config.resolve("trusted-paths.xml"), "<unexpected/>")
        val project = Files.createDirectory(directory.resolve("project"))
        assertFailsWith<IllegalArgumentException> { SandboxProjectTrust.prepare(config.parent, project) }
    }

    @Test fun `startup diagnostics explain agreement flow without claiming a confirmed blocker`() {
        val message = SandboxStartupDiagnostics.describe("at com.intellij.ide.gdpr.AgreementUiKt.showAgreementUi")
        assertContains(message, "可能阻塞")
        assertContains(message, "不会自动接受协议")
        assertContains(SandboxStartupDiagnostics.describe("ConfigImportHelper"), "不要导入日常配置")
        assertContains(SandboxStartupDiagnostics.pendingMessage(directory), "尚未就绪")
        Files.writeString(directory.resolve("startup-error.txt"), "fixture startup failure")
        assertContains(SandboxStartupDiagnostics.pendingMessage(directory), "fixture startup failure")
    }
}
