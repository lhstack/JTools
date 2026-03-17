package com.lhstack.tools.agent

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.Icon

object AgentToolbarIconSupport {
    private val targetSize = JBUI.size(16, 16)

    fun normalize(icon: Icon): Icon {
        val sourceWidth = icon.iconWidth.coerceAtLeast(1)
        val sourceHeight = icon.iconHeight.coerceAtLeast(1)
        val scale = minOf(
            targetSize.width.toDouble() / sourceWidth.toDouble(),
            targetSize.height.toDouble() / sourceHeight.toDouble(),
            1.0
        )
        val drawWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val drawHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val sourceImage = BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_ARGB)
        val sourceGraphics = sourceImage.createGraphics()
        try {
            icon.paintIcon(null, sourceGraphics, 0, 0)
        } finally {
            sourceGraphics.dispose()
        }
        val targetImage = BufferedImage(targetSize.width, targetSize.height, BufferedImage.TYPE_INT_ARGB)
        val targetGraphics = targetImage.createGraphics()
        try {
            targetGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            targetGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val x = (targetSize.width - drawWidth) / 2
            val y = (targetSize.height - drawHeight) / 2
            targetGraphics.drawImage(sourceImage, x, y, drawWidth, drawHeight, null)
        } finally {
            targetGraphics.dispose()
        }
        return object : Icon {
            override fun getIconWidth(): Int = targetSize.width
            override fun getIconHeight(): Int = targetSize.height

            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                g.drawImage(targetImage, x, y, null)
            }
        }
    }

    val minimumButtonSize: Dimension
        get() = JBUI.size(28, 28)
}
