package com.lhstack.tools.agent

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.lhstack.tools.agent.model.log.ModelLogService
import com.lhstack.tools.db.entity.ModelEntity
import com.lhstack.tools.db.entity.PromptTemplateEntity
import com.lhstack.tools.db.entity.ProviderEntity
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.ChatSessionType
import com.lhstack.tools.db.service.ResourceConfigService
import com.lhstack.tools.db.service.SettingService
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.config.AgentRuntimeConfig
import com.lhstack.tools.db.config.AgentEnabledItemsConfig
import com.lhstack.tools.db.config.AgentPersonaConfig
import com.lhstack.tools.db.config.AgentViewResourcesConfig
import com.lhstack.tools.db.config.AgentViewResourceRef
import com.lhstack.tools.db.config.AgentDistillConfig
import com.lhstack.tools.agent.model.tools.BuiltinTools
import com.lhstack.tools.concurrent.AgentExecutors
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

internal object AgentBrowserManagement {
    private val remoteModelJobs = ConcurrentHashMap<String, Future<List<Map<String, String>>>>()

    private data class ConfigField(val key: String, val label: String, val type: String, val default: String)
    private val configFields = listOf(
        ConfigField("model.max_tool_call_rounds", "默认工具调用轮次", "number", "30"),
        ConfigField("model.max_retries", "默认模型重试次数", "number", "0"),
        ConfigField("model.dns_servers", "模型请求 DNS", "text", ""),
        ConfigField("model.http_request_timeout_secs", "模型 HTTP 请求超时", "number", "0"),
        ConfigField("model.http_max_retries_per_request", "模型 HTTP 单请求重试次数", "number", "2"),
        ConfigField("model.http_pool_idle_timeout_secs", "模型连接池空闲秒数", "number", "120"),
        ConfigField("model.http_pool_max_idle_per_host", "每 Host 最大空闲连接", "number", "32"),
        ConfigField("agent.distillation.max_threads", "Agent 蒸馏最大线程数", "number", "1"),
        ConfigField("web_fetch.proxy_enabled", "启用 Web Fetch 代理", "boolean", "false"),
        ConfigField("web_fetch.proxy", "Web Fetch 代理地址", "text", ""),
        ConfigField("web_fetch.timeout_secs", "Web Fetch 超时", "number", "30"),
        ConfigField("web_fetch.max_response_bytes", "Web Fetch 最大响应字节", "number", "1000000"),
    )

    fun handle(project: Project, type: String, payload: JsonObject, changed: () -> Unit): Any? = when (type) {
        "sessions.list" -> ChatSessionService.listVisibleSessions(projectPath(project)).map { session ->
            mapOf(
                "id" to session.id,
                "title" to session.title,
                "agentId" to session.agentId,
                "sessionType" to session.sessionType.value,
                "projectPath" to session.projectPath,
                "createdAt" to session.createdAt,
                "updatedAt" to session.updatedAt,
            )
        }
        "session.create" -> {
            val type = ChatSessionType.from(string(payload, "sessionType"))
            ChatSessionService.createSession(
                payload.stringOrNull("title") ?: "新会话",
                payload.longOrNull("agentId"),
                type,
                projectPath(project).takeIf { type == ChatSessionType.PROJECT },
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
        "modelLogs.list" -> ModelLogService.modelLogPage(
            int(payload, "page", 1), int(payload, "pageSize", 30), payload.stringOrNull("status"),
            payload.longOrNull("agentId"), payload.stringOrNull("messageType"), payload.stringOrNull("keyword"),
        ).let { page -> mapOf(
            "rows" to page.rows.map(::log), "total" to page.total, "page" to page.page, "pageSize" to page.pageSize,
            "agents" to AgentService.listAgents().map { mapOf("id" to it.id, "name" to it.name) },
        ) }
        "modelLogs.detail" -> log(requireNotNull(ModelLogService.modelLogById(long(payload, "id"))) { "日志不存在" })
        "modelLogs.delete" -> { ModelLogService.deleteModelLog(long(payload, "id")); Unit }
        "modelLogs.deleteBatch" -> { ModelLogService.deleteModelLogs(payload.getAsJsonArray("ids").map { it.asLong }); Unit }
        "catalog.get" -> mapOf("providers" to CatalogService.listProvidersWithModels().map { mapOf("provider" to providerMap(it.provider), "models" to it.models.map(::modelMap)) }, "prompts" to CatalogService.listPromptTemplates().map(::promptMap))
        "provider.save" -> { val value = provider(payload); require(value.name.isNotBlank()) { "供应商名称不能为空" }; CatalogService.saveProvider(value) }
        "provider.delete" -> { CatalogService.deleteProvider(long(payload, "id")); Unit }
        "provider.remoteModels.start" -> {
            val provider = requireNotNull(CatalogService.providerById(long(payload, "id"))) { "供应商不存在" }
            val jobId = UUID.randomUUID().toString()
            remoteModelJobs[jobId] = AgentExecutors.shared.submit<List<Map<String, String>>> {
                AwakeRemoteModelService.listModels(provider).map { mapOf("id" to it, "displayName" to it) }
            }
            mapOf("jobId" to jobId)
        }
        "provider.remoteModels.poll" -> pollRemoteModelJob(string(payload, "jobId"))
        "model.save" -> { val value = model(payload); require(value.alias.isNotBlank()) { "模型别名不能为空" }; require(value.modelId.isNotBlank()) { "模型 ID 不能为空" }; CatalogService.saveModel(value) }
        "model.delete" -> { CatalogService.deleteModel(long(payload, "id")); Unit }
        "prompts.list" -> CatalogService.listPromptTemplates().map(::promptMap)
        "prompt.save" -> { val value = prompt(payload); require(value.name.isNotBlank()) { "提示词名称不能为空" }; CatalogService.savePromptTemplate(value) }
        "prompt.delete" -> { CatalogService.deletePromptTemplate(long(payload, "id")); Unit }
        "agents.catalog" -> mapOf(
            "agents" to AgentService.listAgents().map(::agentMap),
            "providers" to CatalogService.listProvidersWithModels().map { mapOf("id" to it.provider.id, "name" to it.provider.name, "models" to it.models.map { model -> mapOf("id" to model.id, "name" to model.alias) }) },
            "prompts" to CatalogService.listPromptTemplates().map { mapOf("id" to it.id, "name" to it.name) },
            "skills" to ResourceConfigService.listSkills().map { it.name },
            "tools" to BuiltinTools.BUILTIN_TOOLS.map { it.name },
            "pluginFunctionGroups" to PluginFunctionToolSupport.groups(project).map { group -> mapOf(
                "pluginKey" to group.pluginKey,
                "pluginName" to group.pluginName,
                "functions" to group.functions.map { function -> mapOf("key" to function.key, "name" to function.functionName, "toolName" to function.toolName, "description" to function.description) },
            ) },
        )
        "agent.save" -> { saveAgent(project, payload); changed(); Unit }
        "agent.delete" -> { AgentService.deleteAgent(long(payload, "id")); changed(); Unit }
        "skills.list" -> ResourceConfigService.listSkills().map { skillMap(it) }
        "skill.files" -> skillFileNode(ResourceConfigService.skillFileTree(string(payload, "name")))
        "skill.create" -> mapOf("name" to ResourceConfigService.createSkill(string(payload, "name")))
        "skill.delete" -> { ResourceConfigService.deleteSkill(string(payload, "name")); Unit }
        "skill.deleteEntry" -> { ResourceConfigService.deleteSkillEntry(string(payload, "name"), string(payload, "path")); Unit }
        "skill.read" -> ResourceConfigService.readSkillFile(string(payload, "name"), payload.get("path")?.asString).let { mapOf("skill" to it.skill, "path" to it.path, "content" to it.content) }
        "skill.save" -> { ResourceConfigService.writeSkillFile(string(payload, "name"), string(payload, "path"), string(payload, "content")); Unit }
        "skill.createFile" -> mapOf("path" to ResourceConfigService.createSkillFile(string(payload, "name"), payload.stringOrNull("parentPath"), string(payload, "fileName")))
        "skill.createDirectory" -> mapOf("path" to ResourceConfigService.createSkillDirectory(string(payload, "name"), payload.stringOrNull("parentPath"), string(payload, "directoryName")))
        "config.get" -> configFields.map { mapOf("key" to it.key, "label" to it.label, "type" to it.type, "value" to (SettingService.setting(it.key) ?: it.default)) }
        "config.save" -> { payload.getAsJsonObject("values").entrySet().forEach { (key, value) -> SettingService.setSetting(key, value.asString) }; Unit }
        else -> error("Unsupported Agent UI command: $type")
    }

    private fun requireVisibleSession(project: Project, id: Long) =
        requireNotNull(ChatSessionService.visibleSessionById(id, projectPath(project))) { "会话不存在或不属于当前项目" }

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

    private fun log(value: ModelLogService.ModelLogRecord) = mapOf(
        "id" to value.id, "sourceType" to value.sourceType, "sourceId" to value.sourceId,
        "agentId" to value.agentId, "messageType" to value.messageType, "providerName" to value.providerName,
        "modelName" to value.modelName, "status" to value.status, "requestData" to value.requestData,
        "responseData" to value.responseData, "errorData" to value.errorData, "startedAt" to value.startedAt,
        "finishedAt" to value.finishedAt, "userMessageAt" to value.userMessageAt,
        "assistantMessageAt" to value.assistantMessageAt, "createdAt" to value.createdAt,
        "usage" to logUsage(value.responseData),
    )
    private fun logUsage(response: JsonObject): JsonObject {
        val structured = response.get("structured_response")?.takeIf { it.isJsonObject }?.asJsonObject
        return structured?.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: response.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject()
    }
    private fun providerMap(v: ProviderEntity) = mapOf("id" to v.id, "name" to v.name, "kind" to v.kind, "apiKey" to v.apiKey, "baseUrl" to v.baseUrl, "api" to v.api, "anthropicVersion" to v.anthropicVersion, "providerConfig" to parseJsonObject(v.providerConfig), "enabled" to v.enabled)
    private fun modelMap(v: ModelEntity) = mapOf("id" to v.id, "providerId" to v.providerId, "alias" to v.alias, "modelId" to v.modelId, "displayName" to v.displayName, "api" to v.api, "contextWindow" to v.contextWindow, "modalities" to v.modalities, "modelParams" to v.modelParams, "executionParams" to v.executionParams, "additionalParams" to v.additionalParams, "enabled" to v.enabled)
    private fun promptMap(v: PromptTemplateEntity) = mapOf("id" to v.id, "name" to v.name, "preamble" to v.preamble, "updatedAt" to v.updatedAt)
    private fun skillFileNode(v: ResourceConfigService.SkillFileNode): Map<String, Any?> = mapOf(
        "name" to v.name,
        "path" to v.path,
        "kind" to v.kind,
        "size" to v.size,
        "children" to v.children.map(::skillFileNode),
    )

    private fun skillMap(v: ResourceConfigService.SkillDirectoryRecord) = mapOf("name" to v.name, "relativePath" to v.relativePath, "path" to v.path, "description" to v.description, "available" to v.available, "failReason" to v.failReason, "files" to v.files)
    private fun agentMap(v: com.lhstack.tools.db.service.AgentRecord) = mapOf(
        "id" to v.id, "name" to v.name, "description" to v.description, "enabled" to v.enabled,
        "providerId" to v.providerId, "modelId" to v.modelId, "promptId" to v.promptId,
        "extraPrompt" to v.extraPrompt, "outputMode" to v.outputMode, "maxRuntimeSecs" to v.maxRuntimeSecs,
        "tags" to v.tags, "includeHistory" to v.runtimeParams.includeHistory,
        "maxHistoryMessages" to v.runtimeParams.maxHistoryMessages,
        "toolCallRetentionRounds" to v.runtimeParams.toolCallRetentionRounds,
        "tools" to v.extConfig.tools.enabled, "toolsIncludeNew" to v.extConfig.tools.includeNew,
        "skills" to v.extConfig.skills.enabled, "skillsIncludeNew" to v.extConfig.skills.includeNew,
        "pluginFunctions" to v.extConfig.pluginFunctions.enabled, "pluginFunctionsIncludeNew" to v.extConfig.pluginFunctions.includeNew,
        "skillPromptEnabled" to v.extConfig.skillPromptEnabled,
        "viewResources" to mapOf(
            "image" to viewResourceMap(v.extConfig.viewResources.image), "audio" to viewResourceMap(v.extConfig.viewResources.audio),
            "video" to viewResourceMap(v.extConfig.viewResources.video), "file" to viewResourceMap(v.extConfig.viewResources.file),
        ),
        "persona" to mapOf(
            "memory" to v.extConfig.persona.memory, "memoryMaxChars" to v.extConfig.persona.memoryMaxChars,
            "behaviorHabits" to v.extConfig.persona.behaviorHabits, "behaviorHabitsMaxChars" to v.extConfig.persona.behaviorHabitsMaxChars,
            "soul" to v.extConfig.persona.soul, "soulMaxChars" to v.extConfig.persona.soulMaxChars,
            "profile" to v.extConfig.persona.profile, "profileMaxChars" to v.extConfig.persona.profileMaxChars,
            "guardrails" to v.extConfig.persona.guardrails, "guardrailsMaxChars" to v.extConfig.persona.guardrailsMaxChars,
        ),
        "distill" to mapOf("enabled" to v.distillConfig.enabled, "agentId" to v.distillConfig.agentId, "minMessages" to v.distillConfig.minMessages, "messageTypes" to v.distillConfig.messageTypes, "extraPrompt" to v.distillConfig.extraPrompt),
        "updatedAt" to v.updatedAt,
    )
    private fun viewResourceMap(v: AgentViewResourceRef) = mapOf("enabled" to v.enabled, "agentId" to v.agentId)
    private fun saveAgent(project: Project, p: JsonObject) {
        val existing = p.longOrNull("id")?.let(AgentService::agentById)
        val base = existing ?: com.lhstack.tools.db.service.AgentRecord(
            null, "", null, true, null, null, null, null,
            AgentRuntimeConfig(), com.lhstack.tools.db.config.AgentCapabilityConfig(),
            com.lhstack.tools.db.config.AgentDistillConfig(), "text", null, emptyList(), null, null,
        )
        val allTools = BuiltinTools.BUILTIN_TOOLS.map { it.name }
        val allSkills = ResourceConfigService.listSkills().map { it.name }
        val enabledTools = stringList(p, "tools")
        val enabledSkills = stringList(p, "skills")
        val allPluginFunctions = PluginFunctionToolSupport.entries(project).map { it.key }
        val enabledPluginFunctions = stringList(p, "pluginFunctions")
        AgentService.upsertAgent(base.copy(
            name = string(p, "name"), description = p.stringOrNull("description"),
            enabled = p.boolean("enabled", true), providerId = p.longOrNull("providerId"),
            modelId = p.longOrNull("modelId"), promptId = p.longOrNull("promptId"),
            extraPrompt = p.stringOrNull("extraPrompt"), outputMode = string(p, "outputMode").ifBlank { "text" },
            maxRuntimeSecs = p.longOrNull("maxRuntimeSecs"), tags = stringList(p, "tags"),
            runtimeParams = AgentRuntimeConfig(
                includeHistory = p.boolean("includeHistory", false),
                maxHistoryMessages = p.optionalInt("maxHistoryMessages"),
                toolCallRetentionRounds = p.optionalInt("toolCallRetentionRounds"),
            ),
            extConfig = base.extConfig.copy(
                tools = AgentEnabledItemsConfig(enabledTools, p.boolean("toolsIncludeNew", false), allTools.filterNot(enabledTools::contains)),
                skills = AgentEnabledItemsConfig(enabledSkills, p.boolean("skillsIncludeNew", false), allSkills.filterNot(enabledSkills::contains)),
                pluginFunctions = AgentEnabledItemsConfig(enabledPluginFunctions, p.boolean("pluginFunctionsIncludeNew", false), allPluginFunctions.filterNot(enabledPluginFunctions::contains)),
                skillPromptEnabled = p.boolean("skillPromptEnabled", false),
                viewResources = parseViewResources(p.getAsJsonObject("viewResources")),
                persona = parsePersona(p.getAsJsonObject("persona")),
            ),
            distillConfig = parseDistill(p.getAsJsonObject("distill"), base.distillConfig.lastDistilledModelLogId),
        ))
    }
    private fun parseViewResources(p: JsonObject?): AgentViewResourcesConfig = AgentViewResourcesConfig(
        image = parseViewResource(p?.getAsJsonObject("image")), audio = parseViewResource(p?.getAsJsonObject("audio")),
        video = parseViewResource(p?.getAsJsonObject("video")), file = parseViewResource(p?.getAsJsonObject("file")),
    )
    private fun parseViewResource(p: JsonObject?): AgentViewResourceRef = AgentViewResourceRef(p?.boolean("enabled", false) ?: false, p?.longOrNull("agentId"))
    private fun parsePersona(p: JsonObject?): AgentPersonaConfig = AgentPersonaConfig(
        memory = p?.let { string(it, "memory") }.orEmpty(), memoryMaxChars = p?.get("memoryMaxChars")?.asInt ?: 512,
        behaviorHabits = p?.let { string(it, "behaviorHabits") }.orEmpty(), behaviorHabitsMaxChars = p?.get("behaviorHabitsMaxChars")?.asInt ?: 512,
        soul = p?.let { string(it, "soul") }.orEmpty(), soulMaxChars = p?.get("soulMaxChars")?.asInt ?: 512,
        profile = p?.let { string(it, "profile") }.orEmpty(), profileMaxChars = p?.get("profileMaxChars")?.asInt ?: 512,
        guardrails = p?.let { string(it, "guardrails") }.orEmpty(), guardrailsMaxChars = p?.get("guardrailsMaxChars")?.asInt ?: 512,
    )
    private fun parseDistill(p: JsonObject?, lastId: Long?): AgentDistillConfig = AgentDistillConfig(
        enabled = p?.boolean("enabled", false) ?: false, agentId = p?.longOrNull("agentId"),
        minMessages = p?.get("minMessages")?.asInt ?: 10, messageTypes = p?.let { stringList(it, "messageTypes") } ?: emptyList(),
        extraPrompt = p?.stringOrNull("extraPrompt"), lastDistilledModelLogId = lastId,
    )

    private fun stringList(p: JsonObject, key: String): List<String> = p.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()

    private fun provider(p: JsonObject) = ProviderEntity().apply {
        id = p.longOrNull("id"); name = string(p, "name"); kind = string(p, "kind").ifBlank { "openai" }
        apiKey = p.stringOrNull("apiKey"); baseUrl = p.stringOrNull("baseUrl"); api = p.stringOrNull("api")
        anthropicVersion = p.stringOrNull("anthropicVersion"); providerConfig = p.get("providerConfig")?.let { if (it.isJsonObject) it.toString() else it.asString } ?: "{}"
        enabled = if (p.boolean("enabled", true)) 1 else 0
    }
    private fun model(p: JsonObject) = ModelEntity().apply {
        id = p.longOrNull("id"); providerId = long(p, "providerId"); alias = string(p, "alias"); modelId = string(p, "modelId")
        displayName = p.stringOrNull("displayName"); api = p.stringOrNull("api"); contextWindow = p.longOrNull("contextWindow")
        modalities = p.get("modalities")?.let { if (it.isJsonArray) it.toString() else it.asString } ?: "[]"
        modelParams = p.stringOrNull("modelParams"); executionParams = p.stringOrNull("executionParams"); additionalParams = p.stringOrNull("additionalParams")
        enabled = if (p.boolean("enabled", true)) 1 else 0
    }
    private fun parseJsonObject(value: String?): JsonObject = runCatching {
        com.google.gson.JsonParser.parseString(value?.takeIf { it.isNotBlank() } ?: "{}").asJsonObject
    }.getOrElse { JsonObject() }
    private fun prompt(p: JsonObject) = PromptTemplateEntity().apply { id = p.longOrNull("id"); name = string(p, "name"); preamble = string(p, "preamble") }
    private fun string(p: JsonObject, key: String) = p.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.stringOrNull(key: String) = string(this, key).takeIf { it.isNotBlank() }
    private fun long(p: JsonObject, key: String) = requireNotNull(p.longOrNull(key)) { "$key 不能为空" }
    private fun JsonObject.optionalInt(key: String): Int? =
        get(key)?.takeUnless { it.isJsonNull || (it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isBlank()) }?.asInt
    private fun JsonObject.longOrNull(key: String) = get(key)?.takeUnless { it.isJsonNull || it.asString.isBlank() }?.asLong
    private fun int(p: JsonObject, key: String, default: Int) = p.get(key)?.asInt ?: default
    private fun JsonObject.boolean(key: String, default: Boolean) = get(key)?.asBoolean ?: default
}
