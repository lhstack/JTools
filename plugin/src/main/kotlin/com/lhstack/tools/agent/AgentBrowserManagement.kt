package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.lhstack.tools.agent.model.log.ModelLogService
import com.lhstack.tools.agent.model.tools.BuiltinTools
import com.lhstack.tools.agent.model.tools.RuntimeTools
import com.lhstack.tools.concurrent.AgentExecutors
import com.lhstack.tools.db.config.AgentDistillConfig
import com.lhstack.tools.db.config.AgentEnabledItemsConfig
import com.lhstack.tools.db.config.AgentPersonaConfig
import com.lhstack.tools.db.config.AgentRuntimeConfig
import com.lhstack.tools.db.config.AgentViewResourceRef
import com.lhstack.tools.db.config.AgentViewResourcesConfig
import com.lhstack.tools.db.entity.ModelEntity
import com.lhstack.tools.db.entity.PromptTemplateEntity
import com.lhstack.tools.db.entity.ProviderEntity
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.ChatSessionType
import com.lhstack.tools.db.service.CodingEnvironmentService
import com.lhstack.tools.db.service.ResourceConfigService
import com.lhstack.tools.db.service.SettingService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

internal object AgentBrowserManagement {
    private val mcpJobs = ConcurrentHashMap<String, Future<com.google.gson.JsonElement?>>()
    private val remoteModelJobs = ConcurrentHashMap<String, Future<List<Map<String, String>>>>()

    /** options 仅 type=select 时有值，作为下拉候选；其余类型为空。 */
    private data class ConfigField(
        val key: String,
        val label: String,
        val description: String,
        val type: String,
        val default: String,
        val options: () -> List<Map<String, Any?>> = ::emptyOptions,
    )

    private fun emptyOptions(): List<Map<String, Any?>> = emptyList()

    /** 润色 Agent 候选：空值表示不启用润色入口。 */
    private fun polishAgentOptions(): List<Map<String, Any?>> = buildList {
        add(mapOf("value" to "", "label" to "不启用"))
        AgentService.listAgents()
            .filter { it.enabled && it.id != null }
            .forEach { add(mapOf("value" to it.id.toString(), "label" to it.name)) }
    }

    private val configFields =
        listOf(
            ConfigField(
                AgentPolishService.SETTING_KEY,
                "输入框润色 Agent",
                "对话输入框的润色入口使用的 Agent。留空表示不启用润色。",
                "select",
                "",
                ::polishAgentOptions,
            ),
            ConfigField(
                "coding.compaction_agent_id",
                "上下文压缩 Agent",
                "编码会话手动压缩上下文时使用的 Agent。环境自己的压缩 Agent 优先，这里是全局回退。",
                "select",
                "",
                ::polishAgentOptions,
            ),
            ConfigField(
                "model.max_tool_call_rounds",
                "默认工具调用轮次",
                "模型请求自动执行工具调用循环时允许的默认最大轮次。默认 30。",
                "number",
                "30",
            ),
            ConfigField(
                "model.max_retries",
                "默认模型重试次数",
                "模型 API 请求失败后的默认重试次数。0 表示不重试。",
                "number",
                "0",
            ),
            ConfigField(
                "model.retry_interval_ms",
                "模型重试间隔",
                "模型请求失败后的重试等待时间，单位毫秒。默认 2000。",
                "number",
                "2000",
            ),
            ConfigField(
                "model.dns_servers",
                "模型请求 DNS",
                "模型 API 请求使用的自定义 DNS 服务器，多个用英文逗号分隔。支持 223.5.5.5 或 223.5.5.5:53；留空时不启用自定义 DNS。",
                "text",
                "",
            ),
            ConfigField(
                "model.http_request_timeout_secs",
                "模型 HTTP 请求超时",
                "模型 HTTP 单次请求总超时（秒），0 表示不限时。多模态总执行上限由处理 Agent 的「最大运行秒数」控制，非零 HTTP 超时可能先触发。",
                "number",
                "0",
            ),
            ConfigField(
                "model.http_pool_idle_timeout_secs",
                "模型连接池空闲秒数",
                "模型 HTTP 客户端连接池中空闲连接的保留时长，单位秒。默认 120。",
                "number",
                "120",
            ),
            ConfigField(
                "model.http_pool_max_idle_per_host",
                "每 Host 最大空闲连接",
                "模型 HTTP 客户端连接池为每个 Host 保留的最大空闲连接数。默认 32。",
                "number",
                "32",
            ),
            ConfigField(
                "web_fetch.proxy_enabled",
                "启用 Web Fetch 代理",
                "控制 web_fetch 工具是否使用工具代理地址。关闭时即使填了代理也不会生效。",
                "boolean",
                "false",
            ),
            ConfigField(
                "web_fetch.proxy",
                "Web Fetch 代理地址",
                "web_fetch 工具默认代理。示例：http://127.0.0.1:7890、socks5://127.0.0.1:1080。只影响 web_fetch，不影响模型 API 请求。",
                "text",
                "",
            ),
            ConfigField(
                "web_fetch.timeout_secs",
                "Web Fetch 超时",
                "web_fetch 工具默认请求超时时间，单位秒。",
                "number",
                "30",
            ),
            ConfigField(
                "web_fetch.max_response_bytes",
                "Web Fetch 最大响应字节",
                "web_fetch 工具读取响应体的最大字节数，避免拉取过大的页面或文件。",
                "number",
                "1000000",
            ),
            ConfigField(
                "bash.max_output_chars",
                "Bash 最大输出",
                "Agent 运行里 bash 工具捕获的最大输出字符数。超出后截断并结束进程。",
                "number",
                "8000",
            ),
            ConfigField(
                "coding.bash.max_output_chars",
                "编码会话 Bash 最大输出",
                "编码会话里 bash 工具捕获的最大输出字符数。超出后截断并结束进程。",
                "number",
                "8000",
            ),
        )

    fun handle(project: Project, type: String, payload: JsonObject, changed: () -> Unit): Any? {
        val scoped = com.lhstack.tools.agent.resources.ScopedResources.forProject(project)
        val scopedSkills =
            if (type.startsWith("skill")) scoped.skills(scoped.scope(payload))
            else ResourceConfigService.global
        return when (type) {
            "sessions.list" ->
                ChatSessionService.listVisibleSessions(projectPath(project)).map { session ->
                    mapOf(
                        "id" to session.id,
                        "title" to session.title,
                        "codingEnvironmentId" to session.codingEnvironmentId,
                        "agentId" to session.agentId,
                        "projectPath" to session.projectPath,
                        "createdAt" to session.createdAt,
                        "updatedAt" to session.updatedAt,
                    )
                }
            "session.create" -> {
                val environmentId =
                    payload.longOrNull("codingEnvironmentId")
                        ?: payload.longOrNull("environmentId")
                        ?: throw IllegalArgumentException("创建会话必须指定编码环境")
                ChatSessionService.createSession(
                    title = payload.stringOrNull("title") ?: "新会话",
                    codingEnvironmentId = environmentId,
                    sessionType = ChatSessionType.PROJECT,
                    workspacePath = projectPath(project),
                    agentId = payload.longOrNull("agentId"),
                    providerId = payload.longOrNull("providerId"),
                    modelId = payload.longOrNull("modelId"),
                    promptId = payload.longOrNull("promptId"),
                )
                changed()
                Unit
            }
            "session.rename" -> {
                val session = requireVisibleSession(project, long(payload, "id"))
                ChatSessionService.renameSession(session.id, string(payload, "title"))
                changed()
                Unit
            }
            "session.delete" -> {
                val id = long(payload, "id")
                val session = requireVisibleSession(project, id)
                ModelLogService.deleteChatTurnsForSession(session.id)
                ChatSessionService.deleteSession(id)
                changed()
                Unit
            }
            "modelLogs.list" ->
                ModelLogService.modelLogPage(
                        int(payload, "page", 1),
                        int(payload, "pageSize", 30),
                        payload.stringOrNull("status"),
                        payload.longOrNull("agentId"),
                        payload.stringOrNull("messageType"),
                        payload.stringOrNull("keyword"),
                    )
                    .let { page ->
                        mapOf(
                            "rows" to page.rows.map(::log),
                            "total" to page.total,
                            "page" to page.page,
                            "pageSize" to page.pageSize,
                            "agents" to
                                AgentService.listAgents().map {
                                    mapOf("id" to it.id, "name" to it.name)
                                },
                        )
                    }
            "modelLogs.detail" ->
                log(requireNotNull(ModelLogService.modelLogById(long(payload, "id"))) { "日志不存在" })
            "modelLogs.delete" -> {
                ModelLogService.deleteModelLog(long(payload, "id"))
                Unit
            }
            "modelLogs.deleteBatch" -> {
                ModelLogService.deleteModelLogs(payload.getAsJsonArray("ids").map { it.asLong })
                Unit
            }
            "catalog.get" ->
                mapOf(
                    "providers" to
                        CatalogService.listProvidersWithModels().map {
                            mapOf(
                                "provider" to providerMap(it.provider),
                                "models" to it.models.map(::modelMap),
                            )
                        },
                    "prompts" to CatalogService.listPromptTemplates().map(::promptMap),
                )
            "provider.save" -> {
                val value = provider(payload)
                require(value.name.isNotBlank()) { "供应商名称不能为空" }
                CatalogService.saveProvider(value)
            }
            "provider.delete" -> {
                CatalogService.deleteProvider(long(payload, "id"))
                Unit
            }
            "provider.remoteModels.start" -> {
                val provider =
                    requireNotNull(CatalogService.providerById(long(payload, "id"))) { "供应商不存在" }
                val jobId = UUID.randomUUID().toString()
                remoteModelJobs[jobId] =
                    AgentExecutors.shared.submit<List<Map<String, String>>> {
                        AwakeRemoteModelService.listModels(provider).map {
                            mapOf("id" to it, "displayName" to it)
                        }
                    }
                mapOf("jobId" to jobId)
            }
            "provider.remoteModels.poll" -> pollRemoteModelJob(string(payload, "jobId"))
            "model.save" -> {
                val value = model(payload)
                require(value.alias.isNotBlank()) { "模型别名不能为空" }
                require(value.modelId.isNotBlank()) { "模型 ID 不能为空" }
                CatalogService.saveModel(value)
            }
            "model.delete" -> {
                CatalogService.deleteModel(long(payload, "id"))
                Unit
            }
            "prompts.list" -> CatalogService.listPromptTemplates().map(::promptMap)
            "prompt.save" -> {
                val value = prompt(payload)
                require(value.name.isNotBlank()) { "提示词名称不能为空" }
                CatalogService.savePromptTemplate(value)
            }
            "prompt.delete" -> {
                CatalogService.deletePromptTemplate(long(payload, "id"))
                Unit
            }
            "agents.catalog" ->
                mapOf(
                    "agents" to AgentService.listAgents().map(::agentMap),
                    "providers" to
                        CatalogService.listProvidersWithModels().map {
                            mapOf(
                                "id" to it.provider.id,
                                "name" to it.provider.name,
                                "models" to
                                    it.models.map { model ->
                                        mapOf(
                                            "id" to model.id,
                                            "name" to model.alias,
                                            "alias" to model.alias,
                                        )
                                    },
                            )
                        },
                    "prompts" to
                        CatalogService.listPromptTemplates().map {
                            mapOf("id" to it.id, "name" to it.name)
                        },
                    "skills" to scopedSkills.listSkills().map { it.name },
                    "tools" to BuiltinTools.BUILTIN_TOOLS.map { it.name },
                    "pluginFunctionGroups" to
                        PluginFunctionToolSupport.groups(project).map { group ->
                            mapOf(
                                "pluginKey" to group.pluginKey,
                                "pluginName" to group.pluginName,
                                "functions" to
                                    group.functions.map { function ->
                                        mapOf(
                                            "key" to function.key,
                                            "name" to function.functionName,
                                            "toolName" to function.toolName,
                                            "description" to function.description,
                                        )
                                    },
                            )
                        },
                )
            "agent.save" -> {
                saveAgent(project, payload)
                changed()
                Unit
            }
            "agent.delete" -> {
                AgentService.deleteAgent(long(payload, "id"))
                changed()
                Unit
            }
            "skills.list" -> scopedSkills.listSkills().map { skillMap(it) }
            "skill.files" -> skillFileNode(scopedSkills.skillFileTree(string(payload, "name")))
            "skill.create" ->
                mapOf(
                    "name" to
                        scopedSkills.createSkill(
                            string(payload, "name"),
                            string(payload, "description"),
                        )
                )
            "skill.import" -> {
                val report = scopedSkills.importSkills(string(payload, "sourcePath"))
                mapOf(
                    "imported" to report.imported.map(::skillMap),
                    "failed" to report.failed.map { mapOf("name" to it.name, "error" to it.error) },
                )
            }
            "skill.chooseImportDir" -> chooseSkillImportDir(project)
            "skill.delete" -> {
                scopedSkills.deleteSkill(string(payload, "name"))
                Unit
            }
            "skill.deleteEntry" -> {
                scopedSkills.deleteSkillEntry(string(payload, "name"), string(payload, "path"))
                Unit
            }
            "skill.read" ->
                scopedSkills
                    .readSkillFile(string(payload, "name"), payload.get("path")?.asString)
                    .let { mapOf("skill" to it.skill, "path" to it.path, "content" to it.content) }
            "skill.save" -> {
                scopedSkills.writeSkillFile(
                    string(payload, "name"),
                    string(payload, "path"),
                    string(payload, "content"),
                )
                Unit
            }
            "skill.createFile" ->
                mapOf(
                    "path" to
                        scopedSkills.createSkillFile(
                            string(payload, "name"),
                            payload.stringOrNull("parentPath"),
                            string(payload, "fileName"),
                        )
                )
            "skill.createDirectory" ->
                mapOf(
                    "path" to
                        scopedSkills.createSkillDirectory(
                            string(payload, "name"),
                            payload.stringOrNull("parentPath"),
                            string(payload, "directoryName"),
                        )
                )
            "config.get" ->
                configFields.map {
                    mapOf(
                        "key" to it.key,
                        "label" to it.label,
                        "description" to it.description,
                        "type" to it.type,
                        "value" to (SettingService.setting(it.key) ?: it.default),
                        "options" to it.options(),
                    )
                }
            // 润色 Agent 等配置直接影响对话面板入口，保存后需要通知宿主同步状态。
            "config.save" -> {
                payload.getAsJsonObject("values").entrySet().forEach { (key, value) ->
                    SettingService.setSetting(key, value.asString)
                }
                changed()
                Unit
            }
            "mcp.list" -> scoped.mcps(scoped.scope(payload))
            "mcp.save" ->
                scoped.saveMcp(
                    scoped.scope(payload),
                    payload,
                    payload.get("id") == null || payload.get("id").isJsonNull,
                )
            "mcp.delete" -> {
                scoped.deleteMcp(scoped.scope(payload), string(payload, "id"))
                changed()
                Unit
            }
            "mcp.tools" -> {
                val scope=scoped.scope(payload)
                val server=scoped.mcp(scope,string(payload,"id"))
                require(server.enabled!=0) { "MCP 已禁用" }
                com.lhstack.tools.agent.resources.McpInspectionSupport.tools(server,if(scope=="project")project.basePath else null)
            }
            "mcp.test" -> {
                val jobId = UUID.randomUUID().toString()
                val input = payload.deepCopy()
                mcpJobs[jobId] =
                    AgentExecutors.shared.submit<com.google.gson.JsonElement?> {
                        com.lhstack.tools.agent.resources
                            .ScopedCliCommands(
                                project.basePath?.let { java.io.File(it) },
                                null,
                                null,
                            )
                            .execute(type, input)
                    }
                mapOf("jobId" to jobId)
            }
            "mcp.poll" -> {
                val jobId = string(payload, "jobId")
                val job = mcpJobs[jobId] ?: error("MCP 检查任务不存在")
                if (!job.isDone) mapOf("done" to false)
                else {
                    mcpJobs.remove(jobId)
                    mapOf("done" to true, "result" to job.get())
                }
            }
            "subagents.list" ->
                if (scoped.scope(payload) == "project")
                    com.lhstack.tools.agent.resources
                        .ProjectResourceStore(java.io.File(project.basePath ?: error("无项目")))
                        .read("subAgents.json", "subAgents")
                else error("全局 SubAgent 请使用 Agent 管理")
            "subagent.save" -> {
                require(scoped.scope(payload) == "project")
                scoped.saveProjectAgent(
                    string(payload, "id"),
                    payload.getAsJsonObject("definition"),
                    payload.get("create")?.asBoolean == true,
                )
                changed()
                Unit
            }
            "subagent.delete" -> {
                require(scoped.scope(payload) == "project")
                scoped.deleteProjectAgent(string(payload, "id"))
                changed()
                Unit
            }
            "environments.catalog" ->
                mapOf(
                    "mcpServers" to scoped.mcps("global"),
                    "environments" to CodingEnvironmentService.listAll().map(::environmentMap),
                    "agents" to
                        AgentService.listAgents()
                            .filter { it.enabled }
                            .map { mapOf("id" to it.id, "name" to it.name) },
                    "skills" to
                        scopedSkills.listSkills().map {
                            mapOf(
                                "name" to it.name,
                                "description" to it.description,
                                "available" to it.available,
                                "fail_reason" to it.failReason,
                            )
                        },
                    "tools" to CodingEnvironmentService.toolNames,
                    "pluginFunctionGroups" to
                        PluginFunctionToolSupport.groups(project).map { group ->
                            mapOf(
                                "pluginKey" to group.pluginKey,
                                "pluginName" to group.pluginName,
                                "functions" to
                                    group.functions.map { function ->
                                        mapOf(
                                            "key" to function.key,
                                            "name" to function.functionName,
                                            "toolName" to function.toolName,
                                            "description" to function.description,
                                        )
                                    },
                            )
                        },
                )
            "environment.create" -> {
                val record = CodingEnvironmentService.create(string(payload, "name"))
                changed()
                environmentMap(record)
            }
            "environment.rename" -> {
                CodingEnvironmentService.rename(long(payload, "id"), string(payload, "name"))
                changed()
                Unit
            }
            "environment.enabled" -> {
                val record =
                    CodingEnvironmentService.setEnabled(
                        long(payload, "id"),
                        payload.boolean("enabled", true),
                    )
                changed()
                environmentMap(record)
            }
            "environment.save" -> {
                val config =
                    payload.get("config")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: throw IllegalArgumentException("缺少环境配置")
                val record = CodingEnvironmentService.updateConfig(long(payload, "id"), config)
                changed()
                environmentMap(record)
            }
            "environment.delete" -> {
                CodingEnvironmentService.delete(long(payload, "id"))
                changed()
                Unit
            }
            else -> error("Unsupported Agent UI command: $type")
        }
    }

    private fun environmentMap(record: com.lhstack.tools.db.service.CodingEnvironmentRecord) =
        mapOf(
            "id" to record.id,
            "name" to record.name,
            "enabled" to record.enabled,
            "config" to record.config,
            "created_at" to record.createdAt,
            "updated_at" to record.updatedAt,
        )

    private fun requireVisibleSession(project: Project, id: Long) =
        requireNotNull(ChatSessionService.visibleSessionById(id, projectPath(project))) {
            "会话不存在或不属于当前项目"
        }

    private fun projectPath(project: Project): String =
        ChatSessionService.normalizeProjectPath(
            project.basePath ?: throw IllegalStateException("当前项目没有项目路径")
        )

    private fun pollRemoteModelJob(jobId: String): Map<String, Any> {
        val job = requireNotNull(remoteModelJobs[jobId]) { "远端模型获取任务不存在或已结束" }
        if (!job.isDone) return mapOf("status" to "running")
        remoteModelJobs.remove(jobId, job)
        return try {
            mapOf("status" to "completed", "models" to job.get())
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            mapOf("status" to "failed", "error" to (cause.message ?: cause.toString()))
        }
    }

    private fun log(value: ModelLogService.ModelLogRecord) =
        mapOf(
            "id" to value.id,
            "sourceType" to value.sourceType,
            "sourceId" to value.sourceId,
            "agentId" to value.agentId,
            "messageType" to value.messageType,
            "providerName" to value.providerName,
            "modelName" to value.modelName,
            "status" to value.status,
            "requestData" to value.requestData,
            "responseData" to value.responseData,
            "errorData" to value.errorData,
            "startedAt" to value.startedAt,
            "finishedAt" to value.finishedAt,
            "userMessageAt" to value.userMessageAt,
            "assistantMessageAt" to value.assistantMessageAt,
            "createdAt" to value.createdAt,
            "usage" to logUsage(value.responseData),
        )

    private fun logUsage(response: JsonObject): JsonObject {
        val structured =
            response.get("structured_response")?.takeIf { it.isJsonObject }?.asJsonObject
        return structured?.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: response.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject()
    }

    private fun providerMap(v: ProviderEntity) =
        mapOf(
            "id" to v.id,
            "name" to v.name,
            "kind" to v.kind,
            "apiKey" to v.apiKey,
            "baseUrl" to v.baseUrl,
            "api" to v.api,
            "anthropicVersion" to v.anthropicVersion,
            "providerConfig" to parseJsonObject(v.providerConfig),
            "enabled" to v.enabled,
        )

    private fun modelMap(v: ModelEntity) =
        mapOf(
            "id" to v.id,
            "providerId" to v.providerId,
            "alias" to v.alias,
            "modelId" to v.modelId,
            "displayName" to v.displayName,
            "api" to v.api,
            "contextWindow" to v.contextWindow,
            "modalities" to v.modalities,
            "modelParams" to v.modelParams,
            "executionParams" to v.executionParams,
            "additionalParams" to v.additionalParams,
            "enabled" to v.enabled,
        )

    private fun promptMap(v: PromptTemplateEntity) =
        mapOf("id" to v.id, "name" to v.name, "preamble" to v.preamble, "updatedAt" to v.updatedAt)

    private fun chooseSkillImportDir(project: Project): String? {
        val descriptor =
            FileChooserDescriptor(false, true, false, false, false, false).apply {
                title = "选择要导入的技能目录"
                description = "将递归导入包含 SKILL.md 的技能目录"
            }
        val selected = arrayOfNulls<VirtualFile>(1)
        val choose = Runnable { selected[0] = FileChooser.chooseFile(descriptor, project, null) }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) choose.run() else application.invokeAndWait(choose)
        return selected[0]?.path
    }

    private fun skillFileNode(v: ResourceConfigService.SkillFileNode): Map<String, Any?> =
        mapOf(
            "name" to v.name,
            "path" to v.path,
            "kind" to v.kind,
            "size" to v.size,
            "children" to v.children.map(::skillFileNode),
        )

    private fun skillMap(v: ResourceConfigService.SkillDirectoryRecord) =
        mapOf(
            "name" to v.name,
            "relativePath" to v.relativePath,
            "path" to v.path,
            "description" to v.description,
            "available" to v.available,
            "failReason" to v.failReason,
            "files" to v.files,
        )

    private fun agentMap(v: com.lhstack.tools.db.service.AgentRecord) =
        mapOf(
            "id" to v.id,
            "name" to v.name,
            "description" to v.description,
            "enabled" to v.enabled,
            "providerId" to v.providerId,
            "modelId" to v.modelId,
            "promptId" to v.promptId,
            "extraPrompt" to v.extraPrompt,
            "outputMode" to v.outputMode,
            "maxRuntimeSecs" to v.maxRuntimeSecs,
            "tags" to v.tags,
            "includeHistory" to v.runtimeParams.includeHistory,
            "maxHistoryMessages" to v.runtimeParams.maxHistoryMessages,
            "toolCallRetentionRounds" to v.runtimeParams.toolCallRetentionRounds,
            "toolOverrides" to
                com.lhstack.tools.agent.resources.ResourceSelection.fromLists(
                    v.extConfig.tools.enabled,
                    v.extConfig.tools.disabled,
                ),
            "skillOverrides" to
                com.lhstack.tools.agent.resources.ResourceSelection.fromLists(
                    v.extConfig.skills.enabled,
                    v.extConfig.skills.disabled,
                ),
            "pluginOverrides" to
                com.lhstack.tools.agent.resources.ResourceSelection.fromLists(
                    v.extConfig.pluginFunctions.enabled,
                    v.extConfig.pluginFunctions.disabled,
                ),
            "tools" to
                com.lhstack.tools.agent.resources.ResourceSelection.enabled(
                    RuntimeTools.REGISTERED_BUILTIN_TOOL_NAMES,
                    com.lhstack.tools.agent.resources.ResourceSelection.fromLists(
                        v.extConfig.tools.enabled,
                        v.extConfig.tools.disabled,
                    ),
                    v.extConfig.tools.includeNew,
                ),
            "toolsIncludeNew" to v.extConfig.tools.includeNew,
            "skills" to
                com.lhstack.tools.agent.resources.ResourceSelection.enabled(
                    ResourceConfigService.global.listSkills().map { it.name },
                    com.lhstack.tools.agent.resources.ResourceSelection.fromLists(
                        v.extConfig.skills.enabled,
                        v.extConfig.skills.disabled,
                    ),
                    v.extConfig.skills.includeNew,
                ),
            "skillsIncludeNew" to v.extConfig.skills.includeNew,
            "pluginFunctions" to v.extConfig.pluginFunctions.enabled,
            "pluginFunctionsIncludeNew" to v.extConfig.pluginFunctions.includeNew,
            "skillPromptEnabled" to v.extConfig.skillPromptEnabled,
            "viewResources" to
                mapOf(
                    "image" to viewResourceMap(v.extConfig.viewResources.image),
                    "audio" to viewResourceMap(v.extConfig.viewResources.audio),
                    "video" to viewResourceMap(v.extConfig.viewResources.video),
                    "file" to viewResourceMap(v.extConfig.viewResources.file),
                ),
            "persona" to
                mapOf(
                    "memory" to v.extConfig.persona.memory,
                    "memoryMaxChars" to v.extConfig.persona.memoryMaxChars,
                    "behaviorHabits" to v.extConfig.persona.behaviorHabits,
                    "behaviorHabitsMaxChars" to v.extConfig.persona.behaviorHabitsMaxChars,
                    "soul" to v.extConfig.persona.soul,
                    "soulMaxChars" to v.extConfig.persona.soulMaxChars,
                    "profile" to v.extConfig.persona.profile,
                    "profileMaxChars" to v.extConfig.persona.profileMaxChars,
                    "guardrails" to v.extConfig.persona.guardrails,
                    "guardrailsMaxChars" to v.extConfig.persona.guardrailsMaxChars,
                ),
            "updatedAt" to v.updatedAt,
        )

    private fun viewResourceMap(v: AgentViewResourceRef) =
        mapOf("enabled" to v.enabled, "agentId" to v.agentId, "timeoutSecs" to v.timeoutSecs)

    private fun selectionConfig(
        p: JsonObject,
        key: String,
        includeKey: String,
    ): AgentEnabledItemsConfig {
        val overrides = p.getAsJsonObject(key) ?: error("缺少 $key")
        require(
            overrides.entrySet().all {
                it.value.isJsonPrimitive && it.value.asJsonPrimitive.isBoolean
            }
        ) {
            "$key 必须是布尔映射"
        }
        return AgentEnabledItemsConfig(
            overrides.entrySet().filter { it.value.asBoolean }.map { it.key },
            p.boolean(includeKey, false),
            overrides.entrySet().filterNot { it.value.asBoolean }.map { it.key },
        )
    }

    private fun saveAgent(project: Project, p: JsonObject) {
        val existing = p.longOrNull("id")?.let(AgentService::agentById)
        val base =
            existing
                ?: com.lhstack.tools.db.service.AgentRecord(
                    null,
                    "",
                    null,
                    true,
                    null,
                    null,
                    null,
                    null,
                    AgentRuntimeConfig(),
                    com.lhstack.tools.db.config.AgentCapabilityConfig(),
                    com.lhstack.tools.db.config.AgentDistillConfig(),
                    "text",
                    null,
                    emptyList(),
                    null,
                    null,
                )
        AgentService.upsertAgent(
            base.copy(
                name = string(p, "name"),
                description = p.stringOrNull("description"),
                enabled = p.boolean("enabled", true),
                providerId = p.longOrNull("providerId"),
                modelId = p.longOrNull("modelId"),
                promptId = p.longOrNull("promptId"),
                extraPrompt = p.stringOrNull("extraPrompt"),
                outputMode = string(p, "outputMode").ifBlank { "text" },
                maxRuntimeSecs = p.longOrNull("maxRuntimeSecs"),
                tags = stringList(p, "tags"),
                runtimeParams =
                    AgentRuntimeConfig(
                        includeHistory = p.boolean("includeHistory", false),
                        maxHistoryMessages = p.optionalInt("maxHistoryMessages"),
                        toolCallRetentionRounds = p.optionalInt("toolCallRetentionRounds"),
                    ),
                extConfig =
                    base.extConfig.copy(
                        tools = selectionConfig(p, "toolOverrides", "toolsIncludeNew"),
                        skills = selectionConfig(p, "skillOverrides", "skillsIncludeNew"),
                        pluginFunctions =
                            selectionConfig(p, "pluginOverrides", "pluginFunctionsIncludeNew"),
                        skillPromptEnabled = p.boolean("skillPromptEnabled", false),
                        viewResources = parseViewResources(p.getAsJsonObject("viewResources")),
                        persona = parsePersona(p.getAsJsonObject("persona")),
                    ),
                distillConfig = AgentDistillConfig(),
            )
        )
    }

    private fun parseViewResources(p: JsonObject?): AgentViewResourcesConfig =
        AgentViewResourcesConfig(
            image = parseViewResource(p?.getAsJsonObject("image")),
            audio = parseViewResource(p?.getAsJsonObject("audio")),
            video = parseViewResource(p?.getAsJsonObject("video")),
            file = parseViewResource(p?.getAsJsonObject("file")),
        )

    private fun parseViewResource(p: JsonObject?): AgentViewResourceRef =
        AgentViewResourceRef(
            enabled = p?.boolean("enabled", false) ?: false,
            agentId = p?.longOrNull("agentId"),
            timeoutSecs = p?.longOrNull("timeoutSecs").also { value ->
                require(value == null || value in 1..Int.MAX_VALUE.toLong()) {
                    "工具超时必须为 1–2147483647 秒"
                }
            },
        )

    private fun parsePersona(p: JsonObject?): AgentPersonaConfig =
        AgentPersonaConfig(
            memory = p?.let { string(it, "memory") }.orEmpty(),
            memoryMaxChars = p?.get("memoryMaxChars")?.asInt ?: 512,
            behaviorHabits = p?.let { string(it, "behaviorHabits") }.orEmpty(),
            behaviorHabitsMaxChars = p?.get("behaviorHabitsMaxChars")?.asInt ?: 512,
            soul = p?.let { string(it, "soul") }.orEmpty(),
            soulMaxChars = p?.get("soulMaxChars")?.asInt ?: 512,
            profile = p?.let { string(it, "profile") }.orEmpty(),
            profileMaxChars = p?.get("profileMaxChars")?.asInt ?: 512,
            guardrails = p?.let { string(it, "guardrails") }.orEmpty(),
            guardrailsMaxChars = p?.get("guardrailsMaxChars")?.asInt ?: 512,
        )

    private fun stringList(p: JsonObject, key: String): List<String> =
        p.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()

    private fun provider(p: JsonObject) =
        ProviderEntity().apply {
            id = p.longOrNull("id")
            name = string(p, "name")
            kind = string(p, "kind").ifBlank { "openai" }
            apiKey = p.stringOrNull("apiKey")
            baseUrl = p.stringOrNull("baseUrl")
            api = p.stringOrNull("api")
            anthropicVersion = p.stringOrNull("anthropicVersion")
            providerConfig =
                p.get("providerConfig")?.let { if (it.isJsonObject) it.toString() else it.asString }
                    ?: "{}"
            enabled = if (p.boolean("enabled", true)) 1 else 0
        }

    private fun model(p: JsonObject) =
        ModelEntity().apply {
            id = p.longOrNull("id")
            providerId = long(p, "providerId")
            alias = string(p, "alias")
            modelId = string(p, "modelId")
            displayName = p.stringOrNull("displayName")
            api = p.stringOrNull("api")
            contextWindow = p.longOrNull("contextWindow")
            modalities =
                p.get("modalities")?.let { if (it.isJsonArray) it.toString() else it.asString }
                    ?: "[]"
            modelParams = p.stringOrNull("modelParams")
            executionParams = p.stringOrNull("executionParams")
            additionalParams = p.stringOrNull("additionalParams")
            enabled = if (p.boolean("enabled", true)) 1 else 0
        }

    private fun parseJsonObject(value: String?): JsonObject =
        runCatching {
                com.google.gson.JsonParser.parseString(value?.takeIf { it.isNotBlank() } ?: "{}")
                    .asJsonObject
            }
            .getOrElse { JsonObject() }

    private fun prompt(p: JsonObject) =
        PromptTemplateEntity().apply {
            id = p.longOrNull("id")
            name = string(p, "name")
            preamble = string(p, "preamble")
        }

    private fun string(p: JsonObject, key: String) =
        p.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.stringOrNull(key: String) = string(this, key).takeIf { it.isNotBlank() }

    private fun long(p: JsonObject, key: String) = requireNotNull(p.longOrNull(key)) { "$key 不能为空" }

    private fun JsonObject.optionalInt(key: String): Int? =
        get(key)
            ?.takeUnless {
                it.isJsonNull ||
                    (it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isBlank())
            }
            ?.asInt

    private fun JsonObject.longOrNull(key: String) =
        get(key)?.takeUnless { it.isJsonNull || it.asString.isBlank() }?.asLong

    private fun int(p: JsonObject, key: String, default: Int) = p.get(key)?.asInt ?: default

    private fun JsonObject.boolean(key: String, default: Boolean) = get(key)?.asBoolean ?: default
}
