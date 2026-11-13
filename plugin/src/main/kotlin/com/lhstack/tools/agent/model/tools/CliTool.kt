package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn

/**
 * Structure-first CLI Function Calling entry. Commands use `domain.action`, for example `mcp.list`.
 */
class CliTool(
    private val cancel: ModelCancel? = null,
    private val project: com.intellij.openapi.project.Project? = null,
    private val resourceLookup: (() -> com.lhstack.tools.agent.resources.RuntimeResourceSnapshot)? =
        null,
    private val subagentSession: Boolean = false,
    private val readOnly: Boolean = false,
) : ToolDyn {
    override fun definition(prompt: String): ToolDefinition =
        ToolDefinition(
            name = NAME,
            description = "JTools 结构化 CLI，arguments 为参数对象。各指令用途：\n" +
                commandSpecs().joinToString("\n") { (name, description, _) -> "$name：${shortDescription(description)}" } +
                "\n详细参数：command=\"<指令> --help\"。",
            parameters =
                JsonParser.parseString(
                    """
                    {
                      "type":"object",
                      "properties":{
                        "command":{"type":"string","description":"必填。命令名；doc 返回简明索引，<command> --help 返回该命令详细说明。"},
                        "arguments":{"type":"object","description":"可选。结构化命令参数；doc 和列表命令可省略。"}
                      },
                      "required":["command"]
                    }
                    """
                        .trimIndent()
                ),
        )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.requireObject("cli arguments")
        val rawCommand = input.string("command").trim()
        if (rawCommand == "doc" || rawCommand == "--help") return doc()
        if (rawCommand.endsWith(" --help"))
            return commandDoc(rawCommand.removeSuffix(" --help").trim())
        require(!subagentSession || rawCommand !in setOf("agent.run", "agent.run.cancel")) {
            "SubAgent 不能启动或管理其他 Agent 运行"
        }
        require(
            !readOnly ||
                rawCommand.substringAfterLast('.') in
                    setOf(
                        "list",
                        "get",
                        "read",
                        "files",
                        "tools",
                        "resources",
                        "read_resource",
                        "test",
                        "remote_models",
                    )
        ) {
            "只读 SubAgent 不允许该 CLI 操作"
        }
        val arguments = input.get("arguments")?.requireObject("arguments") ?: JsonObject()
        return execute(rawCommand, arguments)
    }

    private val scopedCommands =
        com.lhstack.tools.agent.resources.ScopedCliCommands(
            project?.basePath?.let { java.io.File(it) },
            cancel,
            resourceLookup = resourceLookup,
        )

    private fun execute(command: String, args: JsonObject): JsonElement =
        scopedCommands.execute(command, args)
            ?: CliManagementCommands.execute(command, args)
            ?: throw IllegalArgumentException("未知 CLI 指令 `$command`，请调用 doc")

    /** 总览只返回索引；详细参数通过具体命令的 --help 按需读取。 */
    private fun doc(): JsonObject =
        JsonObject().apply {
            addProperty("tool", NAME)
            addProperty(
                "usage",
                "调用方式：{\"command\":\"<name>\",\"arguments\":{...}}。先选择命令，再调用 {\"command\":\"<name> --help\"} 查看详细参数。",
            )
            add(
                "commands",
                JsonArray().apply {
                    commandSpecs().forEach { (name, description, _) ->
                        add(
                            JsonObject().apply {
                                addProperty("command", name)
                                addProperty("description", shortDescription(description))
                                addProperty("help", "$name --help")
                            }
                        )
                    }
                },
            )
        }

    private fun commandDoc(command: String): JsonObject {
        val spec =
            commandSpecs().firstOrNull { it.first == command }
                ?: throw IllegalArgumentException("未知 CLI 指令 `$command`")
        return JsonObject().apply {
            addProperty("command", spec.first)
            addProperty("description", spec.second)
            add("arguments", spec.third)
        }
    }

    private fun commandSpecs(): List<Triple<String, String, JsonObject>> =
        COMMANDS.map { (name, description, fields) ->
            Triple(
                name,
                description,
                fields.deepCopy().apply {
                    if (name.startsWith("mcp.") && name != "mcp.list") {
                        add("scope", field("string", true, "global/project；写操作仅影响对应存储"))
                        if (has("id")) add("id", field("string|integer", true, "全局数据库 ID 或项目 key"))
                    }
                },
            )
        } +
            com.lhstack.tools.agent.resources.ScopedCliCommands.docs.map {
                (name, description, fields) ->
                val merged =
                    COMMANDS.firstOrNull { it.first == name.replaceFirst("subagent.", "agent.") }
                        ?.third
                        ?.deepCopy() ?: JsonObject()
                fields.entrySet().forEach { (key, value) -> merged.add(key, value) }
                Triple(name, description, merged)
            }

    private fun shortDescription(description: String): String =
        description.substringBefore("。").substringBefore('\n') + "。"

    private fun props(vararg values: Pair<String, JsonElement>): JsonObject =
        JsonObject().apply { values.forEach { (name, value) -> add(name, value) } }

    private fun field(type: String, required: Boolean = false, description: String): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            addProperty("required", required)
            addProperty("description", description)
        }

    private val COMMANDS: List<Triple<String, String, JsonObject>> by lazy {
        val id = field("integer", true, "MCP 服务 ID")
        val timeout = field("integer", false, "连接/调用超时秒数，1-300，默认 30")
        listOf(
            Triple(
                "doc",
                "列出命令与简短用途；详细参数通过 <command> --help 获取。",
                JsonObject(),
            ),
            Triple(
                "mcp.list",
                "一次列出当前环境可用的全局与项目 MCP 服务，每项含 scope 和 id；无需参数。查询服务清单用此命令。",
                JsonObject(),
            ),
            Triple("mcp.get", "获取一个 MCP 服务的完整配置。", props("id" to id)),
            Triple(
                "mcp.create",
                "新增 MCP 服务。transport 支持 stdio、sse、streamable_http。",
                props(
                    "name" to field("string", true, "服务名称"),
                    "transport" to field("string", true, "stdio/sse/streamable_http"),
                    "enabled" to field("boolean", false, "是否启用，默认 true"),
                    "command" to field("string", false, "stdio 启动程序"),
                    "args" to field("array<string>", false, "stdio 参数"),
                    "env" to field("object<string,string>", false, "stdio 环境变量"),
                    "url" to field("string", false, "SSE/HTTP 地址"),
                    "headers" to field("object<string,string>", false, "HTTP 请求头"),
                ),
            ),
            Triple(
                "mcp.update",
                "更新 MCP 服务；只修改 arguments 中出现的字段。",
                props(
                    "id" to id,
                    "name" to field("string", false, "服务名称"),
                    "transport" to field("string", false, "stdio/sse/streamable_http"),
                    "enabled" to field("boolean", false, "是否启用"),
                    "command" to field("string|null", false, "stdio 启动程序；null 清空"),
                    "args" to field("array<string>", false, "整体替换 stdio 参数"),
                    "env" to field("object<string,string>", false, "整体替换环境变量"),
                    "url" to field("string|null", false, "远端地址；null 清空"),
                    "headers" to field("object<string,string>", false, "整体替换请求头"),
                ),
            ),
            Triple("mcp.delete", "删除 MCP 服务。", props("id" to id)),
            Triple(
                "mcp.test",
                "连接 MCP 服务并返回服务信息和工具列表。",
                props("id" to id, "timeout_secs" to timeout),
            ),
            Triple(
                "mcp.tools",
                "获取指定 MCP 服务的工具名称、描述与输入 schema，必须传 mcp.list 返回的 scope 和 id；查询有哪些服务请用 mcp.list。",
                props("id" to id, "timeout_secs" to timeout),
            ),
            Triple(
                "mcp.resources",
                "获取 MCP 服务公开的资源列表。",
                props("id" to id, "timeout_secs" to timeout),
            ),
            Triple(
                "mcp.read_resource",
                "按 URI 读取 MCP 资源。",
                props(
                    "id" to id,
                    "uri" to field("string", true, "资源 URI"),
                    "timeout_secs" to timeout,
                ),
            ),
            Triple(
                "mcp.call",
                "调用 MCP 服务工具。",
                props(
                    "id" to id,
                    "tool" to field("string", true, "工具名称"),
                    "arguments" to field("object", false, "工具参数，默认 {}"),
                    "timeout_secs" to timeout,
                ),
            ),
            Triple("provider.list", "列出模型供应商。", JsonObject()),
            Triple("provider.get", "获取供应商。", props("id" to field("integer", true, "供应商 ID"))),
            Triple(
                "provider.remote_models",
                "从供应商远端 API 获取可用模型列表；使用该供应商已保存的 API Key、Base URL、代理和协议配置。",
                props("id" to field("integer", true, "供应商 ID")),
            ),
            Triple(
                "provider.create",
                "创建供应商。",
                props(
                    "name" to field("string", true, "名称"),
                    "kind" to field("string", true, "open_ai/open_ai_compatible/anthropic"),
                    "api_key" to field("string", false, "API Key"),
                    "base_url" to field("string", false, "Base URL"),
                    "api" to field("string", false, "responses/completions"),
                    "enabled" to field("boolean", false, "是否启用"),
                ),
            ),
            Triple(
                "provider.update",
                "更新供应商，未传字段保持不变。",
                props(
                    "id" to field("integer", true, "供应商 ID"),
                    "name" to field("string", false, "名称"),
                    "kind" to field("string", false, "open_ai/open_ai_compatible/anthropic"),
                    "api_key" to field("string|null", false, "API Key"),
                    "base_url" to field("string|null", false, "Base URL"),
                    "api" to field("string|null", false, "responses/completions"),
                    "anthropic_version" to field("string|null", false, "Anthropic 版本"),
                    "provider_config" to field("object", false, "供应商配置"),
                    "enabled" to field("boolean", false, "是否启用"),
                ),
            ),
            Triple(
                "provider.delete",
                "删除供应商及其模型。",
                props("id" to field("integer", true, "供应商 ID")),
            ),
            Triple(
                "model.list",
                "列出供应商模型。",
                props("provider_id" to field("integer", true, "供应商 ID")),
            ),
            Triple("model.get", "获取模型。", props("id" to field("integer", true, "模型 ID"))),
            Triple(
                "model.create",
                "创建模型。",
                props(
                    "provider_id" to field("integer", true, "供应商 ID"),
                    "alias" to field("string", true, "模型别名"),
                    "model_id" to field("string", true, "远端模型 ID"),
                    "modalities" to field("array<string>", false, "能力列表"),
                    "model_params" to field("object", false, "模型参数"),
                    "execution_params" to field("object", false, "执行参数，可含 history_token_ratio"),
                    "additional_params" to field("object", false, "附加参数"),
                ),
            ),
            Triple(
                "model.update",
                "更新模型，未传字段保持不变。",
                props(
                    "id" to field("integer", true, "模型 ID"),
                    "provider_id" to field("integer", false, "供应商 ID"),
                    "alias" to field("string", false, "模型别名"),
                    "model_id" to field("string", false, "远端模型 ID"),
                    "display_name" to field("string|null", false, "显示名"),
                    "api" to field("string|null", false, "responses/completions"),
                    "context_window" to field("integer|null", false, "上下文窗口"),
                    "modalities" to field("array<string>", false, "能力列表"),
                    "model_params" to field("object|null", false, "模型参数"),
                    "execution_params" to
                        field("object|null", false, "执行参数，可含 history_token_ratio"),
                    "additional_params" to field("object|null", false, "附加参数"),
                    "enabled" to field("boolean", false, "是否启用"),
                ),
            ),
            Triple("model.delete", "删除模型。", props("id" to field("integer", true, "模型 ID"))),
            Triple("agent.list", "列出 Agent。", JsonObject()),
            Triple(
                "agent.get",
                "获取 Agent 完整配置。",
                props("id" to field("integer", true, "Agent ID")),
            ),
            Triple(
                "agent.create",
                "创建 Agent。",
                props(
                    "name" to field("string", true, "名称"),
                    "description" to field("string", false, "描述"),
                    "enabled" to field("boolean", false, "是否启用"),
                    "provider_id" to field("integer", false, "供应商 ID"),
                    "model_id" to field("integer", false, "模型 ID"),
                    "prompt_id" to field("integer", false, "提示词 ID"),
                    "extra_prompt" to field("string", false, "扩展提示"),
                    "runtime_params" to field("object", false, "运行配置"),
                    "ext_config" to field("object", false, "能力配置"),
                    "distill_config" to field("object", false, "蒸馏配置"),
                    "output_mode" to field("string", false, "text/json"),
                    "max_runtime_secs" to field("integer", false, "最大运行秒数"),
                    "tags" to field("array<string>", false, "标签"),
                ),
            ),
            Triple(
                "agent.update",
                "更新 Agent，未传字段保持不变。",
                props(
                    "id" to field("integer", true, "Agent ID"),
                    "name" to field("string", false, "名称"),
                    "description" to field("string|null", false, "描述"),
                    "enabled" to field("boolean", false, "是否启用"),
                    "provider_id" to field("integer|null", false, "供应商 ID"),
                    "model_id" to field("integer|null", false, "模型 ID"),
                    "prompt_id" to field("integer|null", false, "提示词 ID"),
                    "extra_prompt" to field("string|null", false, "扩展提示"),
                    "runtime_params" to field("object", false, "整体替换运行配置"),
                    "ext_config" to field("object", false, "整体替换能力配置"),
                    "distill_config" to field("object", false, "整体替换蒸馏配置"),
                    "output_mode" to field("string", false, "text/json"),
                    "max_runtime_secs" to field("integer|null", false, "最大运行秒数"),
                    "tags" to field("array<string>", false, "标签"),
                ),
            ),
            Triple("agent.delete", "删除 Agent。", props("id" to field("integer", true, "Agent ID"))),
            Triple(
                "agent.run",
                "异步运行 Agent；立即返回 run_id,当用户没有明确需要获取 agent 运行结果，则不要调用 agent.run.get获取 agent 运行结果，并可向会话投递实时 Agent 运行卡片。",
                props(
                    "agent_id" to field("integer", true, "Agent ID"),
                    "prompt" to field("string", true, "运行提示"),
                    "project" to field("string", true, "当前已打开的项目路径"),
                    "session_id" to field("integer", true, "投递会话 ID"),
                    "receiver" to
                        field(
                            "string",
                            true,
                            "接收方：user 表示以 AI/助手身份向用户发送，结果作为模型回复；ai 表示以用户身份向 AI 发送，结果作为用户消息进入会话队列",
                        ),
                ),
            ),
            Triple(
                "agent.run.get",
                "查询异步 Agent 运行状态和结果。",
                props("run_id" to field("string", true, "运行 ID")),
            ),
        )
    }

    private fun JsonElement.requireObject(name: String): JsonObject =
        takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("$name 必须是 JSON object")

    private fun JsonObject.string(name: String): String =
        optionalString(name)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺少参数 `$name`")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(name: String): Long =
        optionalLong(name) ?: throw IllegalArgumentException("缺少参数 `$name`")

    private fun JsonObject.optionalLong(name: String): Long? =
        get(name)
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asLong

    private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

    private fun JsonObject.jsonOrDefault(name: String, default: JsonElement): JsonElement =
        get(name) ?: default

    companion object {
        const val NAME = "cli"
    }
}
