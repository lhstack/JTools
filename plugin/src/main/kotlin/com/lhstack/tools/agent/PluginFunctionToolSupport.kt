package com.lhstack.tools.agent

import com.intellij.openapi.project.Project
import com.lhstack.tools.dev.DevPluginRegistry
import com.lhstack.tools.plugins.FunctionCalling
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.PluginInfo
import com.lhstack.tools.plugins.PluginManager

/** Agent 可用的插件函数集合。 */
object PluginFunctionToolSupport {

    data class Group(
        val pluginKey: String,
        val pluginName: String,
        val functions: List<Entry>,
    )

    data class Entry(
        val key: String,
        val toolName: String,
        val functionName: String,
        val description: String,
        val function: FunctionCalling,
    )

    fun groups(
        project: Project? = null,
        sessionName: String? = null,
        sessionId: String? = null,
    ): List<Group> {
        val groups = mutableListOf<Group>()
        PluginManager.getInstance().plugins { info, plugin ->
            functionEntries(pluginKey(info), info.name, plugin, project, sessionName, sessionId)
                .takeIf { it.isNotEmpty() }
                ?.let { entries -> groups.add(Group(pluginKey(info), info.name, entries)) }
        }
        val devPlugin = DevPluginRegistry.plugin()
        val devInfo = DevPluginRegistry.pluginInfo()
        if (devPlugin != null) {
            val pluginName = devInfo?.name ?: devPlugin.pluginName()
            val pluginKey = "dev:${pluginName}"
            functionEntries(pluginKey, pluginName, devPlugin, project, sessionName, sessionId)
                .takeIf { it.isNotEmpty() }
                ?.let { entries -> groups.add(Group(pluginKey, "$pluginName（开发）", entries)) }
        }
        return groups
    }

    fun entries(
        project: Project? = null,
        sessionName: String? = null,
        sessionId: String? = null,
    ): List<Entry> = groups(project, sessionName, sessionId).flatMap { it.functions }

    fun enabledEntries(
        enabled: Collection<String>,
        includeNew: Boolean,
        disabled: Collection<String> = emptyList(),
        project: Project? = null,
        sessionName: String? = null,
        sessionId: String? = null,
    ): List<Entry> {
        val enabledSet = enabled.toSet()
        val disabledSet = disabled.toSet()
        return entries(project, sessionName, sessionId).filter { entry ->
            entry.key in enabledSet || (includeNew && entry.key !in disabledSet)
        }
    }

    fun key(pluginKey: String, functionName: String): String = "$pluginKey:$functionName"

    fun toolName(key: String, functionName: String): String {
        val hash = Integer.toUnsignedString(key.hashCode(), 36)
        val suffix = sanitize(functionName).ifBlank { "function" }
        return "plugin_${hash}_$suffix".take(64)
    }

    private fun pluginKey(info: PluginInfo): String = "plugin:${info.id}"

    private fun functionEntries(
        pluginKey: String,
        pluginName: String,
        plugin: IPlugin,
        project: Project? = null,
        sessionName: String? = null,
        sessionId: String? = null,
    ): List<Entry> {
        val functions = listFunctions(plugin, project, sessionName, sessionId)
        return functions
            .filter { it.name().isNotBlank() }
            .distinctBy { it.name() }
            .map { function ->
                val key = key(pluginKey, function.name())
                Entry(
                    key = key,
                    toolName = toolName(key, function.name()),
                    functionName = function.name(),
                    description = function.description().ifBlank { "插件 $pluginName 函数 ${function.name()}" },
                    function = function,
                )
            }
    }

    private fun listFunctions(
        plugin: IPlugin,
        project: Project?,
        sessionName: String?,
        sessionId: String?,
    ): List<FunctionCalling> {
        val hasSession = !sessionName.isNullOrBlank() && !sessionId.isNullOrBlank()
        val opened = listOfNotNull(project).plus(openProjects()).distinct()
        if (hasSession) {
            val name = sessionName!!
            val id = sessionId!!
            return opened.firstNotNullOfOrNull { item ->
                plugin.functionCallings(name, id, item).takeIf { it.isNotEmpty() }
            } ?: plugin.functionCallings(name, id, project?.locationHash.orEmpty())
        }
        return opened.firstNotNullOfOrNull { item ->
            plugin.functionCallings(null, null, item).takeIf { it.isNotEmpty() }
        } ?: plugin.functionCallings("")
    }

    private fun openProjects(): List<Project> =
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }

    private fun sanitize(value: String): String = value
        .map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_' }
        .joinToString("")
        .trim('_', '-')
}
