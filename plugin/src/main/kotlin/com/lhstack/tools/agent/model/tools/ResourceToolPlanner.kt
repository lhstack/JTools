package com.lhstack.tools.agent.model.tools

import com.google.gson.*
import com.lhstack.tools.agent.*
import com.lhstack.tools.agent.model.params.*
import com.lhstack.tools.agent.resources.LocalResourceReferenceSupport
import com.lhstack.tools.db.service.MessageStoreService
import com.lhstack.tools.llm.*
import java.io.File
import java.security.MessageDigest

object ResourceToolPlanner {
    const val MAX_RESOURCES = 3

    fun usesNative(kind: ResourceKind, model: ResolvedModelConfig): Boolean =
        kind.asStr in model.params.modalities

    fun validate(model: ResolvedModelConfig) {
        val supported =
            if (model.providerKind == ProviderKind.ANTHROPIC || model.api == OpenAiApi.RESPONSES)
                setOf("text", "image", "file")
            else setOf("text", "image", "audio", "video", "file")
        require(model.params.modalities.all { it in supported }) {
            "当前模型 API 不支持声明的模态: ${model.params.modalities.filter {it !in supported}}"
        }
    }

    fun plan(
        model: ResolvedModelConfig,
        workspace: String,
        sessionId: Long?,
        delegated: List<ToolDyn>,
        authorized: List<AgentAttachmentState> = emptyList(),
    ): List<ToolDyn> {
        val native = nativeTools(model, workspace, sessionId, authorized)
        val nativeNames = native.map { it.definition("").name }.toSet()
        val tools = delegated.filterNot { it.definition("").name in nativeNames } + native
        require(tools.map { it.definition("").name }.distinct().size == tools.size) { "多模态工具名称冲突" }
        return tools
    }

    fun nativeTools(
        model: ResolvedModelConfig,
        workspace: String,
        sessionId: Long?,
        authorized: List<AgentAttachmentState> = emptyList(),
    ): List<ToolDyn> {
        validate(model)
        return ResourceKind.entries
            .filter { usesNative(it, model) }
            .map { NativeResourceTool(it, workspace, sessionId, authorized) }
    }
}

class NativeResourceTool(
    private val kind: ResourceKind,
    private val workspace: String,
    private val sessionId: Long?,
    private val authorized: List<AgentAttachmentState> = emptyList(),
) : ToolDyn {
    override fun definition(prompt: String) =
        ToolDefinition(
            kind.toolName,
            "读取${kind.asStr}并通过原生多模态协议加入当前模型本轮上下文，不调用其他 Agent。每次最多 3 个资源，每个资源最大 32 MiB；首次仅有元数据，需要查看时调用。跨轮历史不含资源原文，需要时重新读取。",
            JsonParser.parseString(
                """{"type":"object","additionalProperties":false,"properties":{"resources":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"object","additionalProperties":false,"properties":{"attachment_id":{"type":"string"},"path":{"type":"string"}},"oneOf":[{"required":["attachment_id"]},{"required":["path"]}]}}},"required":["resources"]}"""
            ),
        )

    override fun callJsonBlocking(args: JsonElement): JsonElement = error("原生资源工具必须经结构化工具执行入口调用")

    override fun executeBlocking(args: JsonElement): ToolExecutionResult {
        val obj = args.asJsonObject
        require(obj.keySet() == setOf("resources")) { "仅支持 resources 参数" }
        val resources = obj.getAsJsonArray("resources") ?: error("缺少 resources")
        require(resources.size() in 1..ResourceToolPlanner.MAX_RESOURCES) { "每次必须读取 1–3 个资源" }
        val attachments =
            authorized +
                (sessionId?.let(MessageStoreService::sessionAttachments).orEmpty().map {
                    AgentAttachmentState(
                        it.id.toString(),
                        it.fileName,
                        it.path,
                        it.contentType,
                        it.size,
                        it.kind,
                    )
                })
        val resolved =
            resources.map { item ->
                val ref = item.asJsonObject
                require(
                    ref.size() == 1 && ref.keySet().single() in setOf("attachment_id", "path")
                ) {
                    "每个资源只能指定 attachment_id 或 path"
                }
                require(
                    ref.entrySet().all {
                        it.value.isJsonPrimitive &&
                            it.value.asJsonPrimitive.isString &&
                            it.value.asString.isNotBlank()
                    }
                ) {
                    "资源引用必须为非空字符串"
                }
                val attachment =
                    ref.get("attachment_id")?.asString?.let { id ->
                        attachments.firstOrNull { it.id == id } ?: error("附件不属于当前会话: $id")
                    }
                val location =
                    LocalResourceReferenceSupport.resolve(
                        attachment?.path ?: ref.get("path").asString,
                        workspace,
                        attachments.map { it.path }.toSet(),
                    )
                val file = location.file
                require(file.isFile && file.length() <= 32L * 1024 * 1024) {
                    "资源必须为普通文件且不超过 32 MiB"
                }
                val matched =
                    attachment ?: attachments.firstOrNull { File(it.path).canonicalFile == file }
                val draft =
                    AgentAttachmentSupport.normalize(
                        AgentAttachmentState(
                            matched?.id.orEmpty(),
                            file.name,
                            file.path,
                            matched?.mimeType.orEmpty(),
                            file.length(),
                        )
                    )
                require(draft.kind == kind.asStr) { "资源类型为 ${draft.kind}，不能由 ${kind.toolName} 读取" }
                draft
            }
        val summaries = JsonArray()
        val candidates =
            resolved.map { draft ->
                fun digest() =
                    MessageDigest.getInstance("SHA-256")
                        .digest(File(draft.path).readBytes())
                        .joinToString("") { "%02x".format(it) }
                val digest = digest()
                val content = AgentAttachmentSupport.nativeContent(draft)
                require(digest == digest()) { "读取期间资源发生变化，请重试: ${draft.path}" }
                val summary =
                    JsonObject().apply {
                        addProperty("type", "resource_read_summary")
                        addProperty("kind", kind.asStr)
                        addProperty("path", draft.path)
                        addProperty("attachment_id", draft.id)
                        addProperty("mime_type", draft.mimeType)
                        addProperty("bytes", draft.size)
                        addProperty("sha256", digest)
                        addProperty("status", "loaded")
                        addProperty("note", "资源仅在原始轮次注入，历史不含原始内容；重看请重新调用 ${kind.toolName}")
                    }
                summaries.add(summary)
                ToolResource(
                    "${draft.path}:$digest",
                    listOf(
                        UserContent.Text("[Resource ${kind.asStr}] ${draft.path} sha256=$digest"),
                        content,
                    ),
                )
            }
        return ToolExecutionResult(JsonObject().apply { add("resources", summaries) }, candidates)
    }
}
