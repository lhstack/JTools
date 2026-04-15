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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.math.min
import com.lhstack.tools.listener.PluginListener
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.pluginState

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
        private const val WRITE_SESSION_MAX_CHARS_PER_APPEND = 2000

        private data class WriteSessionState(
            val sessionId: String,
            val resolvedPath: File,
            val mode: String,
            val tempFile: File,
            val createdAt: Long,
            val fileExistedAtOpen: Boolean,
            var totalChars: Int = 0,
        )

        private data class FileContentPatch(
            val oldText: String,
            val newText: String,
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
                    name = "jtools_skill_add",
                    description = "新增一个手动 skill 定义",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "name": { "type": "string", "description": "技能名称" },
                            "description": { "type": "string", "description": "技能描述" },
                            "skillContent": { "type": "string", "description": "Skill 正文内容" },
                            "enabledByDefault": { "type": "boolean", "description": "是否默认启用" },
                            "resources": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "path": { "type": "string" },
                                  "content": { "type": "string" }
                                },
                                "required": ["path", "content"]
                              }
                            }
                          },
                          "required": ["name", "description", "skillContent"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val result = AgentSkillFunctionTools.addSkill(
                            project.pluginState(),
                            AgentSkillAddInput(
                                name = payload.get("name")?.asString?.trim().orEmpty(),
                                description = payload.get("description")?.asString?.trim().orEmpty(),
                                skillContent = payload.get("skillContent")?.asString.orEmpty(),
                                enabledByDefault = payload.get("enabledByDefault")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                                resources = parseSkillResources(payload.get("resources"))
                            )
                        )
                        functionResult(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )
            registerTool(
                AgentTool(
                    name = "jtools_skill_update",
                    description = "按 patch 语义更新 skill 定义",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "skillId": { "type": "string", "description": "技能 ID" },
                            "name": { "type": "string", "description": "技能名称；未提供 skillId 时用于定位" },
                            "description": { "type": "string", "description": "新的技能描述" },
                            "skillContent": { "type": "string", "description": "新的 Skill 正文内容" },
                            "enabledByDefault": { "type": "boolean", "description": "是否默认启用" },
                            "resources": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "path": { "type": "string" },
                                  "content": { "type": "string" }
                                },
                                "required": ["path", "content"]
                              }
                            }
                          }
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val result = AgentSkillFunctionTools.updateSkill(
                            project.pluginState(),
                            AgentSkillUpdateInput(
                                skillId = payload.get("skillId")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                                name = payload.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                                description = payload.get("description")?.takeIf { !it.isJsonNull }?.asString,
                                skillContent = payload.get("skillContent")?.takeIf { !it.isJsonNull }?.asString,
                                enabledByDefault = payload.get("enabledByDefault")?.takeIf { !it.isJsonNull }?.asBoolean,
                                resources = payload.get("resources")?.let(::parseSkillResources)
                            )
                        )
                        functionResult(project, result)
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
                    name = "jtools_skill_set_enabled",
                    description = "启用或禁用 skill，可作用于默认状态或当前项目会话",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "skillId": { "type": "string", "description": "技能 ID" },
                            "name": { "type": "string", "description": "技能名称" },
                            "scope": { "type": "string", "enum": ["default", "session"], "description": "作用范围" },
                            "enabled": { "type": "boolean", "description": "是否启用" },
                            "sessionId": { "type": "string", "description": "可选会话 ID" }
                          },
                          "required": ["enabled"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val scope = AgentSkillEnableScope.fromId(payload.get("scope")?.takeIf { !it.isJsonNull }?.asString)
                        val enabled = payload.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean
                            ?: return@AgentTool error(project, "enabled 不能为空")
                        val result = AgentSkillFunctionTools.setEnabled(
                            state = project.pluginState(),
                            scope = scope,
                            skillId = payload.get("skillId")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                            name = payload.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                            enabled = enabled,
                            projectKey = resolveProjectKey(project),
                            sessionId = payload.get("sessionId")?.takeIf { !it.isJsonNull }?.asString?.trim()
                        )
                        functionResult(project, result)
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
                    name = "jtools_list_plugins_detail",
                    description = "列出所有插件详细信息(包含实现类、路径、大小等)",
                    parametersJson = emptyParameters(),
                    call = {
                        val details = collectPluginDetails(project)
                        success(project, details)
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
                    },
                    pluginInfo = systemPluginInfo
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
                    name = "jtools_read_directory",
                    description = "读取指定目录内容(只读,结果可能截断)",
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
                    name = "jtools_find_file_snippet",
                    description = "在文件中精确查找指定文本片段，返回字符偏移和前后文。适合在调用 jtools_apply_file_patch 前先确认 oldText 是否唯一命中。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "文件路径。相对路径会基于当前项目目录解析。" },
                            "query": { "type": "string", "description": "要精确查找的文本片段。" },
                            "maxMatches": { "type": "integer", "description": "最多返回多少个命中结果，默认 20。", "default": 20 },
                            "contextChars": { "type": "integer", "description": "每个命中前后各返回多少个字符作为上下文，默认 120。", "default": 120 }
                          },
                          "required": ["path", "query"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val query = payload.get("query")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val maxMatches = payload.get("maxMatches")?.takeIf { !it.isJsonNull }?.asInt ?: 20
                        val contextChars = payload.get("contextChars")?.takeIf { !it.isJsonNull }?.asInt ?: 120
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        if (query.isEmpty()) {
                            return@AgentTool error(project, "query 不能为空")
                        }
                        val result = findFileSnippet(project, path, query, maxMatches, contextChars)
                        success(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_apply_file_patch",
                    description = "按 patch 方式局部编辑文件。每个 patch 使用 oldText/newText 表示一个编辑块，并按顺序在最新内容上继续匹配；每个 oldText 必须唯一命中，否则失败。全部 patch 校验通过后一次性写回，并在执行前弹窗确认。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "文件路径。相对路径会基于当前项目目录解析。" },
                            "patches": {
                              "type": "array",
                              "description": "按顺序应用的 patch 列表。每个 oldText 都会在上一个 patch 应用后的最新内容中继续精确匹配。",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "oldText": { "type": "string", "description": "当前文件中应存在的原始文本片段，必须唯一命中。" },
                                  "newText": { "type": "string", "description": "替换后的文本片段。" }
                                },
                                "required": ["oldText", "newText"],
                                "additionalProperties": false
                              }
                            }
                          },
                          "required": ["path", "patches"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        val patches = parseFileContentPatches(payload.get("patches"))
                            ?: return@AgentTool error(project, "patches 必须是非空数组，且每个 patch 都必须包含 oldText 和 newText")
                        if (patches.isEmpty()) {
                            return@AgentTool error(project, "patches 不能为空")
                        }
                        val result = applyFilePatch(project, path, patches)
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
                        4. 单次 content 最多 512 个字符；如果内容超过 512 个字符，必须拆分为多次调用，不能截断成非法内容。
                        5. 输出工具参数时，不能出现任何额外文本、注释、markdown 或不完整 JSON。
                        6. 所有字符串必须完整闭合并正确转义。
                        7. 如果当前内容无法一次写完，优先分段多次调用，保证每次调用都是合法、完整、可解析的 JSON。
                    """.trimIndent(),
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "sessionId": { "type": "string", "description": "写入会话 ID。" },
                            "content": { "type": "string", "description": "本次追加的纯文本内容。必须是完整字符串，不能为 null，单次最多 512 个字符；超过时必须拆分多次调用。","minLength": 1,"maxLength": 512 }
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
                    description = "执行脚本命令。执行前会弹出确认窗口，只有用户确认才会执行；用户拒绝时返回“用户拒绝执行”。未提供 workdir 时优先使用当前项目目录。",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "command": { "type": "string", "description": "要执行的命令。" },
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
                    name = "jtools_mcp_list_resources",
                    description = "列出指定 MCP 服务器的资源列表",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "serverId": { "type": "string", "description": "MCP 服务器 ID" }
                          },
                          "required": ["serverId"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val serverId = payload.get("serverId")?.asString?.trim().orEmpty()
                        val server = findMcpServer(project, serverId) ?: return@AgentTool error(project, "未找到 MCP 服务器")
                        val resources = McpClientManager.safeListResources(server)
                        success(project, resources)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_mcp_read_resource",
                    description = "读取指定 MCP 资源内容",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "serverId": { "type": "string", "description": "MCP 服务器 ID" },
                            "uri": { "type": "string", "description": "资源 URI" }
                          },
                          "required": ["serverId", "uri"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val serverId = payload.get("serverId")?.asString?.trim().orEmpty()
                        val uri = payload.get("uri")?.asString?.trim().orEmpty()
                        val server = findMcpServer(project, serverId) ?: return@AgentTool error(project, "未找到 MCP 服务器")
                        if (uri.isBlank()) {
                            return@AgentTool error(project, "uri 不能为空")
                        }
                        return@AgentTool try {
                            val result = McpClientManager.getClient(server).readResource(uri)
                            success(project, result)
                        } catch (e: Throwable) {
                            error(project, "MCP 读取资源失败: ${e.message ?: "unknown"}")
                        }
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_mcp_list_prompts",
                    description = "列出指定 MCP 服务器的提示模板",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "serverId": { "type": "string", "description": "MCP 服务器 ID" }
                          },
                          "required": ["serverId"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val serverId = payload.get("serverId")?.asString?.trim().orEmpty()
                        val server = findMcpServer(project, serverId) ?: return@AgentTool error(project, "未找到 MCP 服务器")
                        val prompts = McpClientManager.safeListPrompts(server)
                        success(project, prompts)
                    },
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "jtools_mcp_get_prompt",
                    description = "获取 MCP 提示模板内容",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "serverId": { "type": "string", "description": "MCP 服务器 ID" },
                            "name": { "type": "string", "description": "提示模板名称" },
                            "arguments": { "type": "object", "description": "提示模板参数" }
                          },
                          "required": ["serverId", "name"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val serverId = payload.get("serverId")?.asString?.trim().orEmpty()
                        val name = payload.get("name")?.asString?.trim().orEmpty()
                        val server = findMcpServer(project, serverId) ?: return@AgentTool error(project, "未找到 MCP 服务器")
                        if (name.isBlank()) {
                            return@AgentTool error(project, "name 不能为空")
                        }
                        val arguments = payload.getAsJsonObject("arguments")
                        return@AgentTool try {
                            val result = McpClientManager.getClient(server).getPrompt(name, arguments)
                            success(project, result)
                        } catch (e: Throwable) {
                            error(project, "MCP 获取提示失败: ${e.message ?: "unknown"}")
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
                    name = "jtools_mcp_add_server",
                    description = "新增 MCP 服务器配置",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "name": { "type": "string", "description": "服务器名称" },
                            "enabled": { "type": "boolean", "description": "是否启用" },
                            "transport": { "type": "string", "enum": ["stdio", "sse", "streamable-http"], "description": "传输方式" },
                            "stdioCommand": { "type": "string", "description": "stdio 命令" },
                            "stdioArgs": { "type": "array", "items": { "type": "string" } },
                            "stdioEnv": { "type": "object", "additionalProperties": { "type": "string" } },
                            "url": { "type": "string", "description": "HTTP/SSE 地址" },
                            "headers": { "type": "object", "additionalProperties": { "type": "string" } },
                            "authType": { "type": "string", "enum": ["none", "header", "basic", "query"] },
                            "authHeaderName": { "type": "string" },
                            "authHeaderValue": { "type": "string" },
                            "authUsername": { "type": "string" },
                            "authPassword": { "type": "string" },
                            "authQueryParam": { "type": "string" },
                            "authQueryValue": { "type": "string" },
                            "disabledTools": { "type": "array", "items": { "type": "string" } }
                          },
                          "required": ["name", "transport"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val result = AgentMcpFunctionTools.addServer(
                            state = project.pluginState(),
                            input = AgentMcpAddServerInput(
                                name = payload.get("name")?.asString?.trim().orEmpty(),
                                enabled = payload.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                                transport = payload.get("transport")?.asString?.trim().orEmpty(),
                                stdioCommand = payload.get("stdioCommand")?.takeIf { !it.isJsonNull }?.asString,
                                stdioArgs = parseStringArray(payload.get("stdioArgs")),
                                stdioEnv = parseStringMap(payload.get("stdioEnv")),
                                url = payload.get("url")?.takeIf { !it.isJsonNull }?.asString,
                                headers = parseStringMap(payload.get("headers")),
                                authType = payload.get("authType")?.takeIf { !it.isJsonNull }?.asString,
                                authHeaderName = payload.get("authHeaderName")?.takeIf { !it.isJsonNull }?.asString,
                                authHeaderValue = payload.get("authHeaderValue")?.takeIf { !it.isJsonNull }?.asString,
                                authUsername = payload.get("authUsername")?.takeIf { !it.isJsonNull }?.asString,
                                authPassword = payload.get("authPassword")?.takeIf { !it.isJsonNull }?.asString,
                                authQueryParam = payload.get("authQueryParam")?.takeIf { !it.isJsonNull }?.asString,
                                authQueryValue = payload.get("authQueryValue")?.takeIf { !it.isJsonNull }?.asString,
                                disabledTools = parseStringArray(payload.get("disabledTools"))
                            )
                        )
                        functionResult(project, result)
                    },
                    pluginInfo = systemPluginInfo
                )
            )
            registerTool(
                AgentTool(
                    name = "jtools_mcp_update_server",
                    description = "按 patch 语义更新 MCP 服务器配置",
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
                    name = "jtools_mcp_set_enabled",
                    description = "启用或禁用 MCP 服务器",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "serverId": { "type": "string" },
                            "name": { "type": "string" },
                            "enabled": { "type": "boolean" }
                          },
                          "required": ["enabled"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val identifier = payload.get("serverId")?.takeIf { !it.isJsonNull }?.asString?.trim()
                            ?: payload.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (identifier.isBlank()) {
                            return@AgentTool error(project, "serverId 或 name 不能为空")
                        }
                        val enabled = payload.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean
                            ?: return@AgentTool error(project, "enabled 不能为空")
                        functionResult(
                            project,
                            AgentMcpFunctionTools.setEnabled(project.pluginState(), identifier, enabled) {
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

        private fun findFileSnippet(project: Project, path: String, query: String, maxMatches: Int, contextChars: Int): Map<String, Any?> {
            val file = resolveProjectAwareFile(project, path)
            if (!file.exists() || !file.isFile) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "文件不存在")
            }
            if (maxMatches <= 0) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "maxMatches 必须大于 0")
            }
            if (contextChars < 0) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "contextChars 不能小于 0")
            }
            val content = Files.readString(file.toPath(), StandardCharsets.UTF_8)
            val matches = mutableListOf<Map<String, Any?>>()
            var totalMatches = 0
            var searchIndex = 0
            while (true) {
                val matchIndex = content.indexOf(query, searchIndex)
                if (matchIndex < 0) {
                    break
                }
                totalMatches++
                if (matches.size < maxMatches) {
                    val start = matchIndex
                    val end = matchIndex + query.length
                    val beforeStart = maxOf(0, start - contextChars)
                    val afterEnd = min(content.length, end + contextChars)
                    matches.add(
                        mapOf(
                            "start" to start,
                            "end" to end,
                            "beforeContext" to content.substring(beforeStart, start),
                            "matchText" to content.substring(start, end),
                            "afterContext" to content.substring(end, afterEnd)
                        )
                    )
                }
                searchIndex = matchIndex + query.length
            }
            return mapOf(
                "ok" to true,
                "path" to file.absolutePath,
                "queryLength" to query.length,
                "totalMatches" to totalMatches,
                "returnedMatches" to matches.size,
                "truncated" to (totalMatches > matches.size),
                "maxMatches" to maxMatches,
                "contextChars" to contextChars,
                "matches" to matches
            )
        }

        private fun applyFilePatch(
            project: Project,
            path: String,
            patches: List<FileContentPatch>,
        ): Map<String, Any?> {
            val file = resolveProjectAwareFile(project, path)
            if (!file.exists() || !file.isFile) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "文件不存在")
            }
            if (patches.isEmpty()) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "patches 不能为空")
            }
            val originalContent = Files.readString(file.toPath(), StandardCharsets.UTF_8)
            var updatedContent = originalContent
            val patchSummaries = mutableListOf<Map<String, Any?>>()
            patches.forEachIndexed { index, patch ->
                if (patch.oldText.isEmpty()) {
                    return mapOf(
                        "ok" to false,
                        "path" to file.absolutePath,
                        "patchIndex" to index,
                        "error" to "第 ${index + 1} 个 patch 的 oldText 不能为空"
                    )
                }
                val matches = countOccurrences(updatedContent, patch.oldText)
                if (matches <= 0) {
                    return mapOf(
                        "ok" to false,
                        "path" to file.absolutePath,
                        "patchIndex" to index,
                        "error" to "第 ${index + 1} 个 patch 未命中任何内容"
                    )
                }
                if (matches > 1) {
                    return mapOf(
                        "ok" to false,
                        "path" to file.absolutePath,
                        "patchIndex" to index,
                        "matches" to matches,
                        "error" to "第 ${index + 1} 个 patch 命中多处内容，要求 oldText 唯一匹配"
                    )
                }
                val matchIndex = updatedContent.indexOf(patch.oldText)
                updatedContent = buildString(updatedContent.length - patch.oldText.length + patch.newText.length) {
                    append(updatedContent, 0, matchIndex)
                    append(patch.newText)
                    append(updatedContent, matchIndex + patch.oldText.length, updatedContent.length)
                }
                patchSummaries.add(
                    mapOf(
                        "index" to (index + 1),
                        "oldLength" to patch.oldText.length,
                        "newLength" to patch.newText.length,
                        "oldPreview" to previewContent(patch.oldText),
                        "newPreview" to previewContent(patch.newText)
                    )
                )
            }
            if (updatedContent == originalContent) {
                return mapOf("ok" to false, "path" to file.absolutePath, "error" to "patch 未产生任何实际改动")
            }
            val approved = confirmApplyFilePatch(project, file, patchSummaries)
            if (!approved) {
                return mapOf(
                    "ok" to false,
                    "path" to file.absolutePath,
                    "patchCount" to patches.size,
                    "error" to "用户拒绝写入"
                )
            }
            Files.writeString(
                file.toPath(),
                updatedContent,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE
            )
            return mapOf(
                "ok" to true,
                "path" to file.absolutePath,
                "patchCount" to patches.size,
                "patches" to patchSummaries,
                "sizeBytes" to file.length()
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

        private fun confirmApplyFilePatch(
            project: Project,
            path: File,
            patchSummaries: List<Map<String, Any?>>,
        ): Boolean {
            val accepted = booleanArrayOf(false)
            ApplicationManager.getApplication().invokeAndWait {
                val message = buildString {
                    appendLine("即将按 patch 编辑文件：")
                    append(path.absolutePath)
                    appendLine()
                    appendLine()
                    append("patch 数量：")
                    appendLine(patchSummaries.size.toString())
                    appendLine()
                    patchSummaries.take(3).forEach { patch ->
                        append("Patch #")
                        appendLine(patch["index"].toString())
                        append("oldText：")
                        appendLine(patch["oldPreview"].toString())
                        append("newText：")
                        appendLine(patch["newPreview"].toString())
                        appendLine()
                    }
                    if (patchSummaries.size > 3) {
                        append("其余 patch 数量：")
                        append(patchSummaries.size - 3)
                    }
                }
                accepted[0] = Messages.showYesNoDialog(
                    project,
                    message,
                    "确认应用文件 Patch",
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

        private fun countOccurrences(content: String, target: String): Int {
            var count = 0
            var index = 0
            while (true) {
                index = content.indexOf(target, index)
                if (index < 0) {
                    break
                }
                count++
                index += target.length
            }
            return count
        }

        private fun parseFileContentPatches(element: com.google.gson.JsonElement?): List<FileContentPatch>? {
            if (element == null || element.isJsonNull || !element.isJsonArray) {
                return null
            }
            val patches = mutableListOf<FileContentPatch>()
            for (item in element.asJsonArray) {
                val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return null
                val oldText = obj.get("oldText")?.takeIf { !it.isJsonNull }?.asString ?: return null
                val newText = obj.get("newText")?.takeIf { !it.isJsonNull }?.asString ?: return null
                if (oldText.isEmpty()) {
                    return null
                }
                patches.add(FileContentPatch(oldText = oldText, newText = newText))
            }
            return patches
        }

        private fun previewContent(content: String, maxLength: Int = 200): String {
            val normalized = content.replace("\r", "\\r").replace("\n", "\\n")
            return if (normalized.length <= maxLength) {
                normalized
            } else {
                normalized.take(maxLength) + "..."
            }
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

        private fun resolveProjectKey(project: Project): String {
            val basePath = project.basePath?.replace("\\", "/")?.trim().orEmpty()
            return if (basePath.isNotEmpty()) basePath else project.name
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

        private fun parseSkillResources(element: com.google.gson.JsonElement?): List<AgentSkillResourceDraft> {
            if (element == null || element.isJsonNull || !element.isJsonArray) {
                return emptyList()
            }
            return element.asJsonArray.mapNotNull { item ->
                val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val path = obj.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                if (path.isBlank()) {
                    return@mapNotNull null
                }
                AgentSkillResourceDraft(
                    path = path,
                    content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                )
            }
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
