package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.AgentAttachmentState
import com.lhstack.tools.agent.AgentAttachmentSupport
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.provider.AgentRuntime
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn
import java.io.File

/** 多模态资源查看工具：把路径附件交给配置的资源 Agent 分析。 */
class ViewResourceTool(
    private val kind: ResourceKind,
    private val resourceAgentId: Long,
    workspace: String,
    private val skillsRootDir: File?,
    private val cancel: ModelCancel?,
    private val project: com.intellij.openapi.project.Project? = null,
    private val timeoutSecs: Long? = null,
) : ToolDyn {

    private val workspaceTools = WorkspaceTools(workspace)

    override val executionTimeoutSeconds: Long
        get() {
            timeoutSecs?.let {
                return resourceTimeoutSeconds(it) + 10L
            }
            val agent =
                AgentService.agentById(resourceAgentId) ?: error("资源 Agent `$resourceAgentId` 不存在")
            return resourceTimeoutSeconds(timeoutSecs, agent.maxRuntimeSecs) + 10L
        }

    override fun definition(prompt: String): ToolDefinition =
        ToolDefinition(
            name = kind.toolName,
            description = description() + " 每次最多 3 个资源，超出会报错。",
            parameters = JsonParser.parseString(DEFINITION_JSON),
        )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val obj = args.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val paths = requestedPaths(obj)
        require(paths.size in 1..ResourceToolPlanner.MAX_RESOURCES) { "每次必须读取 1–3 个资源" }
        val resources = paths.map(::resolveResourcePath)
        require(resources.isNotEmpty()) { "paths 不能为空" }
        val prompt =
            obj.get("prompt")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("prompt 不能为空，必须描述需要查看资源的哪些内容")

        val resourceAgent =
            AgentService.agentById(resourceAgentId)
                ?: throw IllegalStateException("资源 Agent `$resourceAgentId` 不存在")
        val modalities = resourceAgentModalities(resourceAgent.modelId)
        require(resourceAgentSupportsKind(modalities)) {
            "资源 Agent `${resourceAgent.name}` 绑定模型不支持 `${kind.asStr}` 多模态能力"
        }
        val attachments =
            resources.map { resource ->
                AgentAttachmentSupport.normalize(
                    AgentAttachmentState(
                        name = resource.name,
                        path = resource.path,
                        mimeType = resource.mimeType.orEmpty(),
                        size = resource.size,
                    )
                )
            }
        val childCancel = ModelCancel()
        val interruptId = cancel?.registerInterrupt(childCancel::cancel)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val scheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { action ->
                Thread(action, "jtools-resource-timeout").apply { isDaemon = true }
            }
        val timer =
            scheduler.schedule(
                {
                    timedOut.set(true)
                    childCancel.cancel()
                },
                resourceTimeoutSeconds(timeoutSecs, resourceAgent.maxRuntimeSecs),
                java.util.concurrent.TimeUnit.SECONDS,
            )
        val result =
            try {
                AgentRuntime.execute(
                    AgentRuntime.Request(
                        agentId = resourceAgentId,
                        prompt = prompt + "\n请调用自身的资源读取工具读取以下附件后完成分析，每次最多 3 个资源。",
                        triggerType = kind.toolName,
                        triggerId = resources.joinToString(",") { it.displayPath },
                        workspace = workspaceTools.canonicalRoot().absolutePath,
                        skillsRootDir = skillsRootDir,
                        resourceAttachments = attachments,
                        attachments =
                            attachments.flatMap {
                                AgentAttachmentSupport.toUserContents(it, modalities)
                            },
                        attachmentSnapshots =
                            attachments.map { AgentAttachmentSupport.snapshotOf(it) },
                        cancel = childCancel,
                        toolCancel = childCancel,
                        project = project,
                    )
                )
            } catch (error: Throwable) {
                if (timedOut.get())
                    throw IllegalStateException(
                        "资源 Agent 执行超时（${resourceTimeoutSeconds(timeoutSecs,resourceAgent.maxRuntimeSecs)} 秒）",
                        error,
                    )
                throw error
            } finally {
                timer.cancel(false)
                scheduler.shutdownNow()
                interruptId?.let { cancel?.clearInterrupt(it) }
            }
        check(!timedOut.get()) {
            "资源 Agent 执行超时（${resourceTimeoutSeconds(timeoutSecs,resourceAgent.maxRuntimeSecs)} 秒）"
        }
        return JsonObject().apply {
            addProperty("agent_id", resourceAgentId)
            addProperty("agent_name", resourceAgent.name)
            addProperty("response", result.output)
            addProperty("model_log_id", result.logId)
        }
    }

    private data class ResolvedResource(
        val path: String,
        val name: String,
        val size: Long,
        val mimeType: String?,
        val displayPath: String,
    )

    private fun resolveResourcePath(path: String): ResolvedResource {
        if (ResourcePathSupport.isProtocolPath(path)) {
            val virtualFile = ResourcePathSupport.requireFile(path)
            return ResolvedResource(
                path = path,
                name = virtualFile.name,
                size = virtualFile.length,
                mimeType = ResourcePathSupport.mimeType(path),
                displayPath = path,
            )
        }
        val file = File(path)
        val resolved = if (file.isAbsolute) file else workspaceTools.resolveExistingPath(path)
        require(resolved.exists() && resolved.isFile) { "资源文件不存在或不是普通文件: $path" }
        val canonical = resolved.canonicalFile
        return ResolvedResource(
            path = canonical.absolutePath,
            name = canonical.name,
            size = canonical.length(),
            mimeType =
                runCatching { java.nio.file.Files.probeContentType(canonical.toPath()) }
                    .getOrNull(),
            displayPath =
                if (file.isAbsolute) canonical.absolutePath
                else workspaceTools.displayPath(canonical),
        )
    }

    private fun requestedPaths(obj: JsonObject): List<String> {
        val paths = mutableListOf<String>()
        fun path(value: JsonElement): String {
            require(
                value.isJsonPrimitive &&
                    value.asJsonPrimitive.isString &&
                    value.asString.isNotBlank()
            ) {
                "资源路径必须为非空字符串"
            }
            return value.asString
        }
        obj.get("path")?.let { paths.add(path(it)) }
        obj.get("paths")?.let { items ->
            require(items.isJsonArray) { "paths 必须为数组" }
            items.asJsonArray.forEach { paths.add(path(it)) }
        }
        require(paths.size in 1..ResourceToolPlanner.MAX_RESOURCES) { "每次必须读取 1–3 个资源" }
        return paths
    }

    private fun resourceAgentModalities(modelId: Long?): Set<String> {
        val model = modelId?.let(CatalogService::modelById) ?: return emptySet()
        return runCatching {
                JsonParser.parseString(model.modalities)
                    .asJsonArray
                    .mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
                    .toSet()
            }
            .getOrDefault(emptySet())
    }

    private fun resourceAgentSupportsKind(modalities: Set<String>): Boolean =
        kind.asStr in modalities

    private fun description(): String =
        when (kind) {
            ResourceKind.IMAGE -> "使用资源 Agent 分析图片。"
            ResourceKind.AUDIO -> "使用资源 Agent 分析音频。"
            ResourceKind.VIDEO -> "使用资源 Agent 分析视频。"
            ResourceKind.FILE -> "使用资源 Agent 分析文件。"
        }

    companion object {
        private val DEFINITION_JSON =
            """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "可选。工作区相对路径、普通绝对路径或协议路径（如 file://、jar://、jrt://）。" },
                "paths": {
                  "type": "array", "maxItems": 3,
                  "items": { "type": "string" },
                  "description": "可选。一个或多个工作区相对路径、普通绝对路径或协议路径（如 file://、jar://、jrt://）。"
                },
                "prompt": { "type": "string", "minLength": 1, "description": "必填。明确说明需要分析的内容。" }
              },
              "required": ["prompt"]
            }
            """
                .trimIndent()
    }
}

internal fun resourceTimeoutSeconds(configured: Long?, agentLimit: Long? = null): Long {
    require(configured == null || configured in 1..Int.MAX_VALUE.toLong()) {
        "工具超时必须为 1–2147483647 秒"
    }
    require(agentLimit == null || agentLimit > 0) { "资源 Agent 最大运行秒数必须为正数" }
    return configured ?: agentLimit ?: 120L
}
