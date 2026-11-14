package com.lhstack.tools.debugger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

class JetBrainsDiscoveryTest {
    @TempDir lateinit var root: Path

    @Test fun `discovers non IDEA products and validates JTools plugin ID`() {
        val home = Files.createDirectory(root.resolve("home"))
        val products = Files.createDirectory(root.resolve("products"))
        val ide = Files.createDirectory(products.resolve("PyCharm"))
        Files.createDirectories(ide.resolve("bin"))
        Files.writeString(ide.resolve("bin/pycharm"), "launcher").toFile().setExecutable(true)
        Files.writeString(ide.resolve("bin/pycharm.vmoptions"), "-Xmx1024m")
        val os = System.getProperty("os.name").let { if (it.startsWith("Mac")) "macOS" else if (it.startsWith("Windows")) "Windows" else "Linux" }
        Files.writeString(ide.resolve("product-info.json"), """{"name":"PyCharm","productCode":"PY","version":"2025.3","dataDirectoryName":"PyCharm2025.3","launch":[{"os":"$os","arch":"${System.getProperty("os.arch")}","launcherPath":"bin/pycharm","vmOptionsFilePath":"bin/pycharm.vmoptions"}]}""")
        val plugins = Files.createDirectories(home.resolve("Library/Application Support/JetBrains/PyCharm2025.3/plugins"))
        fun plugin(name: String, id: String): Path {
            val path = Files.createDirectories(plugins.resolve("$name/lib"))
            JarOutputStream(Files.newOutputStream(path.resolve("plugin.jar"))).use {
                it.putNextEntry(JarEntry("META-INF/plugin.xml")); it.write("<idea-plugin><id>$id</id></idea-plugin>".toByteArray()); it.closeEntry()
            }
            return path.parent
        }
        val valid = plugin("anything", "com.lhstack.tools")
        plugin("jtools", "unrelated.plugin")
        val candidates = JetBrainsDiscovery(home, searchRoots = listOf(products), inspectProcesses = false).discover()
        assertEquals(1, candidates.size)
        assertEquals("PY", candidates.single().code)
        assertEquals("PyCharm", candidates.single().product)
        assertEquals(listOf(valid.toRealPath()), candidates.single().distributions)
    }

    @Test fun `missing install reports empty candidates rather than fabricating paths`() {
        val candidates = JetBrainsDiscovery(root, searchRoots = emptyList(), inspectProcesses = false).discover()
        assertTrue(candidates.isEmpty())
    }
}
