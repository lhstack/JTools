package com.lhstack.tools.debugger

import com.intellij.openapi.project.Project
import com.lhstack.tools.plugins.IPlugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.awt.Container
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.test.*

/** 显式启用，消费各独立构建的真实产物；不是完整 IDE 生命周期验收。 */
class ExampleArtifactsTest {
    @Test
    @EnabledIfSystemProperty(named = "debugger.test.examples", matches = "true")
    fun `example packages isolate host dependencies and provide independent working panels`() {
        val root = Path.of(System.getProperty("debugger.test.examplesRoot"))
        val artifacts = listOf(
            root.resolve("maven/target/jtools-maven-example-1.0.0-plugin.jar"),
            root.resolve("gradle/build/libs/jtools-gradle-example-1.0.0-plugin.jar"),
            root.resolve("gradle-idea/build/libs/jtools-gradle-idea-example-1.0.0-plugin.jar")
        )
        val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            when (method.name) {
                "getName" -> "example-test-project"
                "getLocationHash" -> "example-test-hash"
                else -> error("测试未提供 Project.${method.name}")
            }
        } as Project
        artifacts.forEach { artifact ->
            assertTrue(Files.isRegularFile(artifact), "请先构建示例: $artifact")
            val entry = ZipFile(artifact.toFile()).use { jar ->
                val names = jar.entries().asSequence().map { it.name }.toList()
                assertFalse(names.any { it.startsWith("com/lhstack/tools/") || it.startsWith("com/intellij/") })
                listOf("icons/plugin.png" to 48, "icons/tab.png" to 14).forEach { (path, size) ->
                    val image = jar.getInputStream(jar.getEntry(path)).use { ImageIO.read(it) }
                    assertEquals(size, image.width)
                    assertEquals(size, image.height)
                }
                jar.getInputStream(jar.getEntry("META-INF/ToolsPlugin.txt")).bufferedReader().use { it.readText().trim() }
            }
            URLClassLoader(arrayOf(artifact.toUri().toURL()), javaClass.classLoader).use { loader ->
                val plugin = loader.loadClass(entry).getConstructor().newInstance() as IPlugin
                assertEquals(48, plugin.pluginIcon().iconWidth)
                assertEquals(14, plugin.pluginTabIcon().iconWidth)
                SwingUtilities.invokeAndWait {
                    val panel = plugin.createPanel(project)
                    val other = plugin.createPanel(project)
                    assertNotSame(panel, other)
                    val input = find(panel, "example-input") as JTextField
                    val button = find(panel, "example-apply") as JButton
                    input.text = "  verified-input  "
                    button.doClick()
                    assertEquals("已应用：verified-input", (find(panel, "example-result") as JLabel).text)
                    assertEquals("尚未应用", (find(other, "example-result") as JLabel).text)
                    if (entry.contains(".idea.")) {
                        assertContains((find(panel, "example-project") as JLabel).text, "example-test-project")
                    }
                }
            }
        }
        ZipFile(root.resolve("build/jtools-js-example-1.0.0-plugin.zip").toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("pluginInfo.json", "index.html", "app.js", "style.css", "icons/plugin.png", "icons/tab.png"), names)
            listOf("icons/plugin.png" to 48, "icons/tab.png" to 14).forEach { (path, size) ->
                val image = zip.getInputStream(zip.getEntry(path)).use { ImageIO.read(it) }
                assertEquals(size, image.width)
                assertEquals(size, image.height)
            }
        }
    }

    private fun find(component: java.awt.Component, name: String): java.awt.Component {
        if (component.name == name) return component
        if (component is Container) {
            component.components.forEach { child ->
                val found = findOrNull(child, name)
                if (found != null) return found
            }
        }
        error("找不到组件: $name")
    }

    private fun findOrNull(component: java.awt.Component, name: String): java.awt.Component? {
        if (component.name == name) return component
        return if (component is Container) component.components.firstNotNullOfOrNull { findOrNull(it, name) } else null
    }
}
