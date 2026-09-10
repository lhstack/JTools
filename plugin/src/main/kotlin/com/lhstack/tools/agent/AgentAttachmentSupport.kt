package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.lhstack.tools.llm.AudioMediaType
import com.lhstack.tools.llm.ImageMediaType
import com.lhstack.tools.llm.UserContent
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.stream.FileImageOutputStream

/**
 * 附件到 LLM 用户内容（UserContent）的映射。
 *
 * 图片：压缩到模型可接收尺寸后转 base64 图片块；音频：转 base64 音频块；
 * 文本文件：读取内容作为文本块；其余文件：仅注入元数据文本。
 * 所有映射产出 UserContent，直接拼进 Message.User，不经过任何旧的消息结构。
 */
object AgentAttachmentSupport {

    /** 附件回显快照：字段与会话历史读取一致（id/name/path/mimeType/size/kind）。 */
    fun snapshotOf(draft: AgentAttachmentState): JsonObject = normalize(draft).let { normalized ->
        JsonObject().apply {
            addProperty("id", normalized.id)
            addProperty("name", normalized.name)
            addProperty("path", normalized.path)
            addProperty("mimeType", normalized.mimeType)
            addProperty("size", normalized.size)
            addProperty("kind", normalized.kind)
        }
    }

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val audioExtensions = setOf("mp3", "wav", "m4a", "ogg", "flac")
    private val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "webm")
    private val textExtensions = setOf(
        "txt", "md", "markdown", "json", "xml", "yaml", "yml", "properties", "java", "kt", "kts",
        "groovy", "py", "js", "ts", "jsx", "tsx", "html", "css", "scss", "sql", "log", "csv",
    )
    private const val MAX_IMAGE_DIMENSION = 1568
    private const val MAX_IMAGE_BYTES = 3L * 1024 * 1024

    /** 规范化附件：识别 MIME、类型与文件大小。 */
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
            if (size <= 0 && path.isNotBlank()) {
                runCatching { Files.size(Paths.get(path)) }.getOrNull()?.let { size = it }
            }
        }
    }

    /**
     * 把附件映射为一个 LLM 用户内容块。
     *
     * modalities 是当前模型声明的多模态能力（image/audio/video/text）。
     * 模型具备对应模态时内联内容，并且始终附上元数据文本（文件名/类型/路径）。
     * 不具备对应模态或无法读取内容时只发元数据。
     */
    fun toUserContents(draft: AgentAttachmentState, modalities: Set<String>): List<UserContent> {
        val normalized = normalize(draft)
        val metadata = metadataContent(normalized)
        val inline = when (AgentAttachmentKind.fromId(normalized.kind)) {
            AgentAttachmentKind.IMAGE -> if ("image" in modalities) imageContent(normalized) else null
            AgentAttachmentKind.AUDIO -> if ("audio" in modalities) audioContent(normalized) else null
            AgentAttachmentKind.VIDEO -> null
            AgentAttachmentKind.FILE -> textContent(normalized)
        }
        return listOfNotNull(inline, metadata)
    }


    private fun imageContent(draft: AgentAttachmentState): UserContent? {
        val optimized = optimizeImage(draft)
        val path = Paths.get(optimized.path)
        if (!Files.exists(path) || !Files.isRegularFile(path)) return null
        val base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(path))
        val mediaType = ImageMediaType.fromMimeType(optimized.mimeType) ?: ImageMediaType.PNG
        return UserContent.imageBase64(base64, mediaType, null)
    }

    private fun audioContent(draft: AgentAttachmentState): UserContent? {
        val path = Paths.get(draft.path)
        if (!Files.exists(path) || !Files.isRegularFile(path)) return null
        val mediaType = AudioMediaType.fromMimeType(draft.mimeType) ?: return null
        val base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(path))
        return UserContent.audio(base64, mediaType)
    }

    private fun textContent(draft: AgentAttachmentState): UserContent? {
        val text = extractText(draft) ?: return null
        val name = draft.name.ifBlank { Paths.get(draft.path).fileName?.toString().orEmpty() }
        return UserContent.text("[附件文件 $name]\n$text")
    }

    fun metadataPrompt(
        fileName: String,
        contentType: String,
        size: Long,
        kind: String,
        path: String,
        uploadedAt: String,
    ): String = buildString {
        append("[Attachment]\n")
        append("file_name: ").append(fileName).append('\n')
        append("content_type: ").append(contentType).append('\n')
        append("size: ").append(size).append(" bytes\n")
        append("kind: ").append(kind).append('\n')
        append("path: ").append(path).append('\n')
        append("uploaded_at: ").append(uploadedAt).append('\n')
    }

    private fun metadataContent(draft: AgentAttachmentState): UserContent {
        val name = draft.name.ifBlank { Paths.get(draft.path).fileName?.toString().orEmpty() }
        return UserContent.text(
            metadataPrompt(
                fileName = name,
                contentType = draft.mimeType.ifBlank { "application/octet-stream" },
                size = draft.size,
                kind = draft.kind.ifBlank { AgentAttachmentKind.FILE.id },
                path = draft.path,
                uploadedAt = "",
            )
        )
    }

    private fun extractText(draft: AgentAttachmentState): String? {
        if (draft.path.isBlank()) return null
        val path = Paths.get(draft.path)
        if (!Files.exists(path) || Files.isDirectory(path)) return null
        val extension = extensionOf(draft)
        if (extension !in textExtensions && !draft.mimeType.startsWith("text/")) return null
        return Files.readString(path, StandardCharsets.UTF_8).trim().takeIf { it.isNotBlank() }
    }

    private fun resolveMimeType(draft: AgentAttachmentState): String {
        val explicit = draft.mimeType.trim()
        if (explicit.isNotBlank()) return explicit
        if (draft.path.isNotBlank()) {
            runCatching { Files.probeContentType(Paths.get(draft.path)) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { return it }
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

    private fun optimizeImage(draft: AgentAttachmentState): AgentAttachmentState {
        if (draft.path.isBlank()) return draft
        val path = Paths.get(draft.path)
        if (!Files.exists(path) || Files.isDirectory(path)) return draft
        val size = if (draft.size > 0) draft.size else runCatching { Files.size(path) }.getOrDefault(0)
        val sourceImage = runCatching { ImageIO.read(path.toFile()) }.getOrNull() ?: return draft
        if (sourceImage.width <= MAX_IMAGE_DIMENSION && sourceImage.height <= MAX_IMAGE_DIMENSION && size in 1..MAX_IMAGE_BYTES) {
            return draft
        }
        val scaled = scaleImage(sourceImage, MAX_IMAGE_DIMENSION)
        val optimizedFile = kotlin.io.path.createTempFile("jtools-agent-image-", ".jpg").toFile()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam as JPEGImageWriteParam
        params.compressionMode = JPEGImageWriteParam.MODE_EXPLICIT
        params.compressionQuality = 0.86f
        FileImageOutputStream(optimizedFile).use { output ->
            writer.output = output
            writer.write(null, IIOImage(scaled, null, null), params)
        }
        writer.dispose()
        return draft.copy(path = optimizedFile.absolutePath, mimeType = "image/jpeg", size = optimizedFile.length())
    }

    private fun scaleImage(source: BufferedImage, maxDimension: Int): BufferedImage {
        val ratio = minOf(maxDimension.toDouble() / source.width, maxDimension.toDouble() / source.height, 1.0)
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        return output
    }
}
