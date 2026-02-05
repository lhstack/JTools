package com.lhstack.tools.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.lhstack.tools.ext.gson
import com.lhstack.tools.ext.logImpl
import com.lhstack.tools.ext.openThisWindow
import com.lhstack.tools.dev.DevPluginRegistry
import com.lhstack.tools.plugins.FunctionCalling
import com.lhstack.tools.plugins.Helper
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo
import com.lhstack.tools.plugins.PluginType
import com.lhstack.tools.plugins.pluginManager
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import kotlin.math.min
import com.lhstack.tools.listener.PluginListener
import com.lhstack.tools.listener.ProjectPluginListener

data class AgentTool(
    val name: String,
    val description: String,
    val parametersJson: String,
    val call: (String) -> String,
    val pluginInfo: PluginInfo? = null,
    val originName: String? = null,
)

class AgentToolRegistry private constructor(
    val tools: List<AgentTool>,
    private val toolByName: Map<String, AgentTool>,
    private val pluginTools: Map<String, List<AgentTool>>,
    private val pluginInfoById: Map<String, PluginInfo>,
    private val pluginInfosByName: Map<String, List<PluginInfo>>,
) {

    fun findTool(name: String): AgentTool? = toolByName[name]

    fun toolsJson(): JsonArray {
        val array = JsonArray()
        tools.forEach { tool ->
            val params = safeParameters(tool.parametersJson)
            val function = JsonObject().apply {
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                add("parameters", params)
            }
            array.add(JsonObject().apply {
                addProperty("type", "function")
                add("function", function)
            })
        }
        return array
    }

    fun listPlugins(): List<PluginInfo> = pluginInfoById.values.toList()

    fun listPluginToolsById(pluginId: String): List<AgentTool> = pluginTools[pluginId] ?: emptyList()

    fun findPluginByName(pluginName: String): List<PluginInfo> = pluginInfosByName[pluginName] ?: emptyList()

    companion object {
        private const val MAX_TOOL_NAME_LENGTH = 64
        private const val TOOL_PREFIX = "plugin_"

        fun build(project: Project): AgentToolRegistry {
            val tools = mutableListOf<AgentTool>()
            val toolByName = linkedMapOf<String, AgentTool>()
            val pluginTools = linkedMapOf<String, MutableList<AgentTool>>()
            val pluginInfoById = linkedMapOf<String, PluginInfo>()
            val pluginInfosByName = linkedMapOf<String, MutableList<PluginInfo>>()
            val usedNames = hashSetOf<String>()
            val devPluginInfo = DevPluginRegistry.pluginInfo()

            fun registerTool(tool: AgentTool) {
                val uniqueName = ensureUniqueName(tool.name, usedNames)
                val finalTool = if (uniqueName == tool.name) tool else tool.copy(name = uniqueName)
                tools.add(finalTool)
                toolByName[finalTool.name] = finalTool
                finalTool.pluginInfo?.let { info ->
                    pluginTools.getOrPut(info.id) { mutableListOf() }.add(finalTool)
                }
            }

            project.pluginManager().plugins { pluginInfo, plugin ->
                pluginInfoById[pluginInfo.id] = pluginInfo
                pluginInfosByName.getOrPut(pluginInfo.name) { mutableListOf() }.add(pluginInfo)
                val functionCallings = try {
                    plugin.functionCallings(project)
                } catch (_: Throwable) {
                    null
                }
                functionCallings?.forEach { functionCalling ->
                    registerTool(
                        AgentTool(
                            name = buildToolName(pluginInfo, functionCalling),
                            description = buildToolDescription(pluginInfo, functionCalling),
                            parametersJson = functionCalling.parameters() ?: "",
                            call = { arguments -> functionCalling.call(arguments) },
                            pluginInfo = pluginInfo,
                            originName = functionCalling.name() ?: ""
                        )
                    )
                }
            }

            DevPluginRegistry.plugin()?.let { devPlugin ->
                devPluginInfo?.let { info ->
                    if (!pluginInfoById.containsKey(info.id)) {
                        pluginInfoById[info.id] = info
                        pluginInfosByName.getOrPut(info.name) { mutableListOf() }.add(info)
                    }
                    val functionCallings = try {
                        devPlugin.functionCallings(project)
                    } catch (_: Throwable) {
                        null
                    }
                    functionCallings?.forEach { functionCalling ->
                        registerTool(
                            AgentTool(
                                name = buildToolName(info, functionCalling),
                                description = buildToolDescription(info, functionCalling, true),
                                parametersJson = functionCalling.parameters() ?: "",
                                call = { arguments -> functionCalling.call(arguments) },
                                pluginInfo = info,
                                originName = functionCalling.name() ?: ""
                            )
                        )
                    }
                }
            }

            val registry = AgentToolRegistry(
                tools = tools,
                toolByName = toolByName,
                pluginTools = pluginTools,
                pluginInfoById = pluginInfoById,
                pluginInfosByName = pluginInfosByName,
            )

            registerJtoolsFunctions(project, registry, ::registerTool)

            return registry
        }

        private fun registerJtoolsFunctions(
            project: Project,
            registry: AgentToolRegistry,
            registerTool: (AgentTool) -> Unit,
        ) {
            registerTool(
                AgentTool(
                    name = "jtools_list_plugins",
                    description = "列出已安装插件信息(简要)",
                    parametersJson = emptyParameters(),
                    call = {
                        val devInfo = DevPluginRegistry.pluginInfo()
                        val plugins = registry.listPlugins().map { info ->
                            mapOf(
                                "id" to info.id,
                                "name" to info.name,
                                "version" to info.version,
                                "type" to info.type,
                                "source" to if (devInfo?.id == info.id) "developer" else "installed"
                            )
                        }
                        success(project, plugins)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_list_plugins_detail",
                    description = "列出所有插件详细信息(包含实现类、路径、大小等)",
                    parametersJson = emptyParameters(),
                    call = {
                        val details = collectPluginDetails(project)
                        success(project, details)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_get_plugin_detail",
                    description = "获取某个插件的详细信息, 需要 pluginId 或 pluginName",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "pluginId": { "type": "string", "description": "插件ID" },
                            "pluginName": { "type": "string", "description": "插件名称" }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val pluginId = payload.get("pluginId")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val pluginName = payload.get("pluginName")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val pluginInfo = when {
                            pluginId.isNotBlank() -> registry.pluginInfoById[pluginId]
                            pluginName.isNotBlank() -> {
                                val matches = registry.findPluginByName(pluginName)
                                if (matches.size == 1) {
                                    matches.first()
                                } else {
                                    return@AgentTool error(
                                        project,
                                        if (matches.isEmpty()) "未找到插件: $pluginName"
                                        else "插件名称重复, 请使用 pluginId: ${matches.map { it.id }}"
                                    )
                                }
                            }
                            else -> null
                        } ?: return@AgentTool error(project, "需要提供 pluginId 或 pluginName")

                        val detail = collectPluginDetails(project).firstOrNull { item ->
                            item["id"] == pluginInfo.id
                        } ?: return@AgentTool error(project, "插件信息未找到")

                        success(project, detail)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_install_plugin_from_file",
                    description = "从本地文件安装插件(jar/zip)",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "插件文件路径" }
                          },
                          "required": ["path"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        val result = installPluginFromPath(project, path)
                        success(project, result)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_install_plugins_from_files",
                    description = "批量安装本地插件文件或目录(支持jar/zip)",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "paths": { "type": "array", "items": { "type": "string" }, "description": "文件或目录路径列表" },
                            "recursive": { "type": "boolean", "description": "目录是否递归搜索", "default": false }
                          },
                          "required": ["paths"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val pathsElement = payload.get("paths")
                        val recursive = payload.get("recursive")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                        if (pathsElement == null || !pathsElement.isJsonArray) {
                            return@AgentTool error(project, "paths 必须是数组")
                        }
                        val paths = pathsElement.asJsonArray.mapNotNull { item ->
                            item.takeIf { it.isJsonPrimitive }?.asString?.trim()
                        }.filter { it.isNotBlank() }
                        if (paths.isEmpty()) {
                            return@AgentTool error(project, "paths 不能为空")
                        }
                        val files = collectPluginFiles(paths, recursive)
                        val results = files.map { path ->
                            installPluginFromPath(project, path)
                        }
                        success(project, mapOf("total" to files.size, "results" to results))
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_install_plugin_from_url",
                    description = "从URL下载安装插件(jar/zip)，安装完成后删除临时文件",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "url": { "type": "string", "description": "插件下载地址" },
                            "sha256": { "type": "string", "description": "可选SHA256校验值" }
                          },
                          "required": ["url"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val url = payload.get("url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val sha256 = payload.get("sha256")?.takeIf { !it.isJsonNull }?.asString?.trim()
                        if (url.isBlank()) {
                            return@AgentTool error(project, "url 不能为空")
                        }
                        val result = installPluginFromUrl(project, url, sha256)
                        success(project, result)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_uninstall_plugin",
                    description = "卸载插件(通过 pluginId 或 pluginName)",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "pluginId": { "type": "string", "description": "插件ID" },
                            "pluginName": { "type": "string", "description": "插件名称" }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val pluginId = payload.get("pluginId")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val pluginName = payload.get("pluginName")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val pluginInfo = when {
                            pluginId.isNotBlank() -> registry.pluginInfoById[pluginId]
                            pluginName.isNotBlank() -> {
                                val matches = registry.findPluginByName(pluginName)
                                if (matches.size == 1) {
                                    matches.first()
                                } else {
                                    return@AgentTool error(
                                        project,
                                        if (matches.isEmpty()) "未找到插件: $pluginName"
                                        else "插件名称重复, 请使用 pluginId: ${matches.map { it.id }}"
                                    )
                                }
                            }
                            else -> null
                        } ?: return@AgentTool error(project, "需要提供 pluginId 或 pluginName")

                        val plugin = findPluginInstance(project, pluginInfo.id)
                            ?: return@AgentTool error(project, "插件实例未找到")
                        val result = uninstallPlugin(project, pluginInfo, plugin)
                        success(project, result)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_get_system_info",
                    description = "获取系统与IDE信息",
                    parametersJson = emptyParameters(),
                    call = {
                        val info = buildSystemInfo()
                        success(project, info)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_get_env_vars",
                    description = "获取环境变量(可选前缀过滤)",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "prefix": { "type": "string", "description": "变量名前缀过滤" }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: JsonObject()
                        val prefix = payload.get("prefix")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val env = System.getenv().filter { (k, _) ->
                            prefix.isBlank() || k.startsWith(prefix)
                        }
                        success(project, env)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_get_env_var",
                    description = "获取单个环境变量",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "name": { "type": "string", "description": "变量名" }
                          },
                          "required": ["name"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val name = payload.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (name.isBlank()) {
                            return@AgentTool error(project, "name 不能为空")
                        }
                        val value = System.getenv(name)
                        success(project, mapOf("name" to name, "value" to value))
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_list_projects",
                    description = "获取当前打开项目列表",
                    parametersJson = emptyParameters(),
                    call = {
                        val projects = ProjectManager.getInstance().openProjects.map { p ->
                            mapOf(
                                "name" to p.name,
                                "locationHash" to p.locationHash,
                                "basePath" to p.basePath
                            )
                        }
                        success(project, projects)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_get_project_info",
                    description = "获取某个项目的信息(通过 locationHash 或 name)",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "projectId": { "type": "string", "description": "项目 locationHash" },
                            "projectName": { "type": "string", "description": "项目名称" }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val projectId = payload.get("projectId")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val projectName = payload.get("projectName")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val candidates = ProjectManager.getInstance().openProjects.filter { p ->
                            when {
                                projectId.isNotBlank() -> p.locationHash == projectId
                                projectName.isNotBlank() -> p.name == projectName
                                else -> false
                            }
                        }
                        if (candidates.isEmpty()) {
                            return@AgentTool error(project, "未找到项目")
                        }
                        if (candidates.size > 1) {
                            return@AgentTool error(project, "项目名称重复, 请使用 projectId")
                        }
                        val p = candidates.first()
                        val moduleCount = ModuleManager.getInstance(p).modules.size
                        val info = mapOf(
                            "name" to p.name,
                            "locationHash" to p.locationHash,
                            "basePath" to p.basePath,
                            "moduleCount" to moduleCount
                        )
                        success(project, info)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_list_files",
                    description = "列出指定路径下的文件(只读)",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "目录路径" },
                            "depth": { "type": "integer", "description": "递归深度,默认1", "default": 1 }
                          },
                          "required": ["path"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val depth = payload.get("depth")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        val result = listFiles(path, depth)
                        success(project, result)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_read_file",
                    description = "读取指定文件内容(只读,可限制大小)",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "文件路径" },
                            "maxBytes": { "type": "integer", "description": "最大读取字节数,默认100000", "default": 100000 }
                          },
                          "required": ["path"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val maxBytes = payload.get("maxBytes")?.takeIf { !it.isJsonNull }?.asInt ?: 100_000
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        val result = readFile(path, maxBytes)
                        success(project, result)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_list_plugin_functions",
                    description = "获取某个插件的 function calling 列表, 需要 pluginId 或 pluginName",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "pluginId": { "type": "string", "description": "插件ID" },
                            "pluginName": { "type": "string", "description": "插件名称" }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val pluginId = payload.get("pluginId")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val pluginName = payload.get("pluginName")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val pluginInfo = when {
                            pluginId.isNotBlank() -> registry.pluginInfoById[pluginId]
                            pluginName.isNotBlank() -> {
                                val matches = registry.findPluginByName(pluginName)
                                if (matches.size == 1) {
                                    matches.first()
                                } else {
                                    return@AgentTool error(
                                        project,
                                        if (matches.isEmpty()) "未找到插件: $pluginName"
                                        else "插件名称重复, 请使用 pluginId: ${matches.map { it.id }}"
                                    )
                                }
                            }
                            else -> null
                        } ?: return@AgentTool error(project, "需要提供 pluginId 或 pluginName")

                        val toolInfos = registry.listPluginToolsById(pluginInfo.id).map { tool ->
                            mapOf(
                                "toolName" to tool.name,
                                "name" to tool.originName,
                                "description" to tool.description,
                                "parameters" to tool.parametersJson
                            )
                        }
                        success(project, toolInfos)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_list_all_functions",
                    description = "获取所有插件的 function calling 列表",
                    parametersJson = emptyParameters(),
                    call = {
                        val result = registry.listPlugins().map { info ->
                            mapOf(
                                "plugin" to mapOf(
                                    "id" to info.id,
                                    "name" to info.name,
                                    "version" to info.version,
                                    "type" to info.type
                                ),
                                "functions" to registry.listPluginToolsById(info.id).map { tool ->
                                    mapOf(
                                        "toolName" to tool.name,
                                        "name" to tool.originName,
                                        "description" to tool.description,
                                        "parameters" to tool.parametersJson
                                    )
                                }
                            )
                        }
                        success(project, result)
                    }
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_call_plugin_function",
                    description = "调用某个插件的 function calling, 推荐传 toolName",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "toolName": { "type": "string", "description": "函数唯一名称(推荐)" },
                            "pluginId": { "type": "string", "description": "插件ID" },
                            "functionName": { "type": "string", "description": "插件函数原始名称" },
                            "arguments": { "type": "object", "description": "函数参数", "additionalProperties": true },
                            "argumentsJson": { "type": "string", "description": "函数参数JSON字符串" }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val toolName = payload.get("toolName")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val pluginId = payload.get("pluginId")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val functionName = payload.get("functionName")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val argumentsJson = payload.get("argumentsJson")?.takeIf { !it.isJsonNull }?.asString
                        val argumentsObject = payload.get("arguments")?.takeIf { !it.isJsonNull }

                        val arguments = when {
                            !argumentsJson.isNullOrBlank() -> argumentsJson
                            argumentsObject != null -> project.gson.toJson(argumentsObject)
                            else -> "{}"
                        }

                        val tool = when {
                            toolName.isNotBlank() -> registry.findTool(toolName)
                            pluginId.isNotBlank() && functionName.isNotBlank() ->
                                registry.listPluginToolsById(pluginId).firstOrNull { it.originName == functionName }
                            else -> null
                        } ?: return@AgentTool error(project, "未找到可调用的函数")

                        if (tool.pluginInfo == null) {
                            return@AgentTool error(project, "该函数不是插件函数")
                        }

                        return@AgentTool try {
                            success(project, tool.call(arguments))
                        } catch (e: Throwable) {
                            error(project, "调用失败: ${e.message ?: "unknown"}")
                        }
                    }
                )
            )
        }

        private fun buildToolDescription(
            pluginInfo: PluginInfo,
            functionCalling: FunctionCalling,
            isDeveloper: Boolean = false,
        ): String {
            val base = functionCalling.description()?.ifBlank { functionCalling.name().orEmpty() }
                ?: functionCalling.name().orEmpty()
            val source = if (isDeveloper) "developer" else "installed"
            return "$base (plugin=${pluginInfo.name}, id=${pluginInfo.id}, source=$source)"
        }

        private fun buildToolName(pluginInfo: PluginInfo, functionCalling: FunctionCalling): String {
            val safeFunctionName = sanitizeName(functionCalling.name().orEmpty()).ifBlank { "fn" }
            val pluginHash = DigestUtils.md5Hex(pluginInfo.id).substring(0, 8)
            val prefix = "$TOOL_PREFIX${pluginHash}_"
            val maxFunctionLength = (MAX_TOOL_NAME_LENGTH - prefix.length).coerceAtLeast(1)
            val trimmedFunction = safeFunctionName.take(maxFunctionLength)
            return prefix + trimmedFunction
        }

        private fun sanitizeName(value: String): String {
            return value.replace(Regex("[^A-Za-z0-9_-]"), "_")
        }

        private fun ensureUniqueName(base: String, usedNames: MutableSet<String>): String {
            if (usedNames.add(base)) {
                return base
            }
            var index = 2
            while (true) {
                val suffix = "_$index"
                val trimmedBase = base.take(min(base.length, MAX_TOOL_NAME_LENGTH - suffix.length))
                val candidate = trimmedBase + suffix
                if (usedNames.add(candidate)) {
                    return candidate
                }
                index++
            }
        }

        private fun safeParameters(parametersJson: String): JsonObject {
            if (parametersJson.isBlank()) {
                return JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject())
                }
            }
            return try {
                val element = JsonParser.parseString(parametersJson)
                if (element.isJsonObject) element.asJsonObject else JsonObject()
            } catch (_: Throwable) {
                JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject())
                }
            }
        }

        private fun emptyParameters(): String {
            return """{"type":"object","properties":{}}"""
        }

        private fun collectPluginFiles(paths: List<String>, recursive: Boolean): List<String> {
            val results = mutableListOf<String>()
            paths.forEach { path ->
                val file = File(path)
                if (file.isFile) {
                    if (isPluginArchive(file)) {
                        results.add(file.absolutePath)
                    }
                } else if (file.isDirectory) {
                    val files = if (recursive) {
                        file.walkTopDown().filter { it.isFile }.toList()
                    } else {
                        file.listFiles()?.filter { it.isFile } ?: emptyList()
                    }
                    files.filter { isPluginArchive(it) }.forEach { results.add(it.absolutePath) }
                }
            }
            return results.distinct()
        }

        private fun isPluginArchive(file: File): Boolean {
            val ext = file.extension.lowercase()
            return ext == "jar" || ext == "zip"
        }

        private fun installPluginFromUrl(project: Project, url: String, sha256: String?): Map<String, Any?> {
            val suffix = extractArchiveSuffix(url)
            val tempFile = Files.createTempFile("jtools-plugin-", suffix).toFile()
            return try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build()
                val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofFile(tempFile.toPath()))
                if (response.statusCode() !in 200..299) {
                    return mapOf("ok" to false, "error" to "下载失败: ${response.statusCode()}")
                }
                if (!sha256.isNullOrBlank()) {
                    val actual = DigestUtils.sha256Hex(tempFile.readBytes())
                    if (!sha256.equals(actual, ignoreCase = true)) {
                        return mapOf("ok" to false, "error" to "SHA256 校验失败")
                    }
                }
                installPluginFromPath(project, tempFile.absolutePath)
            } catch (e: Throwable) {
                mapOf("ok" to false, "error" to (e.message ?: "下载失败"))
            } finally {
                tempFile.delete()
            }
        }

        private fun extractArchiveSuffix(url: String): String {
            val lower = url.lowercase()
            return when {
                lower.endsWith(".jar") -> ".jar"
                lower.endsWith(".zip") -> ".zip"
                else -> ".jar"
            }
        }

        private fun installPluginFromPath(project: Project, path: String): Map<String, Any?> {
            var response: Map<String, Any?> = mapOf("ok" to false, "error" to "安装失败")
            project.pluginManager().install(path) { plugin, pluginInfo, error ->
                if (error != null) {
                    response = mapOf("ok" to false, "error" to error)
                    return@install
                }
                if (plugin == null || pluginInfo == null) {
                    response = mapOf("ok" to false, "error" to "插件安装失败")
                    return@install
                }
                if (plugin.installRestart()) {
                    ApplicationManager.getApplication().restart()
                    response = mapOf("ok" to true, "pluginId" to pluginInfo.id, "restartRequired" to true)
                    return@install
                }
                ApplicationManager.getApplication().invokeLater {
                    ProjectManager.getInstance().openProjects.forEach { openProject ->
                        try {
                            plugin.openProject(openProject, pluginInfo.logImpl(openProject)) {
                                if (plugin.pluginType() != PluginType.JAVA_NON_UI) {
                                    openProject.openThisWindow()
                                    openProject.messageBus.syncPublisher(ProjectPluginListener.TOPIC)
                                        .openPanel(pluginInfo, plugin)
                                }
                            }
                        } catch (_: Throwable) {
                            // ignore per-project open errors
                        }
                    }
                }
                ApplicationManager.getApplication().messageBus.syncPublisher(PluginListener.TOPIC)
                    .install(plugin, pluginInfo)
                response = mapOf(
                    "ok" to true,
                    "pluginId" to pluginInfo.id,
                    "name" to pluginInfo.name,
                    "version" to pluginInfo.version,
                    "type" to pluginInfo.type
                )
            }
            return response
        }

        private fun buildSystemInfo(): Map<String, Any?> {
            val ideInfo = Helper.getIdeInfo()
            return mapOf(
                "osName" to System.getProperty("os.name"),
                "osVersion" to System.getProperty("os.version"),
                "osArch" to System.getProperty("os.arch"),
                "javaVersion" to System.getProperty("java.version"),
                "javaVendor" to System.getProperty("java.vendor"),
                "javaHome" to System.getProperty("java.home"),
                "jtoolsVersion" to Helper.JTOOLS_VERSION,
                "ide" to mapOf(
                    "apiVersion" to ideInfo.apiVersion,
                    "fullVersion" to ideInfo.fullVersion,
                    "majorVersion" to ideInfo.majorVersion,
                    "minorVersion" to ideInfo.minorVersion,
                    "buildBaselineVersion" to ideInfo.buildBaselineVersion,
                    "buildDate" to ideInfo.buildDate,
                    "versionName" to ideInfo.versionName,
                    "fullApplicationName" to ideInfo.fullApplicationName
                )
            )
        }

        private fun listFiles(path: String, depth: Int): List<Map<String, Any?>> {
            val root = File(path)
            if (!root.exists() || !root.isDirectory) {
                return emptyList()
            }
            val maxDepth = if (depth <= 0) 1 else depth
            return root.walkTopDown()
                .maxDepth(maxDepth)
                .filter { it != root }
                .map { file ->
                    mapOf(
                        "name" to file.name,
                        "path" to file.absolutePath,
                        "isDirectory" to file.isDirectory,
                        "sizeBytes" to if (file.isFile) file.length() else 0L,
                        "lastModified" to file.lastModified()
                    )
                }.toList()
        }

        private fun readFile(path: String, maxBytes: Int): Map<String, Any?> {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                return mapOf("ok" to false, "error" to "文件不存在")
            }
            val limit = if (maxBytes <= 0) 100_000 else maxBytes
            val bytes = file.inputStream().use { it.readNBytes(limit + 1) }
            val truncated = bytes.size > limit
            val contentBytes = if (truncated) bytes.copyOf(limit) else bytes
            val content = String(contentBytes, StandardCharsets.UTF_8)
            return mapOf(
                "ok" to true,
                "path" to file.absolutePath,
                "sizeBytes" to file.length(),
                "truncated" to truncated,
                "content" to content
            )
        }

        private fun collectPluginDetails(project: Project): List<Map<String, Any?>> {
            val devInfo = DevPluginRegistry.pluginInfo()
            val devPlugin = DevPluginRegistry.plugin()
            val result = mutableListOf<Map<String, Any?>>()
            project.pluginManager().plugins { pluginInfo, plugin ->
                val path = pluginInfo.path
                val file = File(path)
                val sizeBytes = if (file.exists()) {
                    try {
                        if (file.isDirectory) {
                            FileUtils.sizeOfDirectory(file)
                        } else {
                            FileUtils.sizeOf(file)
                        }
                    } catch (_: Throwable) {
                        0L
                    }
                } else {
                    0L
                }
                result.add(
                    mapOf(
                        "id" to pluginInfo.id,
                        "name" to pluginInfo.name,
                        "version" to pluginInfo.version,
                        "type" to pluginInfo.type,
                        "source" to if (devInfo?.id == pluginInfo.id) "developer" else "installed",
                        "created" to pluginInfo.created,
                        "path" to path,
                        "exists" to file.exists(),
                        "sizeBytes" to sizeBytes,
                        "implClass" to plugin.javaClass.name
                    )
                )
            }
            if (devInfo != null && devPlugin != null && result.none { it["id"] == devInfo.id }) {
                val path = devInfo.path
                val file = File(path)
                val sizeBytes = if (file.exists()) {
                    try {
                        if (file.isDirectory) {
                            FileUtils.sizeOfDirectory(file)
                        } else {
                            FileUtils.sizeOf(file)
                        }
                    } catch (_: Throwable) {
                        0L
                    }
                } else {
                    0L
                }
                result.add(
                    mapOf(
                        "id" to devInfo.id,
                        "name" to devInfo.name,
                        "version" to devInfo.version,
                        "type" to devInfo.type,
                        "source" to "developer",
                        "created" to devInfo.created,
                        "path" to path,
                        "exists" to file.exists(),
                        "sizeBytes" to sizeBytes,
                        "implClass" to devPlugin.javaClass.name
                    )
                )
            }
            return result
        }

        private fun uninstallPlugin(project: Project, pluginInfo: PluginInfo, plugin: IPlugin): Map<String, Any?> {
            val action = Runnable {
                ApplicationManager.getApplication().messageBus.syncPublisher(PluginListener.TOPIC)
                    .uninstall(plugin, pluginInfo)
                ProjectManager.getInstance().openProjects.forEach { openProject ->
                    try {
                        plugin.closeProject(openProject)
                    } catch (_: Throwable) {
                        // ignore close errors
                    }
                }
                try {
                    plugin.appClose()
                } catch (_: Throwable) {
                    // ignore close errors
                }
                try {
                    plugin.unInstall()
                } catch (_: Throwable) {
                    // ignore uninstall errors
                }
                project.pluginManager().uninstall(pluginInfo)
            }
            if (ApplicationManager.getApplication().isDispatchThread) {
                action.run()
            } else {
                ApplicationManager.getApplication().invokeAndWait(action)
            }
            return mapOf(
                "ok" to true,
                "pluginId" to pluginInfo.id,
                "name" to pluginInfo.name,
                "version" to pluginInfo.version
            )
        }

        private fun findPluginInstance(project: Project, pluginId: String): IPlugin? {
            return project.pluginManager().pluginInstances.entries.firstOrNull { it.key.id == pluginId }?.value
        }

        private fun parseArgs(arguments: String): JsonObject? {
            if (arguments.isBlank()) {
                return JsonObject()
            }
            return try {
                val element = JsonParser.parseString(arguments)
                if (element.isJsonObject) element.asJsonObject else null
            } catch (_: Throwable) {
                null
            }
        }

        private fun success(project: Project, data: Any): String {
            return project.gson.toJson(mapOf("ok" to true, "data" to data))
        }

        private fun error(project: Project, message: String): String {
            return project.gson.toJson(mapOf("ok" to false, "error" to message))
        }
    }
}
