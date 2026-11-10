package com.lhstack.tools.agent.resources

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/** Display thumbnails are independent of model-resource encoding. Call off EDT. */
object LocalImagePreviewSupport {
    fun preview(file: File): Map<String, String> {
        require(file.isFile && file.length() <= 32L * 1024 * 1024) { "预览图片必须为普通文件且不超过 32 MiB" }
        val image = ImageIO.read(file) ?: error("无法解码本地图片")
        val scale = minOf(1.0, 2048.0 / maxOf(image.width, image.height))
        val preview = if (scale == 1.0) image else resize(image, scale)
        val bytes =
            ByteArrayOutputStream().use { out ->
                check(ImageIO.write(preview, "png", out)) { "图片编码失败" }
                out.toByteArray()
            }
        return mapOf(
            "url" to "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}",
            "revision" to "${file.lastModified()}:${file.length()}",
        )
    }

    private fun resize(image: BufferedImage, scale: Double): BufferedImage =
        BufferedImage(
                (image.width * scale).toInt().coerceAtLeast(1),
                (image.height * scale).toInt().coerceAtLeast(1),
                BufferedImage.TYPE_INT_ARGB,
            )
            .apply {
                val graphics = createGraphics()
                try {
                    graphics.drawImage(image, 0, 0, width, height, null)
                } finally {
                    graphics.dispose()
                }
            }
}
