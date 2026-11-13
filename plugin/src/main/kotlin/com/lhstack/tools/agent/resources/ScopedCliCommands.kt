package com.lhstack.tools.agent.resources

import com.google.gson.*
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.tools.McpRuntime

internal class ScopedCliCommands(
    private val projectRoot: java.io.File?,
    private val cancel: ModelCancel?,
    private val resourceLookup: (() -> RuntimeResourceSnapshot)? = null,
) {
    private val resources = ScopedResources(projectRoot)
    private val outputs = com.lhstack.tools.agent.model.tools.McpCallOutputSupport()

    private fun outputLimit(): Int {
        val raw = com.lhstack.tools.db.service.SettingService.setting("mcp.call.max_output_chars") ?: "8000"
        return com.lhstack.tools.agent.model.tools.McpCallOutputSupport.validateConfiguredLimit(raw)
    }

    fun execute(command: String, args: JsonObject): JsonElement? {
        if (
            !command.startsWith("mcp.") &&
                !command.startsWith("skill.") &&
                !command.startsWith("subagent.")
        )
            return null
        require(!args.has("project") && !args.has("project_path") && !args.has("cwd")) {
            "资源命令固定绑定当前项目，不接受其他项目路径"
        }
        fun text(key: String) =
            args.get(key)?.takeUnless { it.isJsonNull }?.asString ?: error("缺少 $key")
        fun result(value: Any?) = Gson().toJsonTree(value)
        val snapshot =
            if (
                command in setOf("mcp.create", "mcp.update", "mcp.delete") ||
                    command.startsWith("subagent.")
            )
                null
            else resourceLookup?.invoke()
        val scopes = if (projectRoot == null) listOf("global") else listOf("global", "project")
        if (command in setOf("mcp.list", "skill.list"))
            require(args.size() == 0) { "$command 不接受参数，返回所有可用来源" }
        if (command == "mcp.list")
            return result(
                if (snapshot == null) scopes.flatMap(resources::mcps)
                else
                    snapshot.mcps.map { (ref, server) ->
                        com.lhstack.tools.db.service.McpService.map(server).apply {
                            addProperty("scope", ref.substringBefore(":"))
                            addProperty("id", ref.substringAfter(":mcp:"))
                        }
                    }
            )
        if (command == "skill.list")
            return result(
                scopes.flatMap { scope ->
                    val source = snapshot?.skills?.singleOrNull { it.scope == scope }
                    (source?.let { com.lhstack.tools.db.service.ResourceConfigService(it.root) }
                            ?: resources.skills(scope))
                        .listSkills()
                        .filter { snapshot == null || source != null && it.name in source.enabled }
                        .map { skill ->
                            Gson().toJsonTree(skill).asJsonObject.apply {
                                addProperty("scope", scope)
                            }
                        }
                }
            )
        val scope = resources.scope(args)
        if (command.startsWith("mcp.")) {
            fun runtime(): McpRuntime {
                val id = text("id")
                val ref = "$scope:mcp:$id"
                require(snapshot == null || ref in snapshot.mcps) { "当前运行未授权 MCP $ref" }
                val server =
                    if (snapshot == null) resources.mcp(scope, id)
                    else snapshot.mcps[ref] ?: error("MCP 未授权")
                require(server.enabled != 0) { "MCP 已禁用" }
                val timeout = args.get("timeout_secs")?.asLong ?: 30
                require(timeout in 1..300) { "timeout_secs 必须为 1–300" }
                return McpRuntime(
                    server,
                    timeout,
                    cancel,
                    if (scope == "project") projectRoot?.path else null,
                )
            }
            return when (command) {
                "mcp.get" -> {
                    val ref = "$scope:mcp:${text("id")}"
                    val server =
                        if (snapshot == null) resources.mcp(scope, text("id"))
                        else snapshot.mcps[ref] ?: error("MCP 未授权")
                    com.lhstack.tools.db.service.McpService.map(server).apply {
                        addProperty("scope", scope)
                        addProperty("id", text("id"))
                    }
                }
                "mcp.create",
                "mcp.update" -> resources.saveMcp(scope, args, command.endsWith("create"))
                "mcp.delete" -> {
                    resources.deleteMcp(scope, text("id"))
                    result(mapOf("deleted" to true, "scope" to scope))
                }
                "mcp.test" -> runtime().test()
                "mcp.tools" -> runtime().tools()
                "mcp.resources" -> runtime().resources()
                "mcp.read_resource" -> runtime().readResource(text("uri"))
                "mcp.call" -> {
                    val connection = runtime()
                    val limit = outputLimit()
                    outputs.validateLimit(limit)
                    outputs.deliver(connection.callTool(text("tool"), args.getAsJsonObject("arguments") ?: JsonObject()), limit)
                }
                else -> error("未知 MCP 命令 $command")
            }
        }
        if (command.startsWith("skill.")) {
            val store = resources.skills(scope)
            val value: Any? =
                when (command) {
                    "skill.get",
                    "skill.files" -> store.skillFileTree(text("name"))
                    "skill.read" -> store.readSkillFile(text("name"), args.get("path")?.asString)
                    "skill.create" ->
                        mapOf("name" to store.createSkill(text("name"), text("description")))
                    "skill.import" -> store.importSkills(text("source_path"))
                    "skill.write" -> {
                        store.writeSkillFile(text("name"), text("path"), text("content"))
                        mapOf("saved" to true)
                    }
                    "skill.create_file" ->
                        store.createSkillFile(
                            text("name"),
                            args.get("parent_path")?.asString,
                            text("file_name"),
                        )
                    "skill.create_directory" ->
                        store.createSkillDirectory(
                            text("name"),
                            args.get("parent_path")?.asString,
                            text("directory_name"),
                        )
                    "skill.delete" -> {
                        store.deleteSkill(text("name"))
                        mapOf("deleted" to true)
                    }
                    "skill.delete_entry" -> {
                        store.deleteSkillEntry(text("name"), text("path"))
                        mapOf("deleted" to true)
                    }
                    else -> error("未知 Skill 命令 $command")
                }
            return JsonObject().apply {
                addProperty("scope", scope)
                addProperty("storage", store.skillsRootDir().path)
                add("result", result(value))
            }
        }
        if (scope == "global") {
            val delegated = args.deepCopy().apply { remove("scope") }
            val value =
                com.lhstack.tools.agent.model.tools.CliManagementCommands.execute(
                    command.replaceFirst("subagent.", "agent."),
                    delegated,
                ) ?: error("未知 SubAgent 管理命令 $command；执行使用 subagent_run 工具")
            return JsonObject().apply {
                addProperty("scope", scope)
                add("result", value)
            }
        }
        return when (command) {
            "subagent.list" ->
                result(
                    resources.projectAgents().map { (key, item) ->
                        mapOf(
                            "id" to key,
                            "scope" to scope,
                            "name" to item.agent.name,
                            "access_mode" to item.accessMode,
                        )
                    }
                )
            "subagent.get" ->
                ProjectResourceStore(projectRoot ?: error("无项目"))
                    .read("subAgents.json", "subAgents")
                    .get(text("id"))
                    ?.asJsonObject
                    ?.deepCopy()
                    ?.apply {
                        addProperty("scope", scope)
                        addProperty("id", text("id"))
                    } ?: error("项目 SubAgent 不存在")
            "subagent.create",
            "subagent.update" -> {
                resources.saveProjectAgent(
                    text("id"),
                    args.getAsJsonObject("definition") ?: error("缺少 definition"),
                    command.endsWith("create"),
                )
                result(mapOf("scope" to scope, "id" to text("id")))
            }
            "subagent.delete" -> {
                resources.deleteProjectAgent(text("id"))
                result(mapOf("scope" to scope, "deleted" to true))
            }
            else -> error("未知 SubAgent 管理命令 $command")
        }
    }

    companion object {
        val docs: List<Triple<String, String, JsonObject>> = buildList {
            val commands =
                mapOf(
                    "skill.list" to "一次返回当前环境授权的全局技能和项目技能，条目携带 scope；不传 scope。",
                    "skill.get" to "技能文件树",
                    "skill.files" to "技能文件树",
                    "skill.read" to "读取技能文件",
                    "skill.create" to "创建技能",
                    "skill.import" to "导入技能目录",
                    "skill.write" to "写入技能文件",
                    "skill.create_file" to "创建文件",
                    "skill.create_directory" to "创建子目录",
                    "skill.delete" to "删除技能",
                    "skill.delete_entry" to "删除文件或子目录",
                    "subagent.list" to "列出子 Agent 定义",
                    "subagent.get" to "读取定义",
                    "subagent.create" to "创建定义",
                    "subagent.update" to "修改定义",
                    "subagent.delete" to "删除定义",
                )
            commands.forEach { (name, description) ->
                add(
                    Triple(
                        name,
                        description,
                        JsonParser.parseString(
                                """{"scope":{"type":"string","required":true,"description":"global/project；项目固定绑定当前 IDE 项目"},"id":{"type":"string|integer","description":"SubAgent identity"},"name":{"type":"string","description":"Skill name"},"description":{"type":"string","description":"Skill description"},"path":{"type":"string","description":"技能相对文件路径"},"parent_path":{"type":"string"},"file_name":{"type":"string"},"directory_name":{"type":"string"},"source_path":{"type":"string"},"content":{"type":"string"},"definition":{"type":"object","description":"项目：name/model({provider_id,model_id})/system_prompt/tools/skills/view_resources/persona/runtime_params/max_runtime_secs/access_mode/bash_enabled；全局：沿用 agent.* 命令字段（provider_id/model_id/extra_prompt 等，不传 definition）"}}"""
                            )
                            .asJsonObject
                            .apply {
                                if (name == "skill.list") {
                                    keySet().toList().forEach(::remove)
                                }
                            },
                    )
                )
            }
        }
    }
}
