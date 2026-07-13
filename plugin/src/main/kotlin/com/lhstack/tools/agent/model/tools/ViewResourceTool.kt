package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.AgentAttachmentState
import com.lhstack.tools.agent.AgentAttachmentSupport
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import com.lhstack.tools.agent.model.provider.AgentRuntime
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService
import java.io.File

/** 多模态资源查看工具：把路径附件交给配置的资源 Agent 分析。 */
class ViewResourceTool(
    private val kind: ResourceKind,
    private val resourceAgentId: Long,
    workspace: String,
    private val skillsRootDir: File?,
    private val cancel: ModelCancel?,
) : ToolDyn {

    private val workspaceTools = WorkspaceTools(workspace)

    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = kind.toolName,
        description = description(),
        parameters = JsonParser.parseString(DEFINITION_JSON),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val obj = args.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val files = requestedPaths(obj).map(::resolveResourcePath)
        require(files.isNotEmpty()) { "paths 不能为空" }
        val prompt = obj.get("prompt")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultPrompt(files)

        val resourceAgent = AgentService.agentById(resourceAgentId)
            ?: throw IllegalStateException("资源 Agent `$resourceAgentId` 不存在")
        val modalities = resourceAgentModalities(resourceAgent.modelId)
        require(resourceAgentSupportsKind(modalities)) {
            "资源 Agent `${resourceAgent.name}` 绑定模型不支持 `${kind.asStr}` 多模态能力"
        }
        val attachments = files.map { file ->
            AgentAttachmentSupport.normalize(
                AgentAttachmentState(name = file.name, path = file.absolutePath, size = file.length())
            )
        }
        val result = AgentRuntime.execute(
            AgentRuntime.Request(
                agentId = resourceAgentId,
                prompt = prompt,
                triggerType = kind.toolName,
                triggerId = files.joinToString(",") { workspaceTools.displayPath(it) },
                workspace = workspaceTools.canonicalRoot().absolutePath,
                skillsRootDir = skillsRootDir,
                attachments = attachments.map { AgentAttachmentSupport.toUserContent(it, modalities) },
                attachmentSnapshots = attachments.map { AgentAttachmentSupport.snapshotOf(it) },
                cancel = cancel,
                toolCancel = cancel,
            )
        )
        return JsonObject().apply {
            addProperty("agent_id", resourceAgentId)
            addProperty("agent_name", resourceAgent.name)
            addProperty("response", result.output)
            addProperty("model_log_id", result.logId)
        }
    }

    private fun resolveResourcePath(path: String): File {
        val file = File(path)
        val resolved = if (file.isAbsolute) file else workspaceTools.resolveExistingPath(path)
        require(resolved.exists() && resolved.isFile) { "资源文件不存在或不是普通文件: $path" }
        return resolved.canonicalFile
    }

    private fun requestedPaths(obj: JsonObject): List<String> {
        val paths = mutableListOf<String>()
        obj.get("path")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(paths::add)
        obj.get("paths")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.forEach { item ->
                item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(paths::add)
            }
        return paths.distinct()
    }

    private fun resourceAgentModalities(modelId: Long?): Set<String> {
        val model = modelId?.let(CatalogService::modelById) ?: return emptySet()
        return runCatching {
            JsonParser.parseString(model.modalities)
                .asJsonArray
                .mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun resourceAgentSupportsKind(modalities: Set<String>): Boolean =
        kind == ResourceKind.FILE || kind.asStr in modalities

    private fun description(): String = when (kind) {
        ResourceKind.IMAGE -> "Analyze one or more workspace images using the configured image resource agent."
        ResourceKind.AUDIO -> "Analyze one or more workspace audio files using the configured audio resource agent."
        ResourceKind.VIDEO -> "Analyze one or more workspace videos using the configured video resource agent."
        ResourceKind.FILE -> "Analyze one or more workspace files using the configured file resource agent."
    }

    private fun defaultPrompt(files: List<File>): String {
        val list = files.joinToString("\n") { "- ${workspaceTools.displayPath(it)}" }
        return "请分析以下${kindLabel()}资源，并返回关键内容、结论和注意事项：\n$list"
    }

    private fun displayResourcePath(file: File): String = runCatching { workspaceTools.displayPath(file) }.getOrDefault(file.absolutePath)

    private fun kindLabel(): String = when (kind) {
        ResourceKind.IMAGE -> "图片"
        ResourceKind.AUDIO -> "音频"
        ResourceKind.VIDEO -> "视频"
        ResourceKind.FILE -> "文件"
    }

    companion object {
        private val DEFINITION_JSON = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Single workspace-relative resource path." },
                "paths": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "One or more resource paths. Each path supports absolute path or workspace-relative path."
                },
                "prompt": { "type": "string", "description": "Optional analysis instruction for the resource agent." }
              },
              "required": []
            }
        """.trimIndent()
    }
}
