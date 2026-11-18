package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.lhstack.tools.agent.model.tools.ResourcePathSupport
import com.lhstack.tools.llm.AudioMediaType
import com.lhstack.tools.llm.UserContent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64

/** 附件元数据与显式原生读取编码。首次输入不读取资源字节。 */
object AgentAttachmentSupport {

    /** 附件回显快照：字段与会话历史读取一致（id/name/path/mimeType/size/kind）。 */
    fun snapshotOf(draft: AgentAttachmentState): JsonObject =
        normalize(draft).let { normalized ->
            JsonObject().apply {
                addProperty("id", normalized.id)
                addProperty("name", normalized.name)
                addProperty("path", normalized.path)
                addProperty("mimeType", normalized.mimeType)
                addProperty("size", normalized.size)
                addProperty("kind", normalized.kind)
            }
        }

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
    private val audioExtensions = setOf("mp3", "wav", "m4a", "ogg", "flac")
    private val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "webm")

    /** 规范化附件：识别 MIME、类型与文件大小。 */
    fun normalize(draft: AgentAttachmentState): AgentAttachmentState {
        val resolvedMime = resolveMimeType(draft)
        val resolvedKind =
            when {
                resolvedMime.startsWith("image/") || extensionOf(draft) in imageExtensions ->
                    AgentAttachmentKind.IMAGE
                resolvedMime.startsWith("audio/") || extensionOf(draft) in audioExtensions ->
                    AgentAttachmentKind.AUDIO
                resolvedMime.startsWith("video/") || extensionOf(draft) in videoExtensions ->
                    AgentAttachmentKind.VIDEO
                else -> AgentAttachmentKind.FILE
            }
        return draft.apply {
            mimeType = resolvedMime
            kind = resolvedKind.id
            if (size <= 0) resourceSize(path)?.let { size = it }
        }
    }

    /**
     * User input always carries metadata; nativeContent is reserved for explicit tool execution.
     */
    fun toUserContents(draft: AgentAttachmentState, modalities: Set<String>): List<UserContent> {
        val normalized = normalize(draft)
        val metadata = metadataContent(normalized)
        return listOf(metadata)
    }

    /** Explicit native tool reads only; callers must validate model/API capabilities first. */
    fun nativeContent(draft: AgentAttachmentState): UserContent {
        val normalized = normalize(draft)
        return when (AgentAttachmentKind.fromId(normalized.kind)) {
            AgentAttachmentKind.IMAGE ->
                imageContent(normalized) ?: error("无法读取图片: ${normalized.path}")
            AgentAttachmentKind.AUDIO ->
                audioContent(normalized) ?: error("不支持的音频类型或无法读取: ${normalized.path}")
            AgentAttachmentKind.VIDEO -> {
                val media =
                    com.lhstack.tools.llm.VideoMediaType.fromMimeType(normalized.mimeType)
                        ?: error("不支持的视频 MIME: ${normalized.mimeType}")
                val bytes = resourceBytes(normalized.path) ?: error("无法读取视频")
                UserContent.Video(
                    com.lhstack.tools.llm.Video(
                        com.lhstack.tools.llm.DocumentSourceKind.Base64(
                            Base64.getEncoder().encodeToString(bytes)
                        ),
                        media,
                        null,
                    )
                )
            }
            AgentAttachmentKind.FILE -> {
                val media =
                    com.lhstack.tools.llm.DocumentMediaType.fromMimeType(normalized.mimeType)
                        ?.takeUnless { it == com.lhstack.tools.llm.DocumentMediaType.OCTET_STREAM }
                        ?: error("不支持的文档 MIME: ${normalized.mimeType}")
                val bytes = resourceBytes(normalized.path) ?: error("无法读取文档")
                val source =
                    if (media == com.lhstack.tools.llm.DocumentMediaType.PDF)
                        com.lhstack.tools.llm.DocumentSourceKind.Base64(
                            Base64.getEncoder().encodeToString(bytes)
                        )
                    else {
                        require(!bytes.contains(0)) { "文档包含二进制内容，不能按文本发送" }
                        com.lhstack.tools.llm.DocumentSourceKind.Str(
                            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                                .decode(java.nio.ByteBuffer.wrap(bytes))
                                .toString()
                        )
                    }
                UserContent.Document(
                    com.lhstack.tools.llm.Document(
                        source,
                        media,
                        com.google.gson.JsonObject().apply {
                            addProperty("filename", normalized.name)
                            addProperty("title", normalized.name)
                        },
                    )
                )
            }
        }
    }

    private fun imageContent(draft: AgentAttachmentState): UserContent? {
        val bytes = resourceBytes(draft.path) ?: return null
        val svg = draft.mimeType.equals("image/svg+xml", ignoreCase = true) || extensionOf(draft) == "svg"
        val encoded = try {
            ModelImageEncodingSupport.encode(bytes, svg)
        } catch (error: Exception) {
            throw IllegalArgumentException("无法解码或转换图片：${draft.path}", error)
        }
        return UserContent.imageBase64(Base64.getEncoder().encodeToString(encoded.bytes), encoded.mediaType, null)
    }

    private fun audioContent(draft: AgentAttachmentState): UserContent? {
        val bytes = resourceBytes(draft.path) ?: return null
        val mediaType = AudioMediaType.fromMimeType(draft.mimeType) ?: return null
        require(mediaType in setOf(AudioMediaType.MP3,AudioMediaType.WAV)) {"当前音频输入协议仅支持 MP3/WAV"}
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return UserContent.audio(base64, mediaType)
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

    fun metadataContent(draft: AgentAttachmentState): UserContent {
        val name = draft.name.ifBlank { resourceName(draft.path).orEmpty() }
        return UserContent.text(
            metadataPrompt(
                fileName = name,
                contentType = draft.mimeType.ifBlank { "application/octet-stream" },
                size = draft.size,
                kind = draft.kind.ifBlank { AgentAttachmentKind.FILE.id },
                path = draft.path,
                uploadedAt = "",
            ) + "读取内容请调用对应 view_* 工具并使用上面的 path（不是 file_name）。相对路径以当前项目工作目录为基准，每次最多 3 个资源。\n"
        )
    }

    private fun resolveMimeType(draft: AgentAttachmentState): String {
        val explicit = draft.mimeType.trim()
        if (explicit.isNotBlank()) return explicit
        resourceMimeType(draft.path)?.let {
            return it
        }
        return when (extensionOf(draft)) {
            "svg" -> "image/svg+xml"
            in imageExtensions -> "image/${extensionOf(draft).replace("jpg", "jpeg")}"
            in audioExtensions -> "audio/${extensionOf(draft)}"
            in videoExtensions -> "video/${extensionOf(draft)}"
            "md",
            "markdown" -> "text/markdown"
            "txt",
            "log" -> "text/plain"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun extensionOf(draft: AgentAttachmentState): String {
        val source = draft.name.ifBlank { resourceName(draft.path).orEmpty() }
        return source.substringAfterLast('.', "").lowercase()
    }

    private fun resourceBytes(path: String): ByteArray? {
        if (path.isBlank()) return null
        if (ResourcePathSupport.isProtocolPath(path)) return ResourcePathSupport.readBytes(path)
        val file = Paths.get(path)
        return if (Files.exists(file) && Files.isRegularFile(file)) Files.readAllBytes(file)
        else null
    }

    private fun resourceName(path: String): String? {
        if (path.isBlank()) return null
        if (ResourcePathSupport.isProtocolPath(path)) return ResourcePathSupport.fileName(path)
        return runCatching { Paths.get(path).fileName?.toString() }.getOrNull()
    }

    private fun resourceSize(path: String): Long? {
        if (path.isBlank()) return null
        if (ResourcePathSupport.isProtocolPath(path)) return ResourcePathSupport.size(path)
        return runCatching { Files.size(Paths.get(path)) }.getOrNull()
    }

    private fun resourceMimeType(path: String): String? {
        if (path.isBlank()) return null
        if (ResourcePathSupport.isProtocolPath(path)) return ResourcePathSupport.mimeType(path)
        return runCatching { Files.probeContentType(Paths.get(path)) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
