package com.lhstack.tools.db.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.lhstack.tools.agent.model.tools.RuntimeTools
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.CodingEnvironmentEntity
import com.lhstack.tools.db.mapper.CodingEnvironmentMapper

data class CodingEnvironmentRecord(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val config: CodingEnvironmentConfig,
    val createdAt: String?,
    val updatedAt: String?,
)

data class CodingEnvironmentConfig(
    @SerializedName("system_prompt") val systemPrompt: String = "",
    @SerializedName("contexts") val contexts: Map<String, CodingContextConfig> = emptyMap(),
    @SerializedName("tools") val tools: Map<String, Boolean> = emptyMap(),
    @SerializedName("view_resources") val viewResources: CodingViewResourcesConfig = CodingViewResourcesConfig(),
    @SerializedName("resources") val resources: CodingResourcesConfig = CodingResourcesConfig(),
    @SerializedName("plugin_functions") val pluginFunctions: Map<String, Boolean> = emptyMap(),
    @SerializedName("plugin_functions_include_new") val pluginFunctionsIncludeNew: Boolean = false,
    @SerializedName("skill_prompt_enabled") val skillPromptEnabled: Boolean = false,
    @SerializedName("compaction") val compaction: CodingCompactionConfig = CodingCompactionConfig(),
)

data class CodingContextConfig(
    @SerializedName("content") val content: String = "",
    @SerializedName("max_chars") val maxChars: Int = 2000,
)

data class CodingResourcesConfig(
    @SerializedName("mcp") val mcp: Map<String, Boolean> = emptyMap(),
    @SerializedName("mcp_include_new") val mcpIncludeNew: Boolean = true,
    @SerializedName("skills") val skills: Map<String, Boolean> = emptyMap(),
    @SerializedName("skills_include_new") val skillsIncludeNew: Boolean = true,
)

data class CodingCompactionConfig(
    @SerializedName("agent_id") val agentId: Long? = null,
)

data class CodingViewResourcesConfig(
    @SerializedName("image") val image: CodingViewResourceRef = CodingViewResourceRef(),
    @SerializedName("audio") val audio: CodingViewResourceRef = CodingViewResourceRef(),
    @SerializedName("video") val video: CodingViewResourceRef = CodingViewResourceRef(),
    @SerializedName("file") val file: CodingViewResourceRef = CodingViewResourceRef(),
)

data class CodingViewResourceRef(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("agent_id") val agentId: Long? = null,
)

object CodingEnvironmentService {
    private val gson = Gson()

    val contextKinds = listOf("user_profile", "coding_style", "workflow", "interaction")
    val toolNames: List<String> = RuntimeTools.REGISTERED_BUILTIN_TOOL_NAMES.sorted()

    fun listEnabled(): List<CodingEnvironmentRecord> = AgentDatabase.execute { session ->
        session.getMapper(CodingEnvironmentMapper::class.java)
            .selectList(QueryWrapper<CodingEnvironmentEntity>().eq("enabled", 1).orderByAsc("name").orderByAsc("id"))
            .map(::toRecord)
    }

    fun listAll(): List<CodingEnvironmentRecord> = AgentDatabase.execute { session ->
        session.getMapper(CodingEnvironmentMapper::class.java)
            .selectList(QueryWrapper<CodingEnvironmentEntity>().orderByAsc("name").orderByAsc("id"))
            .map(::toRecord)
    }

    fun environmentById(id: Long): CodingEnvironmentRecord? = AgentDatabase.execute { session ->
        session.getMapper(CodingEnvironmentMapper::class.java).selectById(id)?.let(::toRecord)
    }

    fun requireEnabled(id: Long): CodingEnvironmentRecord {
        val record = environmentById(id) ?: throw IllegalArgumentException("编码环境 `$id` 不存在")
        require(record.enabled) { "编码环境 `${record.name}` 已停用" }
        return record
    }

    fun create(name: String): CodingEnvironmentRecord {
        val normalized = name.trim()
        require(normalized.isNotBlank()) { "环境名称不能为空" }
        return AgentDatabase.execute { session ->
            val mapper = session.getMapper(CodingEnvironmentMapper::class.java)
            val exists = mapper.selectCount(QueryWrapper<CodingEnvironmentEntity>().eq("name", normalized))
            require(exists == 0L) { "环境名称 `$normalized` 已存在" }
            val entity = CodingEnvironmentEntity().apply {
                this.name = normalized
                enabled = 1
                config = gson.toJson(defaultConfig())
            }
            mapper.insert(entity)
            toRecord(entity)
        }
    }

    fun rename(id: Long, name: String) {
        val normalized = name.trim()
        require(normalized.isNotBlank()) { "环境名称不能为空" }
        AgentDatabase.execute { session ->
            val mapper = session.getMapper(CodingEnvironmentMapper::class.java)
            val entity = mapper.selectById(id) ?: throw IllegalArgumentException("编码环境 `$id` 不存在")
            val clash = mapper.selectCount(
                QueryWrapper<CodingEnvironmentEntity>().eq("name", normalized).ne("id", id),
            )
            require(clash == 0L) { "环境名称 `$normalized` 已存在" }
            entity.name = normalized
            mapper.updateById(entity)
            Unit
        }
    }

    fun updateConfig(id: Long, config: JsonObject): CodingEnvironmentRecord = AgentDatabase.execute { session ->
        val mapper = session.getMapper(CodingEnvironmentMapper::class.java)
        val entity = mapper.selectById(id) ?: throw IllegalArgumentException("编码环境 `$id` 不存在")
        val parsed = parseConfig(config.toString())
        validateConfig(parsed)
        entity.config = gson.toJson(parsed)
        mapper.updateById(entity)
        toRecord(entity)
    }

    fun delete(id: Long) {
        AgentDatabase.execute { session ->
            val changed = session.getMapper(CodingEnvironmentMapper::class.java).deleteById(id)
            require(changed > 0) { "编码环境 `$id` 不存在" }
            Unit
        }
    }

    fun defaultConfig(): CodingEnvironmentConfig = CodingEnvironmentConfig(
        systemPrompt = defaultSystemPrompt(),
        contexts = contextKinds.associateWith { kind ->
            CodingContextConfig(content = "", maxChars = if (kind == "user_profile" || kind == "interaction") 2000 else 4000)
        },
        tools = toolNames.associateWith { true },
        viewResources = CodingViewResourcesConfig(),
        resources = CodingResourcesConfig(),
        pluginFunctions = emptyMap(),
        pluginFunctionsIncludeNew = false,
        skillPromptEnabled = false,
        compaction = CodingCompactionConfig(),
    )

    fun enabledSkillNames(config: CodingEnvironmentConfig, available: Set<String>): Set<String> {
        val explicit = config.resources.skills
        return available.filter { name ->
            explicit[name] ?: config.resources.skillsIncludeNew
        }.toSet()
    }

    fun enabledTools(config: CodingEnvironmentConfig): Set<String> =
        toolNames.filter { config.tools[it] ?: true }.toSet()

    fun enabledPluginFunctionKeys(config: CodingEnvironmentConfig, knownKeys: Set<String>): Set<String> =
        knownKeys.filter { key -> pluginFunctionEnabled(config, key) }.toSet()

    fun pluginFunctionEnabled(config: CodingEnvironmentConfig, key: String, functionName: String? = null): Boolean {
        val stored = config.pluginFunctions
        stored[key]?.let { return it }
        if (!functionName.isNullOrBlank()) {
            stored[functionName]?.let { return it }
            stored.entries.firstOrNull { it.key.endsWith(":$functionName") }?.value?.let { return it }
        }
        return config.pluginFunctionsIncludeNew
    }

    fun compactionAgentId(config: CodingEnvironmentConfig): Long? =
        config.compaction.agentId?.takeIf { it > 0 }

    private fun validateConfig(config: CodingEnvironmentConfig) {
        config.contexts.forEach { (key, value) ->
            require(key in contextKinds) { "不支持的上下文类型 `$key`" }
            require(value.maxChars > 0) { "`$key` 的最大长度必须大于 0" }
            require(value.content.length <= value.maxChars) { "`$key` 超过最大长度 ${value.maxChars}" }
        }
        config.tools.keys.forEach { require(it in toolNames) { "不支持的工具 `$it`" } }
        config.compaction.agentId?.let { id ->
            if (id > 0) {
                AgentService.agentById(id) ?: throw IllegalArgumentException("压缩 Agent `$id` 不存在")
            }
        }
        listOf(config.viewResources.image, config.viewResources.audio, config.viewResources.video, config.viewResources.file).forEach { ref ->
            if (ref.enabled) {
                val agentId = ref.agentId ?: throw IllegalArgumentException("启用的多模态工具必须指定 Agent")
                AgentService.agentById(agentId) ?: throw IllegalArgumentException("多模态 Agent `$agentId` 不存在")
            }
        }
    }

    private fun toRecord(entity: CodingEnvironmentEntity) = CodingEnvironmentRecord(
        id = entity.id ?: 0,
        name = entity.name,
        enabled = entity.enabled == 1,
        config = parseConfig(entity.config),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    internal fun parseConfig(raw: String): CodingEnvironmentConfig {
        val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?: throw IllegalArgumentException("环境配置必须是 JSON object")
        val config = gson.fromJson(parsed, CodingEnvironmentConfig::class.java)
        val defaults = defaultConfig()
        return defaults.copy(
            systemPrompt = config.systemPrompt,
            contexts = defaults.contexts.mapValues { (key, fallback) -> config.contexts[key] ?: fallback },
            tools = defaults.tools.mapValues { (key, fallback) -> config.tools[key] ?: fallback },
            viewResources = config.viewResources,
            resources = config.resources,
            pluginFunctions = booleanMap(parsed.asJsonObject.get("plugin_functions")),
            pluginFunctionsIncludeNew = parsed.asJsonObject.get("plugin_functions_include_new")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            skillPromptEnabled = config.skillPromptEnabled,
            compaction = config.compaction,
        )
    }

    private fun booleanMap(value: com.google.gson.JsonElement?): Map<String, Boolean> {
        val obj = value?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        return obj.entrySet().mapNotNull { entry ->
            val flag = entry.value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return@mapNotNull null
            val enabled = when {
                flag.isBoolean -> flag.asBoolean
                flag.isNumber -> flag.asInt != 0
                flag.isString -> flag.asString.equals("true", ignoreCase = true)
                else -> return@mapNotNull null
            }
            entry.key to enabled
        }.toMap()
    }

    private fun defaultSystemPrompt(): String = """
你是当前项目的编码助手。工作区是当前 IDE 项目目录。

约束：
1. 只修改完成任务所需的最小文件。
2. 先读再改，禁止凭空覆盖未知文件。
3. 工具失败时暴露真实错误，不要伪造成功。
    """.trimIndent()
}
