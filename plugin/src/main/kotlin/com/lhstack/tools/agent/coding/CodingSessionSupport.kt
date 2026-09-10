package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.params.ModelResolver
import com.lhstack.tools.const.Const
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.ChatSessionType
import com.lhstack.tools.db.service.CodingEnvironmentService
import java.io.File

/**
 * 编码会话 config 的读写与校验。对齐 awake-claw create_coding_session：
 * 会话自己持有 provider/model/snapshot；Agent 只提供能力包身份。
 * 缺字段、JSON 非法、模型无法解析时直接失败，不补默认值。
 */
object CodingSessionSupport {
    const val MESSAGE_SESSION_TYPE: String = "coding"
    const val VISIBILITY_PROJECT: String = "project"
    const val VISIBILITY_GLOBAL: String = "global"

    data class CreateInput(
        val title: String,
        val visibility: ChatSessionType,
        val workspacePath: String,
        val codingEnvironmentId: Long,
        val agentId: Long? = null,
        val providerId: Long? = null,
        val modelId: Long? = null,
        val promptId: Long? = null,
    )

    data class ParsedConfig(
        val cwd: String,
        val visibility: ChatSessionType,
        val projectPath: String?,
        val codingEnvironmentId: Long,
        val agentId: Long,
        val providerId: Long,
        val modelId: Long,
        val modelSnapshot: JsonObject,
        val promptId: Long?,
        val reasoningLevel: String?,
        val reasoningConfig: JsonObject?,
        val tokenUsage: JsonObject,
        val maxHistoryRounds: Int?,
    )

    fun canonicalizePath(path: String): String {
        require(path.isNotBlank()) { "路径不能为空" }
        return File(path).canonicalFile.invariantSeparatorsPath
    }

    fun globalWorkspacePath(): String {
        val dir = File(Const.JTOOLS_PLUGIN_HOME, "global")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建全局会话工作区 `${dir.path}`")
        }
        return normalizeWorkspacePath(dir.path)
    }

    fun normalizeWorkspacePath(path: String): String {
        val canonical = canonicalizePath(path)
        require(File(canonical).isDirectory) { "工作区路径 `$path` 不是目录" }
        return canonical
    }

    fun buildConfig(input: CreateInput): JsonObject {
        val title = input.title.trim()
        require(title.isNotBlank()) { "会话名称不能为空" }
        require(title.length <= 80) { "会话名称不能超过 80 个字符" }
        val cwd = normalizeWorkspacePath(input.workspacePath)
        val environment = CodingEnvironmentService.requireEnabled(input.codingEnvironmentId)
        val agent = input.agentId?.let {
            AgentService.agentById(it) ?: throw IllegalArgumentException("Agent `$it` 不存在")
        }
        if (agent != null) require(agent.enabled) { "Agent `${agent.name}` 已停用" }
        val providerId = input.providerId ?: agent?.providerId
            ?: throw IllegalArgumentException("创建会话必须指定供应商和模型")
        val modelId = input.modelId ?: agent?.modelId
            ?: throw IllegalArgumentException("创建会话必须指定供应商和模型")
        val pair = CatalogService.modelPair(providerId, modelId)
            ?: throw IllegalArgumentException("供应商 `$providerId` 与模型 `$modelId` 不存在或不匹配")
        val snapshot = ModelResolver.modelSnapshot(ModelResolver.resolveFromStore(pair.first, pair.second))
        val promptId = input.promptId ?: agent?.promptId
        if (promptId != null) {
            CatalogService.promptTemplateById(promptId)
                ?: throw IllegalArgumentException("提示词模板 `$promptId` 不存在")
        }
        return JsonObject().apply {
            addProperty("cwd", cwd)
            addProperty("visibility", input.visibility.value)
            if (input.visibility == ChatSessionType.PROJECT) addProperty("project_path", cwd)
            else add("project_path", com.google.gson.JsonNull.INSTANCE)
            addProperty("coding_environment_id", environment.id)
            if (agent?.id != null) addProperty("agent_id", agent.id)
            else add("agent_id", com.google.gson.JsonNull.INSTANCE)
            addProperty("provider_id", providerId)
            addProperty("model_id", modelId)
            add("model_snapshot", snapshot)
            if (promptId != null) addProperty("prompt_id", promptId)
            else add("prompt_id", com.google.gson.JsonNull.INSTANCE)
            add("reasoning_level", com.google.gson.JsonNull.INSTANCE)
            add("reasoning_config", com.google.gson.JsonNull.INSTANCE)
            add("token_usage", JsonObject())
            add("max_history_rounds", com.google.gson.JsonNull.INSTANCE)
        }
    }

    fun parseConfig(configText: String, sessionId: Long? = null): ParsedConfig {
        val root = try {
            JsonParser.parseString(configText).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalStateException("会话配置不是 JSON object")
        } catch (error: Throwable) {
            val label = sessionId?.let { " `$it`" } ?: ""
            throw IllegalStateException("会话$label 配置无效", error)
        }
        val cwd = requiredString(root, "cwd")
        val visibility = ChatSessionType.from(requiredString(root, "visibility"))
        val projectPath = root.get("project_path")?.takeUnless { it.isJsonNull }?.asString
        if (visibility == ChatSessionType.PROJECT) {
            require(!projectPath.isNullOrBlank()) { "项目会话缺少 project_path" }
        }
        val snapshot = root.get("model_snapshot")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("会话缺少 model_snapshot")
        val tokenUsage = root.get("token_usage")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val reasoningConfig = root.get("reasoning_config")?.takeIf { it.isJsonObject }?.asJsonObject
        return ParsedConfig(
            cwd = cwd,
            visibility = visibility,
            projectPath = projectPath,
            codingEnvironmentId = requiredLong(root, "coding_environment_id"),
            agentId = root.get("agent_id")?.takeUnless { it.isJsonNull }?.asLong ?: 0L,
            providerId = requiredLong(root, "provider_id"),
            modelId = requiredLong(root, "model_id"),
            modelSnapshot = snapshot,
            promptId = optionalLong(root, "prompt_id"),
            reasoningLevel = root.get("reasoning_level")?.takeUnless { it.isJsonNull }?.asString,
            reasoningConfig = reasoningConfig,
            tokenUsage = tokenUsage,
            maxHistoryRounds = optionalLong(root, "max_history_rounds")?.toInt(),
        )
    }

    fun replaceAgent(config: JsonObject, agentId: Long): JsonObject {
        require(agentId > 0) { "Agent id 无效" }
        val copy = config.deepCopy()
        copy.addProperty("agent_id", agentId)
        return copy
    }

    fun replaceCodingEnvironment(config: JsonObject, environmentId: Long): JsonObject {
        CodingEnvironmentService.requireEnabled(environmentId)
        val copy = config.deepCopy()
        copy.addProperty("coding_environment_id", environmentId)
        return copy
    }

    data class ModelSettingsInput(
        val providerId: Long,
        val modelId: Long,
        val promptId: Long?,
        val modelSnapshot: JsonObject?,
        val reasoningLevel: String?,
        val reasoningConfig: JsonObject?,
        val maxHistoryRounds: Int?,
    )

    fun applyModelSettings(config: JsonObject, input: ModelSettingsInput): JsonObject {
        require(input.providerId > 0) { "供应商 id 无效" }
        require(input.modelId > 0) { "模型 id 无效" }
        val pair = CatalogService.modelPair(input.providerId, input.modelId)
            ?: throw IllegalArgumentException("模型 `${input.modelId}` 不属于供应商 `${input.providerId}`")
        val resolved = ModelResolver.resolveFromStore(pair.first, pair.second)
        val snapshot = if (input.modelSnapshot != null) {
            validateSnapshot(pair.first, input.modelSnapshot)
        } else {
            ModelResolver.modelSnapshot(resolved)
        }
        require(snapshot.get("provider_id")?.asLong == input.providerId) { "model_snapshot.provider_id 必须与会话供应商一致" }
        require(snapshot.get("id")?.asLong == input.modelId) { "model_snapshot.id 必须与会话模型一致" }
        if (input.promptId != null) {
            CatalogService.promptTemplateById(input.promptId)
                ?: throw IllegalArgumentException("提示词 `${input.promptId}` 不存在")
        }
        val copy = config.deepCopy()
        copy.addProperty("provider_id", input.providerId)
        copy.addProperty("model_id", input.modelId)
        if (input.promptId == null) copy.add("prompt_id", com.google.gson.JsonNull.INSTANCE)
        else copy.addProperty("prompt_id", input.promptId)
        copy.add("model_snapshot", applyReasoningOverride(snapshot, input.reasoningLevel, input.reasoningConfig, pair.first.kind))
        if (input.reasoningLevel.isNullOrBlank()) copy.add("reasoning_level", com.google.gson.JsonNull.INSTANCE)
        else copy.addProperty("reasoning_level", input.reasoningLevel)
        if (input.reasoningConfig == null) copy.add("reasoning_config", com.google.gson.JsonNull.INSTANCE)
        else copy.add("reasoning_config", input.reasoningConfig)
        if (input.maxHistoryRounds == null) {
            snapshot.get("max_history_messages")?.takeUnless { it.isJsonNull }?.asLong?.toInt()?.let {
                copy.addProperty("max_history_rounds", it)
            }
        } else {
            copy.addProperty("max_history_rounds", input.maxHistoryRounds)
        }
        return copy
    }


    fun applyReasoningOverride(snapshot: JsonObject, reasoningLevel: String?, reasoningConfig: JsonObject?, providerKind: String): JsonObject {
        val copy = snapshot.deepCopy()
        val params = copy.get("model_params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val level = reasoningLevel?.trim()?.takeIf { it.isNotEmpty() }
        if (level != null) {
            params.addProperty("reasoning_effort", level)
            val reasoning = params.get("reasoning")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            reasoning.addProperty("effort", level)
            params.add("reasoning", reasoning)
            val output = params.get("output_config")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            output.addProperty("effort", level)
            params.add("output_config", output)
        }
        if (providerKind.equals("anthropic", ignoreCase = true) && reasoningConfig != null && reasoningConfig.isJsonObject) {
            params.add("thinking", reasoningConfig.deepCopy())
        }
        copy.add("model_params", params)
        return copy
    }

    fun validateSnapshot(provider: com.lhstack.tools.db.entity.ProviderEntity, snapshot: JsonObject): JsonObject {
        require(snapshot.isJsonObject) { "model_snapshot 必须是 JSON object" }
        val additional = snapshot.get("additional_params")
        if (additional != null && !additional.isJsonNull && !additional.isJsonObject) {
            throw IllegalArgumentException("additional_params 必须是 JSON object")
        }
        ModelResolver.resolveFromSnapshot(provider, snapshot)
        return snapshot
    }

    private fun requiredString(root: JsonObject, key: String): String {
        val value = root.get(key)?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        require(value.isNotEmpty()) { "会话配置缺少 `$key`" }
        return value
    }

    private fun requiredLong(root: JsonObject, key: String): Long {
        val element = root.get(key) ?: throw IllegalStateException("会话配置缺少 `$key`")
        require(!element.isJsonNull) { "会话配置缺少 `$key`" }
        return element.asLong
    }

    private fun optionalLong(root: JsonObject, key: String): Long? {
        val element = root.get(key) ?: return null
        if (element.isJsonNull) return null
        return element.asLong
    }
}
