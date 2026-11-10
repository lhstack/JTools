package com.lhstack.tools.agent.resources

import com.google.gson.*
import com.intellij.openapi.project.Project
import com.lhstack.tools.db.config.*
import com.lhstack.tools.db.entity.McpServerEntity
import com.lhstack.tools.db.service.*
import java.io.File

/** Shared by UI, CLI and runtime. Project identities never masquerade as database IDs. */
class ScopedResources(private val projectRoot: File?) {
    private fun requireScope(scope: String) {
        require(scope in setOf("global", "project")) { "scope 无效" }
    }

    fun scope(args: JsonObject): String =
        args.get("scope")?.let {
            require(it.isJsonPrimitive && it.asString in setOf("global", "project")) {
                "scope 必须为 global 或 project"
            }
            it.asString
        } ?: throw IllegalArgumentException("必须指定 scope: global 或 project")

    private fun project() = ProjectResourceStore(projectRoot ?: error("当前调用未绑定 IDE 项目"))

    fun skills(scope: String): ResourceConfigService =
        when (scope) {
            "global" -> ResourceConfigService.global
            "project" -> project().skills()
            else -> error("scope 无效")
        }

    fun mcps(scope: String): List<JsonObject> =
        when (scope) {
            "global" ->
                McpService.list().map {
                    McpService.map(it).apply {
                        addProperty("scope", scope)
                        addProperty("storage", "sqlite:mcp_servers")
                        addProperty("ref", "global:mcp:${it.id}")
                    }
                }
            "project" ->
                project().read("mcpServer.json", "mcpServers").entrySet().map { (key, value) ->
                    require(!value.asJsonObject.has("enabled") && !value.asJsonObject.has("id")) {
                        "项目 MCP 不接受 enabled 或数据库 id"
                    }
                    val server = parseMcp(value.asJsonObject, key)
                    McpService.map(server).apply {
                        remove("enabled")
                        addProperty("id", key)
                        addProperty("scope", scope)
                        addProperty("ref", "project:mcp:$key")
                        addProperty("storage", File(projectRoot, ".jtools/mcpServer.json").path)
                    }
                }
            else -> error("scope 无效")
        }

    fun mcp(scope: String, id: String): McpServerEntity {
        requireScope(scope)
        return if (scope == "global") {
            McpService.get(id.toLong()) ?: error("MCP $id 不存在")
        } else {
            val value =
                project().read("mcpServer.json", "mcpServers").get(id) ?: error("项目 MCP $id 不存在")
            require(!value.asJsonObject.has("enabled") && !value.asJsonObject.has("id")) {
                "项目 MCP 不接受 enabled 或数据库 id"
            }
            parseMcp(value.asJsonObject, id)
        }
    }

    fun saveMcp(scope: String, input: JsonObject, create: Boolean): JsonObject {
        requireScope(scope)
        val id = input.get("id")?.takeUnless { it.isJsonNull }?.asString
        if (scope == "global" && create) require(id == null) { "创建全局 MCP 不能指定数据库 ID" }
        val base = if (create) JsonObject() else McpService.map(mcp(scope, id ?: error("缺少 id")))
        input
            .entrySet()
            .filter { it.key !in setOf("scope", "id", "ref") }
            .forEach { (k, v) -> base.add(k, v) }
        val key =
            if (scope == "project") id ?: input.get("name")?.asString ?: error("缺少项目 MCP key")
            else ""
        val server = parseMcp(base, key)
        if (scope == "project")
            require(key.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*"))) { "项目 MCP key 无效" }
        if (scope == "global") {
            server.id = if (create) null else id!!.toLong()
            return McpService.map(McpService.save(server)).apply { addProperty("scope", scope) }
        }
        require(!input.has("enabled")) { "项目 MCP 默认启用，不接受 enabled" }
        project().update("mcpServer.json", "mcpServers") { entries ->
            require(entries.has(key) != create) { if (create) "项目 MCP key 已存在" else "项目 MCP 不存在" }
            val value = McpService.map(server)
            value.remove("enabled")
            value.remove("id")
            entries.add(key, value)
        }
        return mcps(scope).first { it.get("id").asString == key }
    }

    fun deleteMcp(scope: String, id: String) {
        requireScope(scope)
        if (scope == "global") McpService.delete(id.toLong())
        else
            project().update("mcpServer.json", "mcpServers") {
                require(it.remove(id) != null) { "项目 MCP 不存在" }
            }
    }

    private fun parseMcp(value: JsonObject, key: String): McpServerEntity {
        require(
            value.keySet().all {
                it in
                    setOf(
                        "id",
                        "name",
                        "enabled",
                        "transport",
                        "command",
                        "args",
                        "env",
                        "url",
                        "headers",
                    )
            }
        ) {
            "MCP 包含未知字段"
        }
        val server =
            McpServerEntity().apply {
                name = value.get("name")?.asString ?: key
                enabled = if (value.get("enabled")?.asBoolean == false) 0 else 1
                transport = value.get("transport")?.asString ?: error("MCP 缺少 transport")
                command = value.get("command")?.takeUnless { it.isJsonNull }?.asString
                args = value.get("args")?.toString() ?: "[]"
                env = value.get("env")?.toString() ?: "{}"
                url = value.get("url")?.takeUnless { it.isJsonNull }?.asString
                headers = value.get("headers")?.toString() ?: "{}"
            }
        require(server.name.isNotBlank()) { "MCP name 不能为空" }
        require(server.transport in setOf("stdio", "sse", "streamable_http")) { "未知 MCP transport" }
        McpService.stringList(server.args)
        McpService.stringMap(server.env, "env")
        McpService.stringMap(server.headers, "headers")
        if (server.transport == "stdio")
            require(!server.command.isNullOrBlank()) { "stdio MCP 缺少 command" }
        else
            require(server.url?.let { java.net.URI(it).scheme in setOf("http", "https") } == true) {
                "MCP url 无效"
            }
        return server
    }

    fun projectAgents(): Map<String, ProjectSubagent> =
        project().read("subAgents.json", "subAgents").entrySet().associate { (key, value) ->
            key to ProjectSubagent.parse(value.asJsonObject, key)
        }

    fun saveProjectAgent(id: String, value: JsonObject, create: Boolean) {
        require(id.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*"))) { "项目 SubAgent key 无效" }
        ProjectSubagent.parse(value, id)
        project().update("subAgents.json", "subAgents") {
            require(it.has(id) != create) { "项目 SubAgent 已存在或不存在" }
            it.add(id, value.deepCopy())
        }
    }

    fun deleteProjectAgent(id: String) =
        project().update("subAgents.json", "subAgents") {
            require(it.remove(id) != null) { "项目 SubAgent 不存在" }
        }

    companion object {
        fun forProject(project: Project?) = ScopedResources(project?.basePath?.let(::File))
    }
}

data class ProjectSubagent(
    val key: String,
    val agent: AgentRecord,
    val accessMode: String,
    val bashEnabled: Boolean,
) {
    companion object {
        private fun resolveCatalogModel(
            reference: JsonElement
        ): com.lhstack.tools.db.entity.ModelEntity {
            require(reference.isJsonObject) { "SubAgent model 必须包含 provider_id 和 model_id" }
            val ref = reference.asJsonObject
            require(ref.keySet() == setOf("provider_id", "model_id")) {
                "model 仅允许 provider_id/model_id"
            }
            val providerId = ref.get("provider_id").asLong
            val modelId = ref.get("model_id").asLong
            val pair =
                CatalogService.modelPair(providerId, modelId) ?: error("SubAgent 模型与供应商不存在或不匹配")
            val model = pair.second
            require(
                CatalogService.providerById(model.providerId)?.enabled?.let { it != 0 } == true
            ) {
                "SubAgent 供应商不存在或已禁用"
            }
            return model
        }

        fun parse(
            value: JsonObject,
            key: String,
            resolveModel: (JsonElement) -> com.lhstack.tools.db.entity.ModelEntity =
                ::resolveCatalogModel,
        ): ProjectSubagent {
            require(
                value.keySet().all {
                    it in
                        setOf(
                            "name",
                            "description",
                            "model",
                            "view_resources",
                            "persona",
                            "system_prompt",
                            "tools",
                            "skills",
                            "runtime_params",
                            "max_runtime_secs",
                            "access_mode",
                            "bash_enabled",
                        )
                }
            ) {
                "SubAgent $key 包含未知字段"
            }
            fun text(name: String) =
                value
                    .get(name)
                    ?.takeUnless { it.isJsonNull }
                    ?.let {
                        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) {
                            "$name 必须是字符串"
                        }
                        it.asString
                    }
            val name = text("name") ?: key
            require(name.isNotBlank()) { "SubAgent 名称不能为空" }
            val modelRef =
                value.get("model") ?: error("SubAgent $key 缺少 model（provider_id/model_id）")
            require(
                modelRef.isJsonObject &&
                    modelRef.asJsonObject.keySet() == setOf("provider_id", "model_id")
            ) {
                "SubAgent model 必须包含 provider_id/model_id"
            }
            modelRef.asJsonObject.entrySet().forEach { (key, id) ->
                require(
                    id.isJsonPrimitive &&
                        id.asJsonPrimitive.isNumber &&
                        id.asLong > 0 &&
                        id.asString == id.asLong.toString()
                ) {
                    "model.$key 必须是正整数"
                }
            }
            val model = resolveModel(modelRef)
            require(
                model.id == modelRef.asJsonObject.get("model_id").asLong &&
                    model.providerId == modelRef.asJsonObject.get("provider_id").asLong
            ) {
                "模型引用与解析结果不匹配"
            }
            require(model.enabled != 0 && model.id != null) { "SubAgent $key 模型无效或已禁用" }
            fun selection(field: String): AgentEnabledItemsConfig {
                val array = value.get(field) ?: return AgentEnabledItemsConfig()
                require(array.isJsonArray) { "$field 必须是 array" }
                return AgentEnabledItemsConfig(
                    array.asJsonArray.map {
                        require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                        it.asString
                    }
                )
            }
            val mode = text("access_mode") ?: "read_only"
            require(mode in setOf("read_only", "workspace_write")) { "SubAgent access_mode 无效" }
            val bash =
                value.get("bash_enabled")?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) {
                        "bash_enabled 必须是 boolean"
                    }
                    it.asBoolean
                } ?: false
            require(!bash || mode == "workspace_write") { "只读 SubAgent 不允许 bash" }
            val runtime =
                value.get("runtime_params")?.let {
                    require(
                        it.isJsonObject &&
                            it.asJsonObject.keySet().all { key ->
                                key in
                                    setOf(
                                        "include_history",
                                        "max_history_messages",
                                        "tool_call_retention_rounds",
                                    )
                            }
                    ) {
                        "runtime_params 包含未知字段"
                    }
                    Gson().fromJson(it, AgentRuntimeConfig::class.java)
                } ?: AgentRuntimeConfig()
            require(
                (runtime.maxHistoryMessages ?: 1) > 0 && (runtime.toolCallRetentionRounds ?: 0) >= 0
            ) {
                "SubAgent 运行参数无效"
            }
            val timeout = value.get("max_runtime_secs")?.takeUnless { it.isJsonNull }?.asLong
            require(timeout == null || timeout > 0) { "max_runtime_secs 必须为正数" }
            val tools = selection("tools")
            require(
                tools.enabled.all {
                    it in
                        com.lhstack.tools.agent.model.tools.RuntimeTools
                            .REGISTERED_BUILTIN_TOOL_NAMES
                }
            ) {
                "SubAgent 包含未知工具"
            }
            value.get("view_resources")?.let { element ->
                require(
                    element.isJsonObject &&
                        element.asJsonObject.keySet().all {
                            it in setOf("image", "audio", "video", "file")
                        }
                ) {
                    "view_resources 字段无效"
                }
                element.asJsonObject.entrySet().forEach { (kind, item) ->
                    require(
                        item.isJsonObject &&
                            item.asJsonObject.keySet().all { it in setOf("enabled", "agent_id", "timeout_secs") }
                    ) {
                        "$kind 资源配置字段无效"
                    }
                    val ref = Gson().fromJson(item, AgentViewResourceRef::class.java)
                    require(ref.timeoutSecs == null || ref.timeoutSecs in 1..Int.MAX_VALUE.toLong()) {
                        "$kind 工具超时必须为 1–2147483647 秒"
                    }
                    if (ref.enabled)
                        require(ref.agentId?.let(AgentService::agentById)?.enabled == true) {
                            "$kind 必须选择可用的资源 Agent"
                        }
                }
            }
            value.get("persona")?.let {
                require(
                    it.isJsonObject &&
                        it.asJsonObject.keySet().all { key ->
                            key in
                                setOf(
                                    "memory",
                                    "memory_max_chars",
                                    "behavior_habits",
                                    "behavior_habits_max_chars",
                                    "soul",
                                    "soul_max_chars",
                                    "profile",
                                    "profile_max_chars",
                                    "guardrails",
                                    "guardrails_max_chars",
                                )
                        }
                ) {
                    "persona 字段无效"
                }
            }
            value.get("persona")?.asJsonObject?.entrySet()?.forEach { (key, value) ->
                if (key.endsWith("_max_chars"))
                    require(
                        value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asInt >= 0
                    ) {
                        "$key 必须是非负整数"
                    }
                else
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                        "$key 必须是字符串"
                    }
            }
            value.get("persona")?.let {
                val persona = Gson().fromJson(it, AgentPersonaConfig::class.java)
                listOf(
                        persona.memoryMaxChars,
                        persona.behaviorHabitsMaxChars,
                        persona.soulMaxChars,
                        persona.profileMaxChars,
                        persona.guardrailsMaxChars,
                    )
                    .forEach { limit -> require(limit >= 0) { "人格字符上限不能为负数" } }
            }
            return ProjectSubagent(
                key,
                AgentRecord(
                    null,
                    name,
                    text("description"),
                    true,
                    model.providerId,
                    model.id,
                    null,
                    text("system_prompt"),
                    runtime,
                    AgentCapabilityConfig(
                        tools = tools,
                        skills = selection("skills"),
                        viewResources =
                            value.get("view_resources")?.let {
                                Gson().fromJson(it, AgentViewResourcesConfig::class.java)
                            } ?: AgentViewResourcesConfig(),
                        persona =
                            value.get("persona")?.let {
                                Gson().fromJson(it, AgentPersonaConfig::class.java)
                            } ?: AgentPersonaConfig(),
                    ),
                    AgentDistillConfig(),
                    "text",
                    timeout,
                    emptyList(),
                    null,
                    null,
                ),
                mode,
                bash,
            )
        }
    }
}
