package com.lhstack.tools.agent.model.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SelectedShellTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `Windows detects pwsh exe in PATH directories containing spaces`() {
        val directory = Files.createDirectory(root.resolve("PowerShell 7"))
        Files.createFile(directory.resolve("pwsh.exe"))

        val shell = SelectedShell.select("Windows 10", directory.toString())

        assertEquals("pwsh", shell.label)
        assertEquals("pwsh", shell.program)
        assertContentEquals(arrayOf("-NoProfile", "-NonInteractive", "-Command"), shell.args)
        assertNotNull(shell.envProbe)
    }

    @Test
    fun `Windows selects powershell exe when pwsh is absent`() {
        Files.createFile(root.resolve("powershell.exe"))

        val shell = SelectedShell.select("Windows 11", root.toString())

        assertEquals("powershell", shell.label)
        assertEquals("powershell", shell.program)
        assertContentEquals(arrayOf("-NoProfile", "-NonInteractive", "-Command"), shell.args)
        assertNotNull(shell.envProbe)
    }

    @Test
    fun `Windows prefers pwsh even when powershell appears earlier in PATH`() {
        val legacy = Files.createDirectory(root.resolve("legacy"))
        val modern = Files.createDirectory(root.resolve("modern"))
        Files.createFile(legacy.resolve("powershell.exe"))
        Files.createFile(modern.resolve("pwsh.exe"))

        val shell = SelectedShell.select("Windows 10", ";$legacy;;$modern;")

        assertEquals("pwsh", shell.label)
    }

    @Test
    fun `Windows ignores directories named like executables and selects cmd when shells are absent`() {
        Files.createDirectory(root.resolve("pwsh.exe"))
        Files.createDirectory(root.resolve("powershell.exe"))

        for (path in listOf(null, "", ";;", root.toString())) {
            val shell = SelectedShell.select("Windows 10", path)
            assertEquals("cmd", shell.label)
            assertContentEquals(arrayOf("/C"), shell.args)
            assertNull(shell.envProbe)
        }
    }

    @Test
    fun `Windows retains detection of extensionless shell files`() {
        Files.createFile(root.resolve("pwsh"))

        assertEquals("pwsh", SelectedShell.select("Windows 10", root.toString()).label)
    }

    @Test
    fun `Unix retains zsh bash sh priority without exe matching`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        Files.createFile(root.resolve("zsh.exe"))
        Files.createFile(root.resolve("bash.exe"))
        for (osName in listOf("Linux", "Mac OS X")) {
            assertEquals("sh", SelectedShell.select(osName, root.toString()).label)
        }
        Files.createFile(root.resolve("bash"))
        for (osName in listOf("Linux", "Mac OS X")) {
            assertEquals("bash", SelectedShell.select(osName, root.toString()).label)
        }
        Files.createFile(root.resolve("zsh"))
        for (osName in listOf("Linux", "Mac OS X")) {
            val shell = SelectedShell.select(osName, root.toString())
            assertEquals("zsh", shell.label)
            assertContentEquals(arrayOf("-lc"), shell.args)
            assertEquals(Charsets.UTF_8, shell.outputCharset)
        }
    }
}
