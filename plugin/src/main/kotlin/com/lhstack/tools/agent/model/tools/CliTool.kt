package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import com.lhstack.tools.db.entity.McpServerEntity
import com.lhstack.tools.db.service.McpService
import com.lhstack.tools.agent.model.http.ModelCancel

/** Structure-first CLI Function Calling entry. Commands use `domain.action`, for example `mcp.list`. */
class CliTool(
    private val cancel: ModelCancel? = null,
) : ToolDyn {
    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = NAME,
        description = "Manage and call external MCP servers through structured JTools CLI commands.\n" +
            "Available commands cover server list/get/create/update/delete, connection test, tools, resources, resource reads, and tool calls.\n" +
            "If you do not remember a command\u0027s exact arguments, call command=\u0027doc\u0027 once to get the full parameter reference for every MCP command.",
        parameters = JsonParser.parseString(
            """
            {
              "type":"object",
              "properties":{
                "command":{"type":"string","description":"MCP command name, for example doc, mcp.list, mcp.create, or mcp.call. Call doc first to see every command and its arguments."},
                "arguments":{"type":"object","description":"Structured command arguments. Omit for doc and list commands."}
              },
              "required":["command"]
            }
            """.trimIndent()
        ),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val input = args.requireObject("cli arguments")
        val command = input.string("command").trim()
        if (command == "doc") return doc()
        val arguments = input.get("arguments")?.requireObject("arguments") ?: JsonObject()
        return execute(command, arguments)
    }

    private fun execute(command: String, args: JsonObject): JsonElement = when (command) {
        "mcp.list" -> JsonArray().apply { McpService.list().forEach { add(McpService.map(it)) } }
        "mcp.get" -> McpService.map(requireServer(args.long("id")))
        "mcp.create" -> McpService.map(McpService.save(createServer(args)))
        "mcp.update" -> McpService.map(updateServer(args))
        "mcp.delete" -> JsonObject().apply { McpService.delete(args.long("id")); addProperty("deleted", true) }
        "mcp.test" -> runtime(args).test()
        "mcp.tools" -> runtime(args).tools()
        "mcp.resources" -> runtime(args).resources()
        "mcp.read_resource" -> runtime(args).readResource(args.string("uri"))
        "mcp.call" -> runtime(args).callTool(args.string("tool"), args.get("arguments")?.requireObject("arguments") ?: JsonObject())        else -> throw IllegalArgumentException("未知 CLI 指令 `$command`，请调用 `doc`")
    }

    private fun createServer(args: JsonObject): McpServerEntity = McpServerEntity().apply {
        name = args.string("name")
        enabled = if (args.boolean("enabled", true)) 1 else 0
        transport = normalizeTransport(args.string("transport"))
        command = args.optionalString("executable")
        this.args = args.jsonOrDefault("args", JsonArray()).also { require(it.isJsonArray) { "args 必须是 JSON array" } }.toString()
        env = args.jsonOrDefault("env", JsonObject()).also { require(it.isJsonObject) { "env 必须是 JSON object" } }.toString()
        url = args.optionalString("url")
        headers = args.jsonOrDefault("headers", JsonObject()).also { require(it.isJsonObject) { "headers 必须是 JSON object" } }.toString()
        validateServer(this)
    }

    private fun updateServer(args: JsonObject): McpServerEntity {
        val server = requireServer(args.long("id"))
        args.optionalString("name")?.let { server.name = it }
        if (args.has("enabled")) server.enabled = if (args.boolean("enabled", true)) 1 else 0
        args.optionalString("transport")?.let { server.transport = normalizeTransport(it) }
        if (args.has("executable")) server.command = args.get("executable").takeUnless { it.isJsonNull }?.asString
        args.get("args")?.let { require(it.isJsonArray) { "args 必须是 JSON array" }; server.args = it.toString() }
        args.get("env")?.let { require(it.isJsonObject) { "env 必须是 JSON object" }; server.env = it.toString() }
        if (args.has("url")) server.url = args.get("url").takeUnless { it.isJsonNull }?.asString
        args.get("headers")?.let { require(it.isJsonObject) { "headers 必须是 JSON object" }; server.headers = it.toString() }
        validateServer(server)
        return McpService.save(server)
    }

    private fun validateServer(server: McpServerEntity) {
        require(server.name.isNotBlank()) { "MCP name 不能为空" }
        when (server.transport) {
            "stdio" -> require(!server.command.isNullOrBlank()) { "stdio MCP 必须提供 executable" }
            "sse", "streamable_http" -> require(!server.url.isNullOrBlank()) { "${server.transport} MCP 必须提供 url" }
        }
    }

    private fun runtime(args: JsonObject): McpRuntime {
        val server = requireServer(args.long("id"))
        require(server.enabled != 0) { "MCP 服务 `${server.id}` 已禁用" }
        return McpRuntime(
            server,
            args.optionalLong("timeout_secs")?.coerceIn(1, 300) ?: 30,
            cancel,
        )
    }

    private fun requireServer(id: Long): McpServerEntity =
        McpService.get(id) ?: throw IllegalArgumentException("MCP 服务 `$id` 不存在")

    private fun normalizeTransport(value: String): String = when (value.lowercase().replace('-', '_')) {
        "stdio" -> "stdio"
        "sse" -> "sse"
        "streamable_http" -> "streamable_http"
        else -> throw IllegalArgumentException("transport 仅支持 stdio、sse、streamable_http")
    }

    /** 一次性输出全部命令的完整参数说明，模型无需多轮 help 即可自描述调用。 */
    private fun doc(): JsonObject = JsonObject().apply {
        addProperty("tool", NAME)
        addProperty("usage", "调用方式：{\"command\":\"<name>\",\"arguments\":{...}}。arguments 按下面每个命令的字段填写。")
        add("commands", JsonArray().apply {
            COMMANDS.forEach { (name, description, arguments) ->
                add(JsonObject().apply {
                    addProperty("command", name)
                    addProperty("description", description)
                    add("arguments", arguments)
                })
            }
        })
    }

    private fun props(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject().apply {
        values.forEach { (name, value) -> add(name, value) }
    }

    private fun field(type: String, required: Boolean = false, description: String): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("required", required)
        addProperty("description", description)
    }

    private val COMMANDS: List<Triple<String, String, JsonObject>> by lazy {
        val id = field("integer", true, "MCP 服务 ID")
        val timeout = field("integer", false, "连接/调用超时秒数，1-300，默认 30")
        listOf(
            Triple("doc", "\u8fd4\u56de\u6240\u6709\u547d\u4ee4\u53ca\u5176\u5b8c\u6574\u53c2\u6570\u8bf4\u660e\uff08\u65e0\u8bb0\u5fc6\u65f6\u5148\u8c03\u7528\u5b83\uff09\u3002", JsonObject()),
            Triple("mcp.list", "列出全部 MCP 服务。", JsonObject()),
            Triple("mcp.get", "获取一个 MCP 服务的完整配置。", props("id" to id)),
            Triple("mcp.create", "新增 MCP 服务。transport 支持 stdio、sse、streamable_http。", props(
                "name" to field("string", true, "服务名称"), "transport" to field("string", true, "stdio/sse/streamable_http"),
                "enabled" to field("boolean", false, "是否启用，默认 true"), "executable" to field("string", false, "stdio 启动程序"),
                "args" to field("array<string>", false, "stdio 参数"), "env" to field("object<string,string>", false, "stdio 环境变量"),
                "url" to field("string", false, "SSE/HTTP 地址"), "headers" to field("object<string,string>", false, "HTTP 请求头"),
            )),
            Triple("mcp.update", "更新 MCP 服务；只修改 arguments 中出现的字段。", props(
                "id" to id, "name" to field("string", false, "服务名称"), "transport" to field("string", false, "stdio/sse/streamable_http"),
                "enabled" to field("boolean", false, "是否启用"), "executable" to field("string|null", false, "stdio 启动程序；null 清空"),
                "args" to field("array<string>", false, "整体替换 stdio 参数"), "env" to field("object<string,string>", false, "整体替换环境变量"),
                "url" to field("string|null", false, "远端地址；null 清空"), "headers" to field("object<string,string>", false, "整体替换请求头"),
            )),
            Triple("mcp.delete", "删除 MCP 服务。", props("id" to id)),
            Triple("mcp.test", "连接 MCP 服务并返回服务信息和工具列表。", props("id" to id, "timeout_secs" to timeout)),
            Triple("mcp.tools", "获取 MCP 服务的工具列表。", props("id" to id, "timeout_secs" to timeout)),
            Triple("mcp.resources", "获取 MCP 服务公开的资源列表。", props("id" to id, "timeout_secs" to timeout)),
            Triple("mcp.read_resource", "按 URI 读取 MCP 资源。", props("id" to id, "uri" to field("string", true, "资源 URI"), "timeout_secs" to timeout)),
            Triple("mcp.call", "调用 MCP 服务工具。", props("id" to id, "tool" to field("string", true, "工具名称"), "arguments" to field("object", false, "工具参数，默认 {}"), "timeout_secs" to timeout)),
        )
    }

    private fun JsonElement.requireObject(name: String): JsonObject =
        takeIf { it.isJsonObject }?.asJsonObject ?: throw IllegalArgumentException("$name 必须是 JSON object")

    private fun JsonObject.string(name: String): String = optionalString(name)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("缺少参数 `$name`")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(name: String): Long = optionalLong(name)
        ?: throw IllegalArgumentException("缺少参数 `$name`")

    private fun JsonObject.optionalLong(name: String): Long? =
        get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

    private fun JsonObject.jsonOrDefault(name: String, default: JsonElement): JsonElement = get(name) ?: default

    companion object {
        const val NAME = "cli"
    }
}
