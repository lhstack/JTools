package com.lhstack.tools.debugger

import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.StartupUiUtil
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.swing.Icon
import kotlin.test.*

class DebuggerIconsTest {
    private fun render(icon: Icon, size: Int): BufferedImage {
        assertEquals(size, icon.iconWidth)
        assertEquals(size, icon.iconHeight)
        return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            try { icon.paintIcon(null, graphics, 0, 0) } finally { graphics.dispose() }
        }
    }

    @Test
    fun `same plugin icons render light dark and light again with matching resources`() {
        val plugin = DebuggerPlugin()
        val preview = BufferedImage(420, 150, BufferedImage.TYPE_INT_RGB)
        val graphics = preview.createGraphics()
        try {
            val icon = plugin.pluginIcon()
            val tab = plugin.pluginTabIcon()
            val mainImages = mutableListOf<BufferedImage>()
            val tabImages = mutableListOf<BufferedImage>()
            listOf(false, true, false).forEachIndexed { index, dark ->
                IconLoader.setUseDarkIcons(dark)
                val mainImage = render(icon, 48)
                val tabImage = render(tab, 14)
                mainImages.add(mainImage)
                tabImages.add(tabImage)
                val suffix = if (dark) "_dark" else ""
                val expectedMain = render(IconLoader.getIcon("/icons/debugger$suffix.svg", javaClass), 48)
                val expectedTab = render(IconLoader.getIcon("/icons/debuggerTab$suffix.svg", javaClass), 14)
                assertEquals(expectedMain.getRGB(24, 30), mainImage.getRGB(24, 30))
                assertEquals(expectedTab.getRGB(6, 9), tabImage.getRGB(6, 9))
                assertEquals(0, mainImage.getRGB(0, 0) ushr 24)
                assertEquals(0, tabImage.getRGB(0, 0) ushr 24)
                if (index < 2) {
                    val x = index * 210
                    graphics.color = if (dark) Color(0x2B2D30) else Color(0xF7F8FA)
                    graphics.fillRect(x, 0, 210, 150)
                    graphics.color = if (dark) Color(0xDFE1E5) else Color(0x333333)
                    graphics.drawString(if (dark) "DARK" else "LIGHT", x + 18, 24)
                    graphics.drawImage(mainImage, x + 18, 44, null)
                    graphics.drawImage(tabImage, x + 110, 61, null)
                    graphics.drawString("48 x 48", x + 18, 120)
                    graphics.drawString("14 x 14", x + 100, 120)
                }
            }
            assertNotEquals(mainImages[0].getRGB(24, 30), mainImages[1].getRGB(24, 30))
            assertNotEquals(tabImages[0].getRGB(6, 9), tabImages[1].getRGB(6, 9))
            assertEquals(mainImages[0].getRGB(24, 30), mainImages[2].getRGB(24, 30))
            assertEquals(tabImages[0].getRGB(6, 9), tabImages[2].getRGB(6, 9))
            val output = Path.of(System.getProperty("debugger.test.output"), "debugger-icons-preview.png")
            Files.createDirectories(output.parent)
            assertTrue(ImageIO.write(preview, "png", output.toFile()))
        } finally {
            graphics.dispose()
            IconLoader.setUseDarkIcons(StartupUiUtil.isUnderDarcula)
            plugin.unInstall()
        }
    }

    @Test
    fun `packaged jar contains all four svg resources`() {
        ZipFile(System.getProperty("debugger.test.jar")).use { zip ->
            listOf("debugger", "debugger_dark", "debuggerTab", "debuggerTab_dark").forEach { name ->
                val entry = assertNotNull(zip.getEntry("icons/$name.svg"), name)
                val expected = assertNotNull(javaClass.getResourceAsStream("/icons/$name.svg")).use { it.readBytes() }
                assertContentEquals(expected, zip.getInputStream(entry).use { it.readBytes() }, name)
            }
        }
    }
}
