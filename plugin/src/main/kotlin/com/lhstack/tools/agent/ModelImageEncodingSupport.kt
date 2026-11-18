package com.lhstack.tools.agent

import com.lhstack.tools.llm.ImageMediaType
import com.lhstack.tools.vendor.jsvg.parser.LoaderContext
import com.lhstack.tools.vendor.jsvg.parser.SVGLoader
import com.lhstack.tools.vendor.jsvg.parser.resources.ResourcePolicy
import com.lhstack.tools.vendor.jsvg.view.ViewBox
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/** 只转换发给模型的图片字节；编码格式和媒体类型必须一致。 */
internal object ModelImageEncodingSupport {
    private const val MAX_DIMENSION = 1568
    private const val MAX_INLINE_BYTES = 3 * 1024 * 1024

    data class EncodedImage(val bytes: ByteArray, val mediaType: ImageMediaType)

    fun encode(bytes: ByteArray, svg: Boolean): EncodedImage {
        // IDE 会向 ImageIO 注册 SVG Reader；SVG 必须直接走专用渲染路径。
        if (svg) return rasterizeSvg(bytes)
        readRaster(bytes)?.let { return it }
        // JDK 不一定安装 WebP reader；传输格式本身支持 WebP，不把它当成 PNG。
        if (bytes.size >= 20 && bytes.startsWithAscii("RIFF", 0) && bytes.startsWithAscii("WEBP", 8) &&
            listOf("VP8 ", "VP8L", "VP8X").any { bytes.startsWithAscii(it, 12) }
        ) return EncodedImage(bytes, ImageMediaType.WEBP)
        throw IllegalArgumentException("不支持或无法解码的图片格式")
    }

    private fun rasterizeSvg(bytes: ByteArray): EncodedImage {
        // 只渲染本次读取的 SVG，不允许它再访问磁盘或网络资源。
        val context = LoaderContext.builder().externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL).build()
        val document = bytes.inputStream().use { SVGLoader().load(it, null, context) }
        requireNotNull(document) { "SVG 文档无法解析" }
        val (width, height) = document.size().let { it.width to it.height }
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0) { "SVG 图片尺寸无效" }
        val scale = minOf(1.0, MAX_DIMENSION.toDouble() / maxOf(width, height))
        val image = BufferedImage(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            document.render(null, graphics, ViewBox(image.width.toFloat(), image.height.toFloat()))
        } finally {
            graphics.dispose()
        }
        return encodePng(image)
    }

    private fun readRaster(bytes: ByteArray): EncodedImage? =
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.setInput(input, true, true)
                val mediaType = ImageMediaType.fromMimeType("image/${reader.formatName.lowercase()}")
                val dimension = maxOf(reader.getWidth(0), reader.getHeight(0))
                val params = reader.defaultReadParam
                val sample = (dimension / MAX_DIMENSION).coerceAtLeast(1)
                if (sample > 1) params.setSourceSubsampling(sample, sample, 0, 0)
                val image = reader.read(0, params)
                when {
                    mediaType == null -> encodePng(image)
                    dimension <= MAX_DIMENSION && bytes.size <= MAX_INLINE_BYTES -> EncodedImage(bytes, mediaType)
                    else -> encodeJpeg(image)
                }
            } finally {
                reader.dispose()
            }
        }

    private fun encodePng(image: BufferedImage): EncodedImage {
        val scaled = resize(image, BufferedImage.TYPE_INT_ARGB)
        val bytes = ByteArrayOutputStream().use { out ->
            check(ImageIO.write(scaled, "png", out)) { "PNG 编码失败" }
            out.toByteArray()
        }
        return EncodedImage(bytes, ImageMediaType.PNG)
    }

    private fun encodeJpeg(image: BufferedImage): EncodedImage {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(out).use { output ->
                writer.output = output
                val params = writer.defaultWriteParam.apply {
                    compressionMode = JPEGImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.86f
                }
                writer.write(null, IIOImage(resize(image, BufferedImage.TYPE_INT_RGB), null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return EncodedImage(out.toByteArray(), ImageMediaType.JPEG)
    }

    private fun resize(image: BufferedImage, type: Int): BufferedImage {
        val ratio = minOf(1.0, MAX_DIMENSION.toDouble() / maxOf(image.width, image.height))
        if (ratio == 1.0 && (type == BufferedImage.TYPE_INT_ARGB || image.type == type)) return image
        val result = BufferedImage((image.width * ratio).toInt().coerceAtLeast(1), (image.height * ratio).toInt().coerceAtLeast(1), type)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(image, 0, 0, result.width, result.height, null)
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun ByteArray.startsWithAscii(text: String, offset: Int) = text.indices.all { this[offset + it] == text[it].code.toByte() }
}
