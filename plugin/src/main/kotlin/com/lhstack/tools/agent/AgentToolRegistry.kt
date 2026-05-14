package com.lhstack.tools.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.lhstack.tools.ext.errorNotify
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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import com.lhstack.tools.listener.PluginListener
import com.lhstack.tools.listener.ProjectPluginListener
import com.lhstack.tools.plugins.pluginState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.collections.set

data class AgentTool(
    val name: String,
    val description: String,
    val parametersJson: String,
    val call: (String) -> String,
    val requiredPermission: AgentToolPermissionScope = AgentToolPermissionScope.WORKSPACE_WRITE,
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
        private const val SYSTEM_PLUGIN_ID = "jtools_system"
        private const val SYSTEM_PLUGIN_NAME = "系统"
        private const val SYSTEM_PLUGIN_PATH = "<internal>"
        private const val SYSTEM_PLUGIN_TYPE = "system"
        private const val DEFAULT_READ_FILE_LIMIT = 200
        private const val MAX_READ_FILE_LIMIT = 1000

        fun build(
            project: Project,
            selectedSkills: List<AgentSkillState> = emptyList(),
            sessionRuntime: AgentSessionRuntimeState = AgentSessionRuntimeState(),
        ): AgentToolRegistry {
            val tools = mutableListOf<AgentTool>()
            val toolByName = linkedMapOf<String, AgentTool>()
            val pluginTools = linkedMapOf<String, MutableList<AgentTool>>()
            val pluginInfoById = linkedMapOf<String, PluginInfo>()
            val pluginInfosByName = linkedMapOf<String, MutableList<PluginInfo>>()
            val usedNames = hashSetOf<String>()
            val devPluginInfo = DevPluginRegistry.pluginInfo()

            fun registerTool(tool: AgentTool) {
                val uniqueName = ensureUniqueName(tool.name, usedNames)
                val wrappedTool = tool.copy(
                    call = { arguments ->
                        executeWithPermissionGuard(project, sessionRuntime, tool, arguments)
                    }
                )
                val finalTool = if (uniqueName == wrappedTool.name) wrappedTool else wrappedTool.copy(name = uniqueName)
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

            registerJtoolsFunctions(project, registry, systemPluginInfo, ::registerTool)
            registerSkillManagementTools(project, systemPluginInfo, ::registerTool)
            registerMcpManagementTools(project, systemPluginInfo, ::registerTool)
            registerMcpTools(project, ::registerTool)

            return registry
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
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
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
                    requiredPermission = AgentToolPermissionScope.WORKSPACE_WRITE,
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
                    requiredPermission = AgentToolPermissionScope.WORKSPACE_WRITE,
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
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
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
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
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
                    requiredPermission = AgentToolPermissionScope.WORKSPACE_WRITE,
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
                    requiredPermission = AgentToolPermissionScope.WORKSPACE_WRITE,
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
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
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
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "read_file",
                    description = "Read a text file in bounded line pages. PDF files are auto-extracted to text. Relative paths resolve from the current project; explicit absolute paths are used directly when the selected permission scope allows them. Omit offset to start at line 1. Omit limit to read the first 200 lines. Continue reading by increasing offset when hasMore is true.",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "文件路径。相对路径会基于当前项目目录解析；完整路径会直接解析，但项目外路径需要完全访问权限。" },
                            "offset": { "type": "integer", "description": "起始行偏移，从 0 开始。", "minimum": 0 },
                            "limit": { "type": "integer", "description": "最多返回多少行；默认 200，最大 1000。", "minimum": 1, "maximum": 1000 }
                          },
                          "required": ["path"]
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val offset = payload.get("offset")?.takeIf { !it.isJsonNull }?.asInt
                        val limit = payload.get("limit")?.takeIf { !it.isJsonNull }?.asInt
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        ok(project, readFile(project, path, offset, limit))
                    },
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "write_file",
                    description = "Write a text file in the workspace, creating parent directories when needed.",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "文件路径。相对路径会基于当前项目目录解析；项目外完整路径需要完全访问权限。" },
                            "content": { "type": "string", "description": "完整文件内容。" }
                          },
                          "required": ["path", "content"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val content = payload.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        ok(project, writeFile(project, path, content))
                    },
                    requiredPermission = AgentToolPermissionScope.WORKSPACE_WRITE,
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "edit_file",
                    description = "Replace text in a workspace file.",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "path": { "type": "string", "description": "文件路径。相对路径会基于当前项目目录解析；项目外完整路径需要完全访问权限。" },
                            "old_string": { "type": "string", "description": "要替换的原始文本。" },
                            "new_string": { "type": "string", "description": "替换后的文本。" },
                            "replace_all": { "type": "boolean", "description": "是否替换所有匹配项。" }
                          },
                          "required": ["path", "old_string", "new_string"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val oldString = payload.get("old_string")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val newString = payload.get("new_string")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val replaceAll = payload.get("replace_all")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                        if (path.isBlank()) {
                            return@AgentTool error(project, "path 不能为空")
                        }
                        ok(project, editFile(project, path, oldString, newString, replaceAll))
                    },
                    requiredPermission = AgentToolPermissionScope.WORKSPACE_WRITE,
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "glob_search",
                    description = "Find files by glob pattern, not regex. Patterns are matched relative to the search root; **/name also matches name in the root directory.",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "pattern": { "type": "string", "description": "glob 模式，不是正则。例如 test.py 查找任意层级同名文件，*.py 查找任意层级 Python 文件，src/**/*.kt 查找 src 下任意层级 Kotlin 文件；**/test.py 也会匹配项目根目录的 test.py。" },
                            "path": { "type": "string", "description": "可选搜索根目录；相对路径会基于当前项目目录解析；项目外完整路径需要完全访问权限。" }
                          },
                          "required": ["pattern"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val pattern = payload.get("pattern")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim()
                        if (pattern.isBlank()) {
                            return@AgentTool error(project, "pattern 不能为空")
                        }
                        ok(project, globSearch(project, pattern, path))
                    },
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "grep_search",
                    description = "Search file contents with a regex pattern.",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "pattern": { "type": "string", "description": "正则表达式。" },
                            "path": { "type": "string", "description": "目录或文件路径，可选。" },
                            "glob": { "type": "string", "description": "文件名过滤 glob。" },
                            "output_mode": { "type": "string", "enum": ["content", "files", "count"] },
                            "-n": { "type": "boolean" },
                            "-i": { "type": "boolean" },
                            "head_limit": { "type": "integer", "minimum": 1 },
                            "offset": { "type": "integer", "minimum": 0 },
                            "context": { "type": "integer", "minimum": 0 }
                          },
                          "required": ["pattern"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val pattern = payload.get("pattern")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (pattern.isBlank()) {
                            return@AgentTool error(project, "pattern 不能为空")
                        }
                        ok(project, grepSearch(project, payload))
                    },
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "bash",
                    description = """
                        Execute a shell command in the current workspace.

                        调用要求：
                        1. command 必须是适配当前操作系统的可直接执行命令。
                        2. Windows 下优先使用 PowerShell 风格命令与语法。
                        3. macOS 下优先使用 zsh 兼容命令与语法。
                        4. Linux 下优先使用 bash 兼容命令与语法。
                        5. 是否使用 rg/find/grep/ls 等命令由模型自行决定，但必须与当前系统 shell 兼容。
                    """.trimIndent(),
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "command": { "type": "string", "description": "要执行的命令。" },
                            "workdir": { "type": "string", "description": "执行目录，可选；相对路径会基于当前项目目录解析。" },
                            "timeout": { "type": "integer", "description": "超时时间，单位毫秒，默认 30000。" },
                            "timeoutMs": { "type": "integer", "description": "兼容旧字段，超时时间，单位毫秒。" },
                            "description": { "type": "string", "description": "命令说明，可选。" },
                            "run_in_background": { "type": "boolean", "description": "是否后台运行；当前实现始终前台执行。" }
                          },
                          "required": ["command"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args)
                            ?: return@AgentTool error(project, "参数解析失败")
                        val command = payload.get("command")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val workdir = payload.get("workdir")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val timeoutMs = payload.get("timeout")?.takeIf { !it.isJsonNull }?.asLong
                            ?: payload.get("timeoutMs")?.takeIf { !it.isJsonNull }?.asLong
                            ?: 30_000L
                        if (command.isBlank()) {
                            return@AgentTool error(project, "command 不能为空")
                        }
                        ok(project, executeCommand(project, command, workdir, timeoutMs))
                    },
                    requiredPermission = AgentToolPermissionScope.DANGER_FULL_ACCESS,
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "WebFetch",
                    description = "Fetch a URL, convert it into readable text, and answer a prompt about it.",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "url": { "type": "string", "description": "目标 URL。" },
                            "prompt": { "type": "string", "description": "抓取目的或问题。" }
                          },
                          "required": ["url", "prompt"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val url = payload.get("url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        val prompt = payload.get("prompt")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (url.isBlank() || prompt.isBlank()) {
                            return@AgentTool error(project, "url 和 prompt 不能为空")
                        }
                        ok(project, AgentWebTools.fetch(url, prompt))
                    },
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
                    pluginInfo = systemPluginInfo
                )
            )

            registerTool(
                AgentTool(
                    name = "WebSearch",
                    description = "Search the web for current information and return cited results.",
                    parametersJson = """
                        {
                          "type": "object",
                          "properties": {
                            "query": { "type": "string", "description": "搜索词。" },
                            "allowed_domains": { "type": "array", "items": { "type": "string" } },
                            "blocked_domains": { "type": "array", "items": { "type": "string" } }
                          },
                          "required": ["query"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                    call = { args ->
                        val payload = parseArgs(args) ?: return@AgentTool error(project, "参数解析失败")
                        val query = payload.get("query")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                        if (query.isBlank()) {
                            return@AgentTool error(project, "query 不能为空")
                        }
                        val allowedDomains = payload.getAsJsonArray("allowed_domains")?.mapNotNull { it?.asString?.trim() }.orEmpty()
                        val blockedDomains = payload.getAsJsonArray("blocked_domains")?.mapNotNull { it?.asString?.trim() }.orEmpty()
                        ok(project, AgentWebTools.search(query, allowedDomains, blockedDomains))
                    },
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
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
                    requiredPermission = AgentToolPermissionScope.WORKSPACE_WRITE,
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
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
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
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
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
                    requiredPermission = AgentToolPermissionScope.WORKSPACE_WRITE,
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
                    requiredPermission = AgentToolPermissionScope.WORKSPACE_WRITE,
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
                    requiredPermission = AgentToolPermissionScope.READ_ONLY,
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
                "currentTime" to LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
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

        private fun readFile(project: Project, path: String, offset: Int?, limit: Int?): Map<String, Any?> {
            val file = resolveProjectAwareFile(project, path)
            if (!file.exists() || !file.isFile) {
                throw IllegalArgumentException("文件不存在")
            }
            val rawContent = Files.readString(file.toPath(), StandardCharsets.UTF_8)
            val lines = rawContent.split('\n')
            val totalLines = if (rawContent.isBlank()) 0 else lines.size
            val startOffset = (offset ?: 0).coerceAtLeast(0)
            val safeLimit = (limit ?: DEFAULT_READ_FILE_LIMIT).coerceIn(1, MAX_READ_FILE_LIMIT)
            val selected = if (startOffset >= lines.size || totalLines == 0) {
                emptyList()
            } else {
                lines.subList(startOffset, min(lines.size, startOffset + safeLimit))
            }
            val content = selected.joinToString("\n")
            val startLine = min(lines.size + 1, startOffset + 1)
            val endLine = if (selected.isEmpty()) startLine else startLine + selected.size - 1
            return mapOf(
                "file" to mapOf(
                    "path" to file.absolutePath,
                    "content" to content,
                    "startLine" to startLine,
                    "endLine" to endLine,
                    "totalLines" to totalLines,
                    "appliedOffset" to startOffset,
                    "appliedLimit" to safeLimit,
                    "hasMore" to (startOffset + selected.size < totalLines)
                )
            )
        }

        private fun writeFile(project: Project, path: String, content: String): Map<String, Any?> {
            val file = resolveProjectAwareFile(project, path)
            file.parentFile?.let { Files.createDirectories(it.toPath()) }
            val existed = file.exists()
            val original = if (existed) Files.readString(file.toPath(), StandardCharsets.UTF_8) else null
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8)
            return mapOf(
                "path" to file.absolutePath,
                "type" to if (existed) "update" else "create",
                "sizeBytes" to file.length(),
                "originalFile" to original
            )
        }

        private fun editFile(
            project: Project,
            path: String,
            oldString: String,
            newString: String,
            replaceAll: Boolean,
        ): Map<String, Any?> {
            val file = resolveProjectAwareFile(project, path)
            if (!file.exists() || !file.isFile) {
                throw IllegalArgumentException("文件不存在")
            }
            if (oldString == newString) {
                throw IllegalArgumentException("old_string and new_string must differ")
            }
            val original = Files.readString(file.toPath(), StandardCharsets.UTF_8)
            if (!original.contains(oldString)) {
                throw IllegalArgumentException("old_string not found")
            }
            val updated = if (replaceAll) original.replace(oldString, newString) else original.replaceFirst(oldString, newString)
            Files.writeString(file.toPath(), updated, StandardCharsets.UTF_8)
            return mapOf(
                "path" to file.absolutePath,
                "replaceAll" to replaceAll,
                "originalFile" to original,
                "updatedFile" to updated
            )
        }

        private fun globSearch(project: Project, pattern: String, path: String?): Map<String, Any?> {
            val root = resolveProjectAwareDirectory(project, path.orEmpty())
            val matchers = buildGlobMatchers(root, pattern)
            val matches = root.walkTopDown()
                .filter { it.isFile }
                .map { root.toPath().relativize(it.toPath()) to it }
                .filter { (relative, _) ->
                    matchers.any { matcher -> matcher.matches(relative) || matcher.matches(relative.fileName) }
                }
                .map { (_, file) -> file.absolutePath }
                .sorted()
                .toList()
            return mapOf(
                "root" to root.absolutePath,
                "pattern" to pattern,
                "numFiles" to matches.size,
                "filenames" to matches
            )
        }

        private fun buildGlobMatchers(root: File, pattern: String): List<java.nio.file.PathMatcher> {
            val fileSystem = root.toPath().fileSystem
            val patterns = linkedSetOf(pattern)
            if (pattern.startsWith("**/")) {
                patterns += pattern.removePrefix("**/")
            }
            return patterns.map { fileSystem.getPathMatcher("glob:$it") }
        }

        private fun grepSearch(project: Project, payload: JsonObject): Map<String, Any?> {
            val pattern = payload.get("pattern")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            val glob = payload.get("glob")?.takeIf { !it.isJsonNull }?.asString?.trim()
            val outputMode = payload.get("output_mode")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "content" }
            val withLineNumbers = payload.get("-n")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val ignoreCase = payload.get("-i")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val headLimit = payload.get("head_limit")?.takeIf { !it.isJsonNull }?.asInt
            val offset = payload.get("offset")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val root = resolveProjectAwareDirectory(project, path)
            val regex = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            val matcher = glob?.let { root.toPath().fileSystem.getPathMatcher("glob:$it") }
            val contentMatches = mutableListOf<String>()
            val files = mutableSetOf<String>()
            var matchCount = 0

            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = root.toPath().relativize(file.toPath())
                if (matcher != null && !matcher.matches(relative) && !matcher.matches(relative.fileName)) {
                    return@forEach
                }
                val lines = runCatching { Files.readAllLines(file.toPath(), StandardCharsets.UTF_8) }.getOrDefault(emptyList())
                var fileMatched = false
                lines.forEachIndexed { index, line ->
                    if (regex.containsMatchIn(line)) {
                        fileMatched = true
                        matchCount += regex.findAll(line).count().coerceAtLeast(1)
                        val prefix = if (withLineNumbers) "${file.absolutePath}:${index + 1}:" else "${file.absolutePath}:"
                        contentMatches += prefix + line
                    }
                }
                if (fileMatched) {
                    files += file.absolutePath
                }
            }

            return when (outputMode) {
                "count" -> mapOf(
                    "numFiles" to files.size,
                    "numMatches" to matchCount,
                    "appliedOffset" to offset,
                    "appliedLimit" to headLimit
                )
                "files" -> mapOf(
                    "numFiles" to files.size,
                    "filenames" to files.toList(),
                    "appliedOffset" to offset,
                    "appliedLimit" to headLimit
                )
                else -> {
                    val sliced = contentMatches.drop(offset).let { matches ->
                        if (headLimit != null) matches.take(headLimit) else matches
                    }
                    mapOf(
                        "numFiles" to if (offset == 0) files.size else sliced.map { it.substringBefore(':') }.distinct().size,
                        "content" to sliced.joinToString("\n"),
                        "appliedOffset" to offset,
                        "appliedLimit" to headLimit
                    )
                }
            }
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
            AgentToolPermissionSupport.resolveExplicitFile(path)?.let { explicitFile ->
                return explicitFile
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

        private fun executeWithPermissionGuard(
            project: Project,
            sessionRuntime: AgentSessionRuntimeState,
            tool: AgentTool,
            arguments: String,
        ): String {
            val payload = runCatching { parseArgs(arguments) }.getOrNull()
                ?: return error(project, "参数解析失败")
            val requiredScope = requiredPermissionForToolInvocation(project, tool, payload)
            val selectedScope = AgentToolPermissionScope.fromId(sessionRuntime.permissionScope)
            val approvalPolicy = AgentToolApprovalPolicy.fromId(sessionRuntime.approvalPolicy)
            val outcome = AgentToolPermissionSupport.evaluate(
                selectedScope = selectedScope,
                approvalPolicy = approvalPolicy,
                requiredScope = requiredScope,
            )
            if (!outcome.allowed) {
                val message = "当前权限模式不允许执行 ${tool.name}，当前权限为 ${selectedScope.displayName}，所需权限为 ${requiredScope.displayName}"
                runCatching { project.errorNotify("工具权限不足", message) }
                return error(project, message)
            }
            if (outcome.requiresConfirmation && !confirmDangerousToolExecution(project, tool, payload, requiredScope)) {
                return error(project, "用户拒绝执行危险操作")
            }
            return try {
                tool.call(arguments)
            } catch (error: Throwable) {
                error(project, error.message ?: "工具执行失败")
            }
        }

        private fun requiredPermissionForToolInvocation(
            project: Project,
            tool: AgentTool,
            payload: JsonObject,
        ): AgentToolPermissionScope {
            val projectRoot = projectRoot(project)
            fun pathPermission(
                path: String?,
                baseRequired: AgentToolPermissionScope,
            ): AgentToolPermissionScope {
                return AgentToolPermissionSupport.requiredPermissionForPath(projectRoot, path, baseRequired)
            }

            return when (tool.name) {
                "bash" -> {
                    val command = payload.get("command")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                    val workdir = payload.get("workdir")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    AgentToolPermissionSupport.maxScope(
                        AgentToolPermissionSupport.classifyBashPermission(command, projectRoot),
                        pathPermission(workdir, AgentToolPermissionScope.READ_ONLY)
                    )
                }
                "read_file", "glob_search", "grep_search" -> {
                    val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    pathPermission(path, AgentToolPermissionScope.READ_ONLY)
                }
                "write_file", "edit_file", "jtools_create_directory" -> {
                    val path = payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    pathPermission(path, AgentToolPermissionScope.WORKSPACE_WRITE)
                }
                else -> tool.requiredPermission
            }
        }

        private fun projectRoot(project: Project): File {
            return File(project.basePath?.takeIf { it.isNotBlank() } ?: System.getProperty("user.dir"))
        }

        private fun confirmDangerousToolExecution(
            project: Project,
            tool: AgentTool,
            payload: JsonObject,
            requiredScope: AgentToolPermissionScope,
        ): Boolean {
            val accepted = booleanArrayOf(false)
            ApplicationManager.getApplication().invokeAndWait {
                val details = when (tool.name) {
                    "bash" -> payload.get("command")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                    else -> payload.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                }
                accepted[0] = Messages.showYesNoDialog(
                    project,
                    "即将执行危险操作：${tool.name}\n权限级别：${requiredScope.displayName}\n$details",
                    "确认危险操作",
                    Messages.getWarningIcon()
                ) == Messages.YES
            }
            return accepted[0]
        }

        private fun ok(project: Project, data: Map<String, Any?>): String {
            return project.gson.toJson(linkedMapOf<String, Any?>("ok" to true).apply { putAll(data) })
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
