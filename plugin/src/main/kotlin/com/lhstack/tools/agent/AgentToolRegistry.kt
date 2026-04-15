package com.lhstack.tools.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
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
import java.io.BufferedReader
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import com.lhstack.tools.listener.PluginListener
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.pluginState
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.collections.set

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
        private const val MCP_TOOL_PREFIX = "mcp_"
        private const val DEFAULT_MAX_LIST_ENTRIES = 1000
        private const val SYSTEM_PLUGIN_ID = "jtools_system"
        private const val SYSTEM_PLUGIN_NAME = "系统"
        private const val SYSTEM_PLUGIN_PATH = "<internal>"
        private const val SYSTEM_PLUGIN_TYPE = "system"
        private const val WRITE_SESSION_MAX_CHARS_PER_APPEND = 2048
        private data class WriteSessionState(
            val sessionId: String,
            val resolvedPath: File,
            val mode: String,
            val tempFile: File,
            val createdAt: Long,
            val fileExistedAtOpen: Boolean,
            var totalChars: Int = 0,
        )



        private val writeSessions = ConcurrentHashMap<String, WriteSessionState>()

        fun build(project: Project, selectedSkills: List<AgentSkillState> = emptyList()): AgentToolRegistry {
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

            val systemPluginInfo = buildSystemPluginInfo()
            if (!pluginInfoById.containsKey(systemPluginInfo.id)) {
                pluginInfoById[systemPluginInfo.id] = systemPluginInfo
                pluginInfosByName.getOrPut(systemPluginInfo.name) { mutableListOf() }.add(systemPluginInfo)
            }

            val registry = AgentToolRegistry(
                tools = tools,
                toolByName = toolByName,
                pluginTools = pluginTools,
                pluginInfoById = pluginInfoById,
                pluginInfosByName = pluginInfosByName,
            )

            registerSkillResourceTools(project, selectedSkills, systemPluginInfo, ::registerTool)
            registerJtoolsFunctions(project, registry, systemPluginInfo, ::registerTool)
            registerSkillManagementTools(project, systemPluginInfo, ::registerTool)
            registerMcpManagementTools(project, systemPluginInfo, ::registerTool)
            registerMcpTools(project, ::registerTool)

            return registry
        }

        private fun registerSkillResourceTools(
            project: Project,
            selectedSkills: List<AgentSkillState>,
            systemPluginInfo: PluginInfo,
            registerTool: (AgentTool) -> Unit,
        ) {
            if (selectedSkills.isEmpty()) {
                return
            }
            registerTool(
                AgentTool(
                    name = "jtools_skill_list_resources",
                    description = "列出当前会话已启用 skills 的资源列表",
                    parametersJson = emptyParameters(),
                    call = {
                        success(project, AgentSkillResourceSupport.listResources(selectedSkills))
                    },
                    pluginInfo = systemPluginInfo
                )
            )
            registerTool(
                AgentTool(
                    name = "jtools_skill_read_resource",
                    description = "读取当前会话已启用 skill 的资源内容，可按 skillName 和 path 读取",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "skillName": { "type": "string", "description": "技能名称，可选" },
                            "path": { "type": "string", "description": "资源路径，支持完整路径或文件名" }
                          },
                          "required": ["path"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.asString?.trim().orEmpty()
                        val skillName = payload.get("skillName")?.asString?.trim()
                        val result = AgentSkillResourceSupport.readResource(selectedSkills, skillName, path)
                        if (result.error != null) {
                            return@AgentTool error(
                                project,
                                buildString {
                                    append(result.error)
                                    if (result.suggestions.isNotEmpty()) {
                                        append("，可选资源: ")
                                        append(result.suggestions.joinToString(", "))
                                    }
                                }
                            )
                        }
                        success(
                            project,
                            mapOf(
                                "skillId" to result.skillId,
                                "skillName" to result.skillName,
                                "path" to result.path,
                                "content" to result.content
                            )
                        )
                    },
                    pluginInfo = systemPluginInfo
                )
            )
        }

        private fun registerSkillManagementTools(
            project: Project,
            systemPluginInfo: PluginInfo,
            registerTool: (AgentTool) -> Unit,
        ) {
            registerTool(
                AgentTool(
                    name = "jtools_skill_list",
                    description = "列出当前已保存的 skills 定义",
                    parametersJson = emptyParameters(),
                    call = {
                        success(project, AgentSkillFunctionTools.listSkills(project.pluginState()))
                    },
                    pluginInfo = systemPluginInfo
                )
            )
            registerTool(
                AgentTool(
                    name = "jtools_skill_delete",
                    description = "删除一个 skill，并自动从会话中移除",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "skillId": { "type": "string", "description": "技能 ID" },
                            "name": { "type": "string", "description": "技能名称" }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val identifier = payload.get("skillId")?.takeIf { !it.isJsonNull }?.asString?.trim()
                            ?: payload.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (identifier.isBlank()) {
                            return@AgentTool error(project, "skillId 或 name 不能为空")
                        }
                        functionResult(project, AgentSkillFunctionTools.deleteSkill(project.pluginState(), identifier))
                    },
                    pluginInfo = systemPluginInfo
                )
            )
            registerTool(
                AgentTool(
                    name = "jtools_skill_import_from_path",
                    description = "从本地路径导入 skills，支持单个 SKILL.md、skill 根目录和多 skills 子目录扫描",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "本地路径" }
                          },
                          "required": ["path"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.asString?.trim().orEmpty()
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        val importResult = runCatching {
                            AgentSkillImportSupport.importFromPath(path, project.pluginState().agentSkills)
                        }.getOrElse { throwable ->
                            return@AgentTool error(project, throwable.message ?: "导入失败")
                        }
                        project.pluginState().agentSkills.addAll(importResult.importedSkills)
                        success(
                            project,
                            mapOf(
                                "importedSkills" to importResult.importedSkills.map { skill ->
                                    mapOf("id" to skill.id, "name" to skill.name, "resourceCount" to skill.resources.size)
                                },
                                "skippedSkills" to importResult.skippedSkills,
                                "warnings" to importResult.warnings
                            )
                        )
                    },
                    pluginInfo = systemPluginInfo
                )
            )
        }

        private fun registerJtoolsFunctions(
            project: Project,
            registry: AgentToolRegistry,
            systemPluginInfo: PluginInfo,
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
                                "source" to when {
                                    info.id == SYSTEM_PLUGIN_ID -> "system"
                                    devInfo?.id == info.id -> "developer"
                                    else -> "installed"
                                }
                            )
                        }
                        success(project, plugins)
                    },
                    pluginInfo = systemPluginInfo
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
                    },
                    pluginInfo = systemPluginInfo
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
                    },
                    pluginInfo = systemPluginInfo
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
                    },
                    pluginInfo = systemPluginInfo
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
                    },
                    pluginInfo = systemPluginInfo
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
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_get_current_project",
                    description = "获取当前用户所在项目的信息",
                    parametersJson = emptyParameters(),
                    call = {
                        val info = mapOf(
                            "name" to project.name,
                            "locationHash" to project.locationHash,
                            "basePath" to project.basePath,
                            "moduleCount" to ModuleManager.getInstance(project).modules.size
                        )
                        success(project, info)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_list_files",
                    description = "列出指定路径下的文件(只读,结果可能截断)",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "目录路径" },
                            "depth": { "type": "integer", "description": "递归深度,默认1", "default": 1 },
                            "maxEntries": { "type": "integer", "description": "最多返回条目数,默认1000", "default": 1000 }
                          },
                          "required": ["path"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val depth = payload.get("depth")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                        val maxEntries = payload.get("maxEntries")?.takeIf { !it.isJsonNull }?.asInt
                            ?: DEFAULT_MAX_LIST_ENTRIES
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        val result = listFiles(path, depth, maxEntries)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_get_file_char_count",
                    description = "获取文件字符总数和字节大小。读取大文件前应先调用此工具，再按字符区间使用 jtools_read_file_chunk 分段读取。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "文件路径。相对路径会基于当前项目目录解析。" }
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
                        val result = getFileCharCount(project, path)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_read_file_chunk",
                    description = "按字符区间读取文件内容。用于大文件分段读取，offset 和 length 都按字符数计算，相对路径会基于当前项目目录解析。返回结果包含 nextOffset、totalChars、remainingChars、hasMore；当 hasMore=false 时表示文件已读取完毕，必须停止继续读取。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "文件路径。相对路径会基于当前项目目录解析。" },
                            "offset": { "type": "integer", "description": "起始字符偏移，从 0 开始。" },
                            "length": { "type": "integer", "description": "本次读取的字符数。" }
                          },
                          "required": ["path", "offset", "length"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val offset = payload.get("offset")?.takeIf { !it.isJsonNull }?.asInt
                            ?: return@AgentTool error(project, "offset 不能为空")
                        val length = payload.get("length")?.takeIf { !it.isJsonNull }?.asInt
                            ?: return@AgentTool error(project, "length 不能为空")
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        val result = readFileChunk(project, path, offset, length)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )
            registerTool(
                AgentTool(
                    name = "jtools_open_write_session",
                    description = "打开一个文件写入会话。调用前会弹窗让用户确认目标路径和写入模式；只有确认后才会创建会话。后续通过 sessionId 追加内容，最后再 commit。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "文件路径。调用前应先与用户确认；相对路径会基于当前项目目录解析。" },
                            "mode": {
                              "type": "string",
                              "description": "写入模式：create 表示新建，overwrite 表示覆盖，append 表示追加。",
                              "enum": ["create", "overwrite", "append"]
                            }
                          },
                          "required": ["path", "mode"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val mode = payload.get("mode")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        val result = openWriteSession(project, path, mode)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_append_write_session",
                    description = """
                        向已打开的写入会话追加一段内容。内容会先写入临时文件，不会立即修改正式文件。

                        调用要求：
                        1. 必须严格按照参数 schema 传入完整 JSON。
                        2. 必须同时提供 sessionId 和 content，不能省略，不能为 null。
                        3. content 必须是本次要追加的纯文本内容，不要包装成 JSON，不要添加解释。
                        4. 单次 content 最多 2048 个字符；如果内容超过 2048 个字符，必须拆分为多次调用，不能截断成非法内容。
                        5. 输出工具参数时，不能出现任何额外文本、注释、markdown 或不完整 JSON。
                        6. 所有字符串必须完整闭合并正确转义。
                        7. 如果当前内容无法一次写完，优先分段多次调用，保证每次调用都是合法、完整、可解析的 JSON。
                    """.trimIndent(),
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": { "type": "string", "description": "写入会话 ID。" },
                            "content": { "type": "string", "description": "本次追加的纯文本内容。必须是完整字符串，不能为 null，单次最多 2048 个字符；超过时必须拆分多次调用。","minLength": 1,"maxLength": 2048 }
                          },
                          "required": ["sessionId", "content"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val sessionId = payload.get("sessionId")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val content = payload.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (sessionId.isBlank()) {
                            return@AgentTool error(project, "sessionId 不能为空")
                        }
                        val result = appendWriteSession(sessionId, content)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_commit_write_session",
                    description = "提交写入会话，将临时文件内容一次性写入正式文件。提交成功后会自动销毁该写入会话。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": { "type": "string", "description": "写入会话 ID。" }
                          },
                          "required": ["sessionId"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val sessionId = payload.get("sessionId")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (sessionId.isBlank()) {
                            return@AgentTool error(project, "sessionId 不能为空")
                        }
                        val result = commitWriteSession(sessionId)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_discard_write_session",
                    description = "丢弃写入会话并删除临时文件，不会修改正式文件。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": { "type": "string", "description": "写入会话 ID。" }
                          },
                          "required": ["sessionId"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val sessionId = payload.get("sessionId")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (sessionId.isBlank()) {
                            return@AgentTool error(project, "sessionId 不能为空")
                        }
                        val result = discardWriteSession(sessionId)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_execute_command",
                    description = """
                        执行系统命令。执行前会弹出确认窗口，只有用户确认才会执行；用户拒绝时返回“用户拒绝执行”。未提供 workdir 时优先使用当前项目目录。

                        调用要求：
                        1. command 必须是适配当前操作系统的可直接执行命令。
                        2. Windows 下优先使用 PowerShell 风格命令与语法。
                        3. macOS 下优先使用 zsh 兼容命令与语法。
                        4. Linux 下优先使用 bash 兼容命令与语法。
                        5. 避免混用不同系统的路径格式、环境变量写法、重定向和管道语法。
                        6. 如果命令依赖 shell 特性，应优先使用当前系统默认推荐 shell 可识别的写法。
                    """.trimIndent(),
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "command": { "type": "string", "description": "要执行的命令。必须根据当前操作系统选择合适的 shell 风格：Windows 优先 PowerShell，macOS 优先 zsh，Linux 优先 bash。" },
                            "workdir": { "type": "string", "description": "执行目录，可选；相对路径会基于当前项目目录解析。" },
                            "timeoutMs": { "type": "integer", "description": "超时时间，单位毫秒，默认 30000。" }
                          },
                          "required": ["command"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val command = payload.get("command")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val workdir = payload.get("workdir")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val timeoutMs = payload.get("timeoutMs")?.takeIf { !it.isJsonNull }?.asLong ?: 30_000L
                        if (command.isBlank()) {
                            return@AgentTool error(project, "command 不能为空")
                        }
                        val result = executeCommand(project, command, workdir, timeoutMs)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_create_directory",
                    description = "创建目录(支持递归创建父目录)。调用前应先和用户确认目标目录路径；如果用户未明确给出完整目录，优先使用当前项目目录作为基准路径。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "要创建的目录路径。调用前应先与用户确认；相对路径会基于当前项目目录解析。" }
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
                        val result = createDirectory(project, path)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_mcp_list_servers",
                    description = "列出已配置的 MCP 服务器",
                    parametersJson = emptyParameters(),
                    call = {
                        val servers = McpSupport.safeServers(project.pluginState().agentMcpServers).map { server ->
                            McpSupport.ensureServerId(server)
                            mapOf(
                                "id" to server.id,
                                "name" to server.name,
                                "enabled" to server.enabled,
                                "transport" to server.transport
                            )
                        }
                        success(project, servers)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_mcp_query",
                    description = "统一查询 MCP 服务器的资源、单个资源内容、提示词、单个提示词内容或工具列表。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "serverId": { "type": "string", "description": "MCP 服务器 ID" },
                            "kind": { "type": "string", "enum": ["resources", "resource", "prompts", "prompt", "tools"], "description": "查询类型" },
                            "uri": { "type": "string", "description": "kind=resource 时需要的资源 URI" },
                            "name": { "type": "string", "description": "kind=prompt 时需要的提示词名称" },
                            "arguments": { "type": "object", "description": "kind=prompt 时可选的提示词参数" }
                          },
                          "required": ["serverId", "kind"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val serverId = payload.get("serverId")?.asString?.trim().orEmpty()
                        val kind = payload.get("kind")?.asString?.trim().orEmpty()
                        val server = findMcpServer(project, serverId) ?: return@AgentTool error(project, "未找到 MCP 服务器")
                        return@AgentTool when (kind) {
                            "resources" -> success(project, McpClientManager.safeListResources(server))
                            "tools" -> success(project, McpClientManager.safeListTools(server))
                            "prompts" -> success(project, McpClientManager.safeListPrompts(server))
                            "resource" -> {
                                val uri = payload.get("uri")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                                if (uri.isBlank()) {
                                    return@AgentTool error(project, "kind=resource 时 uri 不能为空")
                                }
                                runCatching { McpClientManager.getClient(server).readResource(uri) }
                                    .fold(
                                        onSuccess = { success(project, it) },
                                        onFailure = { error(project, "MCP 读取资源失败: ${it.message ?: "unknown"}") }
                                    )
                            }
                            "prompt" -> {
                                val name = payload.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                                if (name.isBlank()) {
                                    return@AgentTool error(project, "kind=prompt 时 name 不能为空")
                                }
                                val arguments = payload.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject
                                runCatching { McpClientManager.getClient(server).getPrompt(name, arguments) }
                                    .fold(
                                        onSuccess = { success(project, it) },
                                        onFailure = { error(project, "MCP 获取提示失败: ${it.message ?: "unknown"}") }
                                    )
                            }
                            else -> error(project, "kind 必须是 resources、resource、prompts、prompt 或 tools")
                        }
                    },
                    pluginInfo = systemPluginInfo
                )
            )
        }

        private fun registerMcpTools(project: Project, registerTool: (AgentTool) -> Unit) {
            val state = project.pluginState()
            if (!state.agentMcpEnabled) {
                return
            }
            val servers = McpSupport.safeServers(state.agentMcpServers)
                .filter { it.enabled }
                .onEach { McpSupport.ensureServerId(it) }
            McpClientManager.syncStates(servers)
            servers.forEach { server ->
                val disabled = server.disabledTools.toSet()
                val tools = McpClientManager.safeListTools(server)
                tools.forEach { tool ->
                    if (disabled.contains(tool.name)) {
                        return@forEach
                    }
                    val toolName = buildMcpToolName(server.id, tool.name)
                    val description = buildMcpToolDescription(server.name, tool)
                    registerTool(
                        AgentTool(
                            name = toolName,
                            description = description,
                            parametersJson = tool.inputSchema.toString(),
                            call = { args -> callMcpTool(project, server, tool.name, args) },
                            originName = tool.name
                        )
                    )
                }
            }
        }

        private fun registerMcpManagementTools(
            project: Project,
            systemPluginInfo: PluginInfo,
            registerTool: (AgentTool) -> Unit,
        ) {
            registerTool(
                AgentTool(
                    name = "jtools_mcp_update_server",
                    description = "按 upsert 语义新增或更新 MCP 服务器配置；同时可修改 enabled 状态。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "serverId": { "type": "string", "description": "服务器 ID" },
                            "name": { "type": "string", "description": "服务器名称；未提供 serverId 时用于定位" },
                            "enabled": { "type": "boolean" },
                            "transport": { "type": "string", "enum": ["stdio", "sse", "streamable-http"] },
                            "stdioCommand": { "type": "string" },
                            "stdioArgs": { "type": "array", "items": { "type": "string" } },
                            "stdioEnv": { "type": "object", "additionalProperties": { "type": "string" } },
                            "url": { "type": "string" },
                            "headers": { "type": "object", "additionalProperties": { "type": "string" } },
                            "authType": { "type": "string", "enum": ["none", "header", "basic", "query"] },
                            "authHeaderName": { "type": "string" },
                            "authHeaderValue": { "type": "string" },
                            "authUsername": { "type": "string" },
                            "authPassword": { "type": "string" },
                            "authQueryParam": { "type": "string" },
                            "authQueryValue": { "type": "string" },
                            "disabledTools": { "type": "array", "items": { "type": "string" } }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val result = AgentMcpFunctionTools.updateServer(
                            state = project.pluginState(),
                            input = AgentMcpUpdateServerInput(
                                serverId = payload.get("serverId")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                                name = payload.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                                enabled = payload.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean,
                                transport = payload.get("transport")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                                stdioCommand = payload.get("stdioCommand")?.takeIf { !it.isJsonNull }?.asString,
                                stdioArgs = payload.get("stdioArgs")?.let(::parseStringArray),
                                stdioEnv = payload.get("stdioEnv")?.let(::parseStringMap),
                                url = payload.get("url")?.takeIf { !it.isJsonNull }?.asString,
                                headers = payload.get("headers")?.let(::parseStringMap),
                                authType = payload.get("authType")?.takeIf { !it.isJsonNull }?.asString,
                                authHeaderName = payload.get("authHeaderName")?.takeIf { !it.isJsonNull }?.asString,
                                authHeaderValue = payload.get("authHeaderValue")?.takeIf { !it.isJsonNull }?.asString,
                                authUsername = payload.get("authUsername")?.takeIf { !it.isJsonNull }?.asString,
                                authPassword = payload.get("authPassword")?.takeIf { !it.isJsonNull }?.asString,
                                authQueryParam = payload.get("authQueryParam")?.takeIf { !it.isJsonNull }?.asString,
                                authQueryValue = payload.get("authQueryValue")?.takeIf { !it.isJsonNull }?.asString,
                                disabledTools = payload.get("disabledTools")?.let(::parseStringArray)
                            ),
                            onInvalidate = { McpClientManager.invalidate(it) }
                        )
                        functionResult(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )
            registerTool(
                AgentTool(
                    name = "jtools_mcp_delete_server",
                    description = "删除 MCP 服务器配置",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "serverId": { "type": "string" },
                            "name": { "type": "string" }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val identifier = payload.get("serverId")?.takeIf { !it.isJsonNull }?.asString?.trim()
                            ?: payload.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (identifier.isBlank()) {
                            return@AgentTool error(project, "serverId 或 name 不能为空")
                        }
                        functionResult(
                            project,
                            AgentMcpFunctionTools.deleteServer(project.pluginState(), identifier) {
                                McpClientManager.invalidate(it)
                            }
                        )
                    },
                    pluginInfo = systemPluginInfo
                )
            )
            registerTool(
                AgentTool(
                    name = "jtools_mcp_test_server",
                    description = "测试 MCP 服务器连通性",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "serverId": { "type": "string" },
                            "name": { "type": "string" }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val identifier = payload.get("serverId")?.takeIf { !it.isJsonNull }?.asString?.trim()
                            ?: payload.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (identifier.isBlank()) {
                            return@AgentTool error(project, "serverId 或 name 不能为空")
                        }
                        val result = AgentMcpFunctionTools.testServer(project.pluginState(), identifier) { server ->
                            McpClientManager.getClient(server).ping()
                            mapOf(
                                "reachable" to true,
                                "id" to server.id,
                                "name" to server.name,
                                "transport" to server.transport
                            )
                        }
                        functionResult(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )
        }

        private fun buildMcpToolName(serverId: String, toolName: String): String {
            val safeName = sanitizeName(toolName).ifBlank { "tool" }
            val serverHash = DigestUtils.md5Hex(serverId).substring(0, 6)
            val prefix = "$MCP_TOOL_PREFIX${serverHash}_"
            val maxLength = (MAX_TOOL_NAME_LENGTH - prefix.length).coerceAtLeast(1)
            return prefix + safeName.take(maxLength)
        }

        private fun buildMcpToolDescription(serverName: String, tool: McpToolDescriptorState): String {
            val base = tool.description.takeIf { it.isNotBlank() } ?: tool.name
            return "MCP[$serverName]: $base (name=${tool.name})"
        }

        private fun callMcpTool(project: Project, server: McpServerState, toolName: String, arguments: String): String {
            val payload = parseArgs(arguments)
                ?: return error(project, "参数解析失败")
            return try {
                val result = McpClientManager.getClient(server).requestTool(toolName, payload)
                success(project, result)
            } catch (e: Throwable) {
                error(project, "MCP 调用失败: ${e.message ?: "unknown"}")
            }
        }

        private fun buildSystemPluginInfo(): PluginInfo {
            return PluginInfo(
                id = SYSTEM_PLUGIN_ID,
                path = SYSTEM_PLUGIN_PATH,
                name = SYSTEM_PLUGIN_NAME,
                version = Helper.JTOOLS_VERSION.toString(),
                created = 0L,
                type = SYSTEM_PLUGIN_TYPE
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

        private fun listFiles(path: String, depth: Int, maxEntries: Int): Map<String, Any?> {
            val root = File(path)
            val limit = if (maxEntries <= 0) DEFAULT_MAX_LIST_ENTRIES else maxEntries
            if (!root.exists() || !root.isDirectory) {
                return mapOf(
                    "path" to root.absolutePath,
                    "exists" to false,
                    "isDirectory" to root.isDirectory,
                    "items" to emptyList<Map<String, Any?>>(),
                    "count" to 0,
                    "maxEntries" to limit,
                    "truncated" to false
                )
            }
            val maxDepth = if (depth <= 0) 1 else depth
            val items = mutableListOf<Map<String, Any?>>()
            val iterator = root.walkTopDown()
                .maxDepth(maxDepth)
                .filter { it != root }
                .iterator()
            var truncated = false
            while (iterator.hasNext()) {
                val file = iterator.next()
                if (items.size >= limit) {
                    truncated = true
                    break
                }
                items.add(
                    mapOf(
                        "name" to file.name,
                        "path" to file.absolutePath,
                        "isDirectory" to file.isDirectory,
                        "sizeBytes" to if (file.isFile) file.length() else 0L,
                        "lastModified" to file.lastModified()
                    )
                )
            }
            return mapOf(
                "path" to root.absolutePath,
                "exists" to true,
                "isDirectory" to true,
                "items" to items,
                "count" to items.size,
                "maxEntries" to limit,
                "truncated" to truncated
            )
        }

        private fun getFileCharCount(project: Project, path: String): Map<String, Any?> {
            val file = resolveProjectAwareFile(project, path)
            if (!file.exists() || !file.isFile) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "文件不存在")
            }
            return mapOf(
                "ok" to true,
                "path" to file.absolutePath,
                "sizeBytes" to file.length(),
                "charCount" to countFileChars(file)
            )
        }

        private fun readFileChunk(project: Project, path: String, offset: Int, length: Int): Map<String, Any?> {
            val file = resolveProjectAwareFile(project, path)
            if (!file.exists() || !file.isFile) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "文件不存在")
            }
            if (offset < 0) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "offset 不能小于 0")
            }
            if (length <= 0) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "length 必须大于 0")
            }
            val endExclusive = offset + length
            var totalChars = 0
            val content = StringBuilder(length)
            BufferedReader(Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) {
                        break
                    }
                    val chunkStart = totalChars
                    val chunkEnd = totalChars + read
                    if (chunkEnd > offset && chunkStart < endExclusive) {
                        val startInBuffer = maxOf(0, offset - chunkStart)
                        val endInBuffer = minOf(read, endExclusive - chunkStart)
                        if (endInBuffer > startInBuffer) {
                            content.append(buffer, startInBuffer, endInBuffer - startInBuffer)
                        }
                    }
                    totalChars = chunkEnd
                }
            }
            val actualLength = content.length
            val nextOffset = min(totalChars, offset + actualLength)
            val remainingChars = maxOf(0, totalChars - nextOffset)
            return mapOf(
                "ok" to true,
                "path" to file.absolutePath,
                "offset" to offset,
                "length" to actualLength,
                "requestedLength" to length,
                "nextOffset" to nextOffset,
                "endOffset" to nextOffset,
                "remainingChars" to remainingChars,
                "hasMore" to (nextOffset < totalChars),
                "totalChars" to totalChars,
                "sizeBytes" to file.length(),
                "content" to content.toString()
            )
        }

        private fun openWriteSession(project: Project, path: String, mode: String): Map<String, Any?> {
            val normalizedMode = mode.trim().lowercase()
            if (normalizedMode !in setOf("create", "overwrite", "append")) {
                return mapOf("ok" to false, "error" to "mode 必须是 create、overwrite 或 append")
            }
            val resolvedPath = resolveProjectAwareFile(project, path)
            if (resolvedPath.exists() && resolvedPath.isDirectory) {
                return mapOf("ok" to false, "path" to resolvedPath.absolutePath, "error" to "目标已存在且是目录")
            }
            if (normalizedMode == "create" && resolvedPath.exists()) {
                return mapOf("ok" to false, "path" to resolvedPath.absolutePath, "error" to "目标文件已存在，请使用 overwrite 或 append")
            }
            val approved = confirmWriteSessionOpen(project, resolvedPath, normalizedMode)
            if (!approved) {
                return mapOf(
                    "ok" to false,
                    "path" to resolvedPath.absolutePath,
                    "mode" to normalizedMode,
                    "error" to "用户拒绝写入"
                )
            }
            val tempDir = resolvedPath.parentFile?.also { Files.createDirectories(it.toPath()) }
                ?: File(System.getProperty("java.io.tmpdir"))
            val tempFile = Files.createTempFile(tempDir.toPath(), "jtools-write-session-", ".tmp").toFile().apply {
                deleteOnExit()
            }
            val sessionId = UUID.randomUUID().toString()
            val state = WriteSessionState(
                sessionId = sessionId,
                resolvedPath = resolvedPath,
                mode = normalizedMode,
                tempFile = tempFile,
                createdAt = System.currentTimeMillis(),
                fileExistedAtOpen = resolvedPath.exists(),
            )
            writeSessions[sessionId] = state
            return mapOf(
                "ok" to true,
                "sessionId" to sessionId,
                "resolvedPath" to resolvedPath.absolutePath,
                "mode" to normalizedMode,
                "fileExists" to resolvedPath.exists()
            )
        }

        private fun appendWriteSession(sessionId: String, content: String): Map<String, Any?> {
            val session = writeSessions[sessionId]
                ?: return mapOf("ok" to false, "sessionId" to sessionId, "error" to "写入会话不存在")
            if (content.isEmpty()) {
                return mapOf("ok" to false, "sessionId" to sessionId, "error" to "content 不能为空")
            }
            if (content.length > WRITE_SESSION_MAX_CHARS_PER_APPEND) {
                return mapOf(
                    "ok" to false,
                    "sessionId" to sessionId,
                    "error" to "单次最多追加 $WRITE_SESSION_MAX_CHARS_PER_APPEND 个字符",
                    "contentLength" to content.length,
                    "maxChars" to WRITE_SESSION_MAX_CHARS_PER_APPEND
                )
            }
            Files.writeString(
                session.tempFile.toPath(),
                content,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            )
            session.totalChars += content.length
            return mapOf(
                "ok" to true,
                "sessionId" to sessionId,
                "resolvedPath" to session.resolvedPath.absolutePath,
                "writtenChars" to content.length,
                "totalChars" to session.totalChars
            )
        }

        private fun commitWriteSession(sessionId: String): Map<String, Any?> {
            val session = writeSessions[sessionId]
                ?: return mapOf("ok" to false, "sessionId" to sessionId, "error" to "写入会话不存在")
            return try {
                if (session.mode == "create" && session.resolvedPath.exists() && !session.fileExistedAtOpen) {
                    return mapOf(
                        "ok" to false,
                        "sessionId" to sessionId,
                        "resolvedPath" to session.resolvedPath.absolutePath,
                        "error" to "目标文件已存在，create 模式不能覆盖，请重新打开写入会话"
                    )
                }
                session.resolvedPath.parentFile?.let { Files.createDirectories(it.toPath()) }
                when (session.mode) {
                    "append" -> {
                        Files.writeString(
                            session.resolvedPath.toPath(),
                            Files.readString(session.tempFile.toPath(), StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND
                        )
                        session.tempFile.delete()
                    }
                    else -> {
                        runCatching {
                            Files.move(
                                session.tempFile.toPath(),
                                session.resolvedPath.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE
                            )
                        }.recoverCatching {
                            Files.move(
                                session.tempFile.toPath(),
                                session.resolvedPath.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        }.getOrThrow()
                    }
                }
                writeSessions.remove(sessionId)
                mapOf(
                    "ok" to true,
                    "sessionId" to sessionId,
                    "resolvedPath" to session.resolvedPath.absolutePath,
                    "mode" to session.mode,
                    "sizeBytes" to session.resolvedPath.length(),
                    "totalChars" to session.totalChars
                )
            } catch (e: Throwable) {
                mapOf(
                    "ok" to false,
                    "sessionId" to sessionId,
                    "resolvedPath" to session.resolvedPath.absolutePath,
                    "error" to (e.message ?: "提交写入会话失败")
                )
            }
        }

        private fun discardWriteSession(sessionId: String): Map<String, Any?> {
            val session = writeSessions.remove(sessionId)
                ?: return mapOf("ok" to false, "sessionId" to sessionId, "error" to "写入会话不存在")
            session.tempFile.delete()
            return mapOf(
                "ok" to true,
                "sessionId" to sessionId,
                "resolvedPath" to session.resolvedPath.absolutePath,
                "discarded" to true
            )
        }

        private fun createDirectory(project: Project, path: String): Map<String, Any?> {
            val directory = resolveProjectAwareFile(project, path)
            val existed = directory.exists()
            if (existed && !directory.isDirectory) {
                return mapOf(
                    "ok" to false,
                    "path" to directory.absolutePath,
                    "error" to "目标已存在且不是目录"
                )
            }
            Files.createDirectories(directory.toPath())
            return mapOf(
                "ok" to true,
                "path" to directory.absolutePath,
                "existed" to existed,
                "created" to !existed
            )
        }

        private fun executeCommand(project: Project, command: String, workdir: String, timeoutMs: Long): Map<String, Any?> {
            val resolvedWorkdir = resolveProjectAwareDirectory(project, workdir)
            val approved = confirmCommandExecution(project, command, resolvedWorkdir)
            if (!approved) {
                return mapOf(
                    "ok" to false,
                    "command" to command,
                    "workdir" to resolvedWorkdir.absolutePath,
                    "error" to "用户拒绝执行"
                )
            }
            if (!resolvedWorkdir.exists() || !resolvedWorkdir.isDirectory) {
                return mapOf(
                    "ok" to false,
                    "command" to command,
                    "workdir" to resolvedWorkdir.absolutePath,
                    "error" to "工作目录不存在"
                )
            }
            val effectiveTimeout = if (timeoutMs <= 0) 30_000L else timeoutMs
            return try {
                val processBuilder = ProcessBuilder().apply {
                    if (System.getProperty("os.name").lowercase().contains("win")) {
                        command("cmd", "/c", command)
                    } else {
                        command("sh", "-c", command)
                    }
                    directory(resolvedWorkdir)
                    redirectErrorStream(false)
                }
                val process = processBuilder.start()
                val stdoutFuture = CompletableFuture.supplyAsync {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
                val stderrFuture = CompletableFuture.supplyAsync {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
                val completed = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
                if (!completed) {
                    process.destroyForcibly()
                }
                val stdout = stdoutFuture.join().trimEnd()
                val stderr = stderrFuture.join().trimEnd()
                mapOf(
                    "ok" to (completed && process.exitValue() == 0),
                    "command" to command,
                    "workdir" to resolvedWorkdir.absolutePath,
                    "exitCode" to if (completed) process.exitValue() else -1,
                    "stdout" to stdout,
                    "stderr" to stderr,
                    "timedOut" to !completed
                )
            } catch (e: Throwable) {
                mapOf(
                    "ok" to false,
                    "command" to command,
                    "workdir" to resolvedWorkdir.absolutePath,
                    "error" to (e.message ?: "命令执行失败")
                )
            }
        }

        private fun resolveProjectAwareFile(project: Project, path: String): File {
            val candidate = File(path)
            if (candidate.isAbsolute) {
                return candidate
            }
            val basePath = project.basePath?.takeIf { it.isNotBlank() } ?: System.getProperty("user.dir")
            return File(basePath, path)
        }

        private fun resolveProjectAwareDirectory(project: Project, path: String): File {
            if (path.isBlank()) {
                val basePath = project.basePath?.takeIf { it.isNotBlank() } ?: System.getProperty("user.dir")
                return File(basePath)
            }
            return resolveProjectAwareFile(project, path)
        }
        private fun confirmWriteSessionOpen(project: Project, path: File, mode: String): Boolean {
            val accepted = booleanArrayOf(false)
            ApplicationManager.getApplication().invokeAndWait {
                val message = buildString {
                    appendLine("即将打开文件写入会话：")
                    append(path.absolutePath)
                    appendLine()
                    appendLine()
                    append("模式：")
                    append(mode)
                }
                accepted[0] = Messages.showYesNoDialog(
                    project,
                    message,
                    "确认写入文件",
                    Messages.getQuestionIcon()
                ) == Messages.YES
            }
            return accepted[0]
        }
        private fun confirmCommandExecution(project: Project, command: String, workdir: File): Boolean {
            val accepted = booleanArrayOf(false)
            ApplicationManager.getApplication().invokeAndWait {
                val message = buildString {
                    appendLine("即将执行以下命令：")
                    appendLine(command)
                    appendLine()
                    append("工作目录：")
                    append(workdir.absolutePath)
                }
                accepted[0] = Messages.showYesNoDialog(
                    project,
                    message,
                    "确认执行命令",
                    Messages.getQuestionIcon()
                ) == Messages.YES
            }
            return accepted[0]
        }

        private fun countFileChars(file: File): Int {
            var total = 0
            BufferedReader(Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) {
                        break
                    }
                    total += read
                }
            }
            return total
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
            val systemPluginInfo = buildSystemPluginInfo()
            if (result.none { it["id"] == systemPluginInfo.id }) {
                result.add(
                    mapOf(
                        "id" to systemPluginInfo.id,
                        "name" to systemPluginInfo.name,
                        "version" to systemPluginInfo.version,
                        "type" to systemPluginInfo.type,
                        "source" to "system",
                        "created" to systemPluginInfo.created,
                        "path" to systemPluginInfo.path,
                        "exists" to false,
                        "sizeBytes" to 0L,
                        "implClass" to "builtin"
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

        private fun findMcpServer(project: Project, serverId: String): McpServerState? {
            if (serverId.isBlank()) {
                return null
            }
            return McpSupport.safeServers(project.pluginState().agentMcpServers).firstOrNull { server ->
                McpSupport.ensureServerId(server)
                server.id == serverId
            }
        }

        private fun parseStringArray(element: com.google.gson.JsonElement?): List<String> {
            if (element == null || element.isJsonNull || !element.isJsonArray) {
                return emptyList()
            }
            return element.asJsonArray.mapNotNull { item ->
                item.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            }
        }

        private fun parseStringMap(element: com.google.gson.JsonElement?): Map<String, String> {
            if (element == null || element.isJsonNull || !element.isJsonObject) {
                return emptyMap()
            }
            return element.asJsonObject.entrySet().mapNotNull { (key, value) ->
                key.trim().takeIf { it.isNotEmpty() }?.let { trimmedKey ->
                    trimmedKey to value.takeIf { !it.isJsonNull }?.asString.orEmpty().trim()
                }
            }.toMap()
        }


        private fun parseArgs(arguments: String): JsonObject? {
            if (arguments.isBlank()) {
                return JsonObject()
            }
            return JsonParser.parseString(arguments) as JsonObject?
        }


        private fun success(project: Project, data: Any): String {
            return project.gson.toJson(mapOf("ok" to true, "data" to data))
        }

        private fun functionResult(project: Project, result: AgentFunctionResult<*>): String {
            return if (result.ok) {
                success(project, result.data ?: emptyMap<String, Any?>())
            } else {
                error(project, result.error ?: "操作失败")
            }
        }

        private fun error(project: Project, message: String): String {
            return project.gson.toJson(mapOf("ok" to false, "error" to message))
        }
    }
}
