package com.lhstack.tools.agent.model.llm

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * LLM 消息与内容类型。完全照抄 awake-claw 的 src/llm.rs。
 * Rust 的 serde_json::Value 对应 Gson 的 JsonElement。
 *
 * 这些类型只承载运行时消息结构，不做任何业务判断，供 OpenAI / Anthropic 客户端使用。
 */

// ---------------- Message ----------------

sealed class Message {
    /** role = system */
    data class System(val content: String) : Message()

    /** role = user */
    data class User(val content: List<UserContent>) : Message()

    /** role = assistant */
    data class Assistant(
        val id: String?,
        val content: List<AssistantContent>,
    ) : Message()

    companion object {
        fun user(text: String): Message = User(listOf(UserContent.Text(text)))

        fun assistant(text: String): Message =
            Assistant(id = null, content = listOf(AssistantContent.Text(text)))
    }
}

// ---------------- AssistantContent ----------------

sealed class AssistantContent {
    data class Text(val text: String) : AssistantContent()
    data class Reasoning(val reasoning: com.lhstack.tools.agent.model.llm.Reasoning) : AssistantContent()
    data class ToolCall(val toolCall: com.lhstack.tools.agent.model.llm.ToolCall) : AssistantContent()

    companion object {
        fun text(text: String): AssistantContent = Text(text)
    }
}

// ---------------- UserContent ----------------

sealed class UserContent {
    data class Text(val text: String) : UserContent()
    data class Image(val image: com.lhstack.tools.agent.model.llm.Image) : UserContent()
    data class Audio(val audio: com.lhstack.tools.agent.model.llm.Audio) : UserContent()
    data class Video(val video: com.lhstack.tools.agent.model.llm.Video) : UserContent()
    data class Document(val document: com.lhstack.tools.agent.model.llm.Document) : UserContent()
    data class ToolResult(val toolResult: com.lhstack.tools.agent.model.llm.ToolResult) : UserContent()

    companion object {
        fun text(text: String): UserContent = Text(text)

        fun imageBase64(
            data: String,
            mediaType: ImageMediaType?,
            detail: ImageDetail?,
        ): UserContent = Image(
            com.lhstack.tools.agent.model.llm.Image(
                data = DocumentSourceKind.Base64(data),
                mediaType = mediaType,
                detail = detail,
            )
        )

        fun imageUrl(
            url: String,
            mediaType: ImageMediaType?,
            detail: ImageDetail?,
        ): UserContent = Image(
            com.lhstack.tools.agent.model.llm.Image(
                data = DocumentSourceKind.Url(url),
                mediaType = mediaType,
                detail = detail,
            )
        )

        fun audio(data: String, mediaType: AudioMediaType?): UserContent = Audio(
            com.lhstack.tools.agent.model.llm.Audio(
                data = DocumentSourceKind.Base64(data),
                mediaType = mediaType,
            )
        )
    }
}

// ---------------- Tool call / result ----------------

data class ToolCall(
    val id: String,
    val callId: String?,
    val function: ToolFunction,
    val signature: String?,
    val additionalParams: JsonElement?,
)

data class ToolFunction(
    val name: String,
    val arguments: JsonElement,
)

data class ToolResult(
    val id: String,
    val callId: String?,
    val content: List<ToolResultContent>,
)

sealed class ToolResultContent {
    data class Text(val text: String) : ToolResultContent()
    data class Image(val image: com.lhstack.tools.agent.model.llm.Image) : ToolResultContent()

    companion object {
        fun text(text: String): ToolResultContent = Text(text)
    }
}

// ---------------- Reasoning ----------------

data class Reasoning(
    val id: String?,
    val content: List<ReasoningContent>,
) {
    fun displayText(): String = content.mapNotNull { item ->
        when (item) {
            is ReasoningContent.Text -> item.text
            is ReasoningContent.Summary -> item.text
            is ReasoningContent.Redacted -> item.data
            is ReasoningContent.Encrypted -> null
        }
    }.joinToString("")
}

sealed class ReasoningContent {
    data class Text(val text: String) : ReasoningContent()
    data class Summary(val text: String) : ReasoningContent()
    data class Redacted(val data: String) : ReasoningContent()
    data class Encrypted(val data: String) : ReasoningContent()
}

// ---------------- Media ----------------

sealed class DocumentSourceKind {
    abstract val data: String

    data class Base64(override val data: String) : DocumentSourceKind()
    data class Str(override val data: String) : DocumentSourceKind()
    data class FileId(override val data: String) : DocumentSourceKind()
    data class Url(override val data: String) : DocumentSourceKind()
}

data class Image(
    val data: DocumentSourceKind,
    val mediaType: ImageMediaType?,
    val detail: ImageDetail?,
) {
    fun tryIntoUrl(): String = when (val source = data) {
        is DocumentSourceKind.Url -> source.data
        is DocumentSourceKind.Base64 -> {
            val mt = mediaType ?: throw IllegalStateException("base64 image requires media_type")
            "data:${mt.toMimeType()};base64,${source.data}"
        }
        else -> throw IllegalStateException("unsupported image source")
    }
}

data class Audio(
    val data: DocumentSourceKind,
    val mediaType: AudioMediaType?,
)

data class Video(
    val data: DocumentSourceKind,
    val mediaType: VideoMediaType?,
    val additionalParams: JsonElement?,
)

data class Document(
    val data: DocumentSourceKind,
    val mediaType: DocumentMediaType?,
    val additionalParams: JsonElement?,
)

interface MimeType {
    fun toMimeType(): String
}

enum class ImageMediaType : MimeType {
    JPEG, PNG, GIF, WEBP;

    override fun toMimeType(): String = when (this) {
        JPEG -> "image/jpeg"
        PNG -> "image/png"
        GIF -> "image/gif"
        WEBP -> "image/webp"
    }

    companion object {
        fun fromMimeType(value: String): ImageMediaType? = when (value) {
            "image/jpeg", "image/jpg" -> JPEG
            "image/png" -> PNG
            "image/gif" -> GIF
            "image/webp" -> WEBP
            else -> null
        }
    }
}

enum class AudioMediaType : MimeType {
    WAV, MP3, AIFF, AAC, OGG, FLAC, M4A, AVI, MP4, MPEG, MOV, WEBM, PCM16, PCM24;

    override fun toMimeType(): String = when (this) {
        WAV -> "audio/wav"
        MP3 -> "audio/mpeg"
        AIFF -> "audio/aiff"
        AAC -> "audio/aac"
        OGG -> "audio/ogg"
        FLAC -> "audio/flac"
        M4A -> "audio/mp4"
        AVI -> "video/avi"
        MP4 -> "video/mp4"
        MPEG -> "video/mpeg"
        MOV -> "video/quicktime"
        WEBM -> "video/webm"
        PCM16 -> "audio/pcm16"
        PCM24 -> "audio/pcm24"
    }

    companion object {
        fun fromMimeType(value: String): AudioMediaType? = when (value) {
            "audio/wav", "audio/x-wav" -> WAV
            "audio/mpeg", "audio/mp3" -> MP3
            "audio/aiff" -> AIFF
            "audio/aac" -> AAC
            "audio/ogg" -> OGG
            "audio/flac" -> FLAC
            "audio/mp4", "audio/m4a" -> M4A
            "video/avi" -> AVI
            "video/mp4" -> MP4
            "video/mpeg" -> MPEG
            "video/quicktime" -> MOV
            "video/webm" -> WEBM
            "audio/pcm16" -> PCM16
            "audio/pcm24" -> PCM24
            else -> null
        }
    }
}

enum class VideoMediaType : MimeType {
    AVI, MP4, MPEG, MOV, WEBM;

    override fun toMimeType(): String = when (this) {
        AVI -> "video/avi"
        MP4 -> "video/mp4"
        MPEG -> "video/mpeg"
        MOV -> "video/quicktime"
        WEBM -> "video/webm"
    }

    companion object {
        fun fromMimeType(value: String): VideoMediaType? = when (value) {
            "video/avi" -> AVI
            "video/mp4" -> MP4
            "video/mpeg" -> MPEG
            "video/quicktime" -> MOV
            "video/webm" -> WEBM
            else -> null
        }
    }
}

enum class DocumentMediaType : MimeType {
    PDF, TXT, JSON, XML, OCTET_STREAM;

    override fun toMimeType(): String = when (this) {
        PDF -> "application/pdf"
        TXT -> "text/plain"
        JSON -> "application/json"
        XML -> "application/xml"
        OCTET_STREAM -> "application/octet-stream"
    }

    companion object {
        fun fromMimeType(value: String): DocumentMediaType? = when {
            value == "application/pdf" -> PDF
            value == "text/plain" || value == "text/markdown" || value == "text/csv" || value == "text/html" -> TXT
            value == "application/json" -> JSON
            value == "application/xml" || value == "text/xml" -> XML
            value == "application/octet-stream" -> OCTET_STREAM
            value.startsWith("text/") -> TXT
            else -> null
        }
    }
}

enum class ImageDetail {
    Auto, Low, High
}

// ---------------- Tool definition + dyn ----------------

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

/**
 * 动态工具接口。对应 awake 的 ToolDyn：definition + call_json。
 * 具体工具（内置 / 插件）实现该接口后交给客户端调用。
 */
interface ToolDyn {
    fun definition(prompt: String): ToolDefinition
    fun callJsonBlocking(args: JsonElement): JsonElement
}

// ---------------- Usage ----------------

/**
 * Provider usage JSON is the single source of truth. Different providers expose
 * different keys and nested detail objects, so usage is not normalized into a
 * fixed set of translated fields.
 */
class Usage private constructor(
    private val fields: JsonObject,
) {
    constructor() : this(JsonObject())

    fun add(other: Usage) {
        accumulateUsageObject(fields, other.fields)
    }

    /** Anthropic stream usage is a cumulative snapshot; latest values replace earlier values. */
    fun mergeSnapshot(other: Usage) {
        mergeUsageSnapshot(fields, other.fields)
    }

    fun getLong(key: String): Long? = fields.get(key)?.unsignedLongOrNull()

    fun setLong(key: String, value: Long) {
        fields.addProperty(key, value)
    }

    fun toJson(): JsonObject = fields.deepCopy()

    companion object {
        fun fromJson(value: JsonElement): Usage = Usage(
            value.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() ?: JsonObject(),
        )

        private fun accumulateUsageObject(target: JsonObject, source: JsonObject) {
            for ((key, incoming) in source.entrySet()) {
                val existing = target.get(key)
                if (existing == null) {
                    target.add(key, incoming.deepCopy())
                } else {
                    accumulateUsageValue(target, key, existing, incoming)
                }
            }
        }

        private fun accumulateUsageValue(
            parent: JsonObject,
            key: String,
            existing: JsonElement,
            incoming: JsonElement,
        ) {
            when {
                existing.isJsonPrimitive && incoming.isJsonPrimitive -> {
                    val left = existing.unsignedLongOrNull()
                    val right = incoming.unsignedLongOrNull()
                    if (left != null && right != null) {
                        parent.addProperty(key, saturatingAdd(left, right))
                    } else {
                        parent.add(key, incoming.deepCopy())
                    }
                }
                existing.isJsonObject && incoming.isJsonObject ->
                    accumulateUsageObject(existing.asJsonObject, incoming.asJsonObject)
                existing.isJsonArray && incoming.isJsonArray ->
                    incoming.asJsonArray.forEach { existing.asJsonArray.add(it.deepCopy()) }
                else -> parent.add(key, incoming.deepCopy())
            }
        }

        private fun mergeUsageSnapshot(target: JsonObject, source: JsonObject) {
            for ((key, incoming) in source.entrySet()) {
                if (incoming.isJsonNull) continue
                val existing = target.get(key)
                if (existing?.isJsonObject == true && incoming.isJsonObject) {
                    mergeUsageSnapshot(existing.asJsonObject, incoming.asJsonObject)
                } else {
                    target.add(key, incoming.deepCopy())
                }
            }
        }

        private fun JsonElement.unsignedLongOrNull(): Long? {
            if (!isJsonPrimitive || !asJsonPrimitive.isNumber) return null
            return runCatching {
                asBigDecimal.toBigIntegerExact().takeIf { it.signum() >= 0 && it.bitLength() <= 63 }?.toLong()
            }.getOrNull()
        }

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}

// ---------------- Provider round ----------------

data class ProviderToolCall(
    val id: String,
    val callId: String?,
    val name: String,
    val arguments: JsonElement,
) {
    fun argsString(): String = when (arguments) {
        is JsonPrimitive -> if (arguments.isString) arguments.asString else arguments.toString()
        JsonNull.INSTANCE -> ""
        else -> arguments.toString()
    }
}

class ProviderRound(
    var response: String = "",
    val reasoning: MutableList<String> = mutableListOf(),
    val toolCalls: MutableList<ProviderToolCall> = mutableListOf(),
    val providerMessages: MutableList<Message> = mutableListOf(),
    val usage: Usage = Usage(),
)
