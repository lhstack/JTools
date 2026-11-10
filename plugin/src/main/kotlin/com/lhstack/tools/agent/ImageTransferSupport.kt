package com.lhstack.tools.agent

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import okhttp3.OkHttpClient
import okhttp3.Request

/** Read original image bytes for clipboard/save; display thumbnails are never used as originals. */
internal object ImageTransferSupport {
    private const val MAX_BYTES = 32 * 1024 * 1024
    private val client by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }

    data class ImageData(val bytes: ByteArray, val filename: String) {
        fun transferable(): Transferable {
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("无法解码图片")
            return ImageTransferable(image)
        }
    }

    fun fromFile(file: File): ImageData {
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "图片必须为普通文件且不超过 32 MiB" }
        return decode(file.inputStream().use { it.readNBytes(MAX_BYTES + 1) }, file.name)
    }

    fun fromUrl(url: String, filename: String?): ImageData {
        if (url.startsWith("data:", true)) {
            val comma = url.indexOf(',')
            require(
                comma > 0 &&
                    url.substring(0, comma)
                        .matches(
                            Regex("data:image/[a-zA-Z0-9.+-]+;base64", RegexOption.IGNORE_CASE)
                        )
            ) {
                "只支持 base64 图片数据"
            }
            val encoded = url.substring(comma + 1)
            require(encoded.length <= ((MAX_BYTES + 2L) / 3 * 4)) { "图片超过 32 MiB" }
            return decode(Base64.getDecoder().decode(encoded), filename)
        }
        val uri = URI(url)
        require(uri.scheme?.lowercase() in setOf("http", "https") && uri.userInfo == null) {
            "不支持的图片地址"
        }
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "读取图片失败：HTTP ${response.code}" }
            val body = response.body ?: error("图片响应为空")
            require(body.contentLength() <= MAX_BYTES) { "图片超过 32 MiB" }
            val bytes = body.byteStream().use { it.readNBytes(MAX_BYTES + 1) }
            return decode(
                bytes,
                filename?.takeIf { it.isNotBlank() } ?: uri.path.substringAfterLast('/'),
            )
        }
    }

    private fun decode(bytes: ByteArray, filename: String?): ImageData {
        require(bytes.size in 1..MAX_BYTES) { "图片为空或超过 32 MiB" }
        val format =
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                require(readers.hasNext()) { "无法识别图片格式" }
                val reader = readers.next()
                try {
                    reader.input = input
                    reader.read(0) ?: error("无法解码图片")
                    reader.formatName.lowercase()
                } finally {
                    reader.dispose()
                }
            }
        val suggested = CodeBlockSaveSupport.suggestedFileName(filename, "image.$format")
        val extension = CodeBlockSaveSupport.extensionOf(suggested)
        val sameFormat = extension == format || (format == "jpeg" && extension == "jpg")
        val outputName =
            if (sameFormat) suggested else suggested.substringBeforeLast('.') + ".$format"
        return ImageData(bytes, outputName)
    }

    private class ImageTransferable(private val image: BufferedImage) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}
