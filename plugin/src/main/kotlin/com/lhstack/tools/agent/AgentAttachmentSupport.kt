package com.lhstack.tools.agent

import io.agentscope.core.message.AudioBlock
import io.agentscope.core.message.Base64Source
import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.ImageBlock
import io.agentscope.core.message.URLSource
import io.agentscope.core.message.VideoBlock
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.stream.FileImageOutputStream

object AgentAttachmentSupport {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val audioExtensions = setOf("mp3", "wav", "m4a", "ogg", "flac")
    private val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "webm")
    private val textExtensions = setOf(
        "txt", "md", "markdown", "json", "xml", "yaml", "yml", "properties", "java", "kt", "kts",
        "groovy", "py", "js", "ts", "jsx", "tsx", "html", "css", "scss", "sql", "log", "csv",
    )
    private const val MAX_IMAGE_DIMENSION = 1568
    private const val MAX_IMAGE_BYTES = 3L * 1024 * 1024

    fun normalize(draft: AgentAttachmentState): AgentAttachmentState {
        val resolvedMime = resolveMimeType(draft)
        val resolvedKind = when {
            resolvedMime.startsWith("image/") || extensionOf(draft) in imageExtensions -> AgentAttachmentKind.IMAGE
            resolvedMime.startsWith("audio/") || extensionOf(draft) in audioExtensions -> AgentAttachmentKind.AUDIO
            resolvedMime.startsWith("video/") || extensionOf(draft) in videoExtensions -> AgentAttachmentKind.VIDEO
            else -> AgentAttachmentKind.FILE
        }
        return draft.apply {
            mimeType = resolvedMime
            kind = resolvedKind.id
            deliveryMode = AgentAttachmentDeliveryMode.fromId(deliveryMode).id
            if (size <= 0 && path.isNotBlank()) {
                runCatching { Files.size(Paths.get(path)) }.getOrNull()?.let { size = it }
            }
        }
    }

    fun prepareForUpload(draft: AgentAttachmentState): AgentAttachmentState {
        val normalized = normalize(draft)
        return when (AgentAttachmentKind.fromId(normalized.kind)) {
            AgentAttachmentKind.IMAGE -> optimizeImage(normalized)
            else -> normalized
        }
    }

    fun toMediaBlock(draft: AgentAttachmentState): ContentBlock? {
        val normalized = prepareForUpload(draft)
        if (normalized.path.isBlank()) {
            return null
        }
        val source = buildSource(normalized)
        return when (AgentAttachmentKind.fromId(normalized.kind)) {
            AgentAttachmentKind.IMAGE -> ImageBlock.builder().source(source).build()
            AgentAttachmentKind.AUDIO -> AudioBlock.builder().source(source).build()
            AgentAttachmentKind.VIDEO -> VideoBlock.builder().source(source).build()
            AgentAttachmentKind.FILE -> null
        }
    }

    fun toFileContext(draft: AgentAttachmentState): AgentFileContextEntry? {
        val normalized = normalize(draft)
        val extracted = extractText(normalized)
        val deliveryMode = AgentAttachmentDeliveryMode.fromId(normalized.deliveryMode)
        if (extracted == null && deliveryMode == AgentAttachmentDeliveryMode.CONTENT_ONLY) {
            return null
        }
        return AgentFileContextEntry(
            name = normalized.name.ifBlank { Paths.get(normalized.path).fileName?.toString().orEmpty() },
            mimeType = normalized.mimeType,
            path = normalized.path,
            resourceRef = buildResourceRef(normalized),
            content = when (deliveryMode) {
                AgentAttachmentDeliveryMode.METADATA_ONLY -> null
                else -> extracted
            },
            metadataOnly = deliveryMode == AgentAttachmentDeliveryMode.METADATA_ONLY || extracted.isNullOrBlank(),
        )
    }

    fun extractText(draft: AgentAttachmentState): String? {
        if (draft.path.isBlank()) {
            return null
        }
        val path = Paths.get(draft.path)
        if (!Files.exists(path) || Files.isDirectory(path)) {
            return null
        }
        val extension = extensionOf(draft)
        if (extension !in textExtensions && !draft.mimeType.startsWith("text/")) {
            return null
        }
        return Files.readString(path, StandardCharsets.UTF_8)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun buildResourceRef(draft: AgentAttachmentState): String {
        return if (draft.path.isNotBlank()) {
            Paths.get(draft.path).toUri().toString()
        } else {
            "attachment://${draft.id}/${draft.name.ifBlank { "unnamed" }}"
        }
    }

    private fun resolveMimeType(draft: AgentAttachmentState): String {
        val explicit = draft.mimeType.trim()
        if (explicit.isNotBlank()) {
            return explicit
        }
        if (draft.path.isNotBlank()) {
            runCatching { Files.probeContentType(Paths.get(draft.path)) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return when (extensionOf(draft)) {
            in imageExtensions -> "image/${extensionOf(draft).replace("jpg", "jpeg")}"
            in audioExtensions -> "audio/${extensionOf(draft)}"
            in videoExtensions -> "video/${extensionOf(draft)}"
            "md", "markdown" -> "text/markdown"
            "txt", "log" -> "text/plain"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun extensionOf(draft: AgentAttachmentState): String {
        val source = draft.name.ifBlank {
            if (draft.path.isBlank()) "" else Paths.get(draft.path).fileName?.toString().orEmpty()
        }
        return source.substringAfterLast('.', "").lowercase()
    }

    private fun buildSource(draft: AgentAttachmentState): io.agentscope.core.message.Source {
        return if (draft.path.isNotBlank()) {
            val path = Paths.get(draft.path)
            if (Files.exists(path) && Files.isRegularFile(path)) {
                val encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(path))
                Base64Source.builder()
                    .data(encoded)
                    .mediaType(draft.mimeType)
                    .build()
            } else {
                URLSource.builder().url(path.toUri().toString()).build()
            }
        } else {
            URLSource.builder().url("").build()
        }
    }

    private fun optimizeImage(draft: AgentAttachmentState): AgentAttachmentState {
        if (draft.path.isBlank()) {
            return draft
        }
        val path = Paths.get(draft.path)
        if (!Files.exists(path) || Files.isDirectory(path)) {
            return draft
        }
        val size = if (draft.size > 0) draft.size else runCatching { Files.size(path) }.getOrDefault(0)
        val sourceImage = runCatching { ImageIO.read(path.toFile()) }.getOrNull() ?: return draft
        if (sourceImage.width <= MAX_IMAGE_DIMENSION &&
            sourceImage.height <= MAX_IMAGE_DIMENSION &&
            size in 1..MAX_IMAGE_BYTES
        ) {
            return draft
        }
        val scaled = scaleImage(sourceImage, MAX_IMAGE_DIMENSION)
        val optimizedFile = writeOptimizedImage(scaled, draft) ?: return draft
        return draft.copy(
            path = optimizedFile.absolutePath,
            mimeType = if (optimizedFile.extension.equals("jpg", true)) "image/jpeg" else "image/png",
            size = optimizedFile.length(),
        )
    }

    private fun scaleImage(source: BufferedImage, maxDimension: Int): BufferedImage {
        val ratio = minOf(
            1.0,
            maxDimension.toDouble() / source.width.toDouble(),
            maxDimension.toDouble() / source.height.toDouble(),
        )
        if (ratio >= 1.0) {
            return source
        }
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(width, height, if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return scaled
    }

    private fun writeOptimizedImage(image: BufferedImage, draft: AgentAttachmentState): File? {
        val tempDir = Files.createTempDirectory("agent-attachment-img").toFile().apply { deleteOnExit() }
        val pngFile = File(tempDir, "${File(draft.name.ifBlank { "attachment" }).nameWithoutExtension}-optimized.png")
        if (image.colorModel.hasAlpha()) {
            if (ImageIO.write(image, "png", pngFile) && pngFile.length() <= MAX_IMAGE_BYTES) {
                pngFile.deleteOnExit()
                return pngFile
            }
        }
        val jpgFile = File(tempDir, "${File(draft.name.ifBlank { "attachment" }).nameWithoutExtension}-optimized.jpg")
        listOf(0.88f, 0.8f, 0.72f, 0.64f).forEach { quality ->
            if (writeJpeg(image, jpgFile, quality) && jpgFile.length() in 1..MAX_IMAGE_BYTES) {
                jpgFile.deleteOnExit()
                return jpgFile
            }
        }
        if (jpgFile.exists()) {
            jpgFile.deleteOnExit()
            return jpgFile
        }
        return null
    }

    private fun writeJpeg(image: BufferedImage, target: File, quality: Float): Boolean {
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) image else BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { copy ->
            val graphics = copy.createGraphics()
            try {
                graphics.color = java.awt.Color.WHITE
                graphics.fillRect(0, 0, copy.width, copy.height)
                graphics.drawImage(image, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").asSequence().firstOrNull() ?: return false
        return runCatching {
            FileImageOutputStream(target).use { output ->
                writer.output = output
                val params = JPEGImageWriteParam(null).apply {
                    compressionMode = JPEGImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(rgb, null, null), params)
            }
            true
        }.getOrDefault(false).also {
            writer.dispose()
        }
    }
}
