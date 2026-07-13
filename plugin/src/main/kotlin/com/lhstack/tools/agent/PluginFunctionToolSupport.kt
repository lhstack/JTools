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

    fun groups(project: Project?): List<Group> {
        val groups = mutableListOf<Group>()
        PluginManager.getInstance().plugins { info, plugin ->
            functionEntries(project, pluginKey(info), info.name, plugin).takeIf { it.isNotEmpty() }?.let { entries ->
                groups.add(Group(pluginKey(info), info.name, entries))
            }
        }
        val devPlugin = DevPluginRegistry.plugin()
        val devInfo = DevPluginRegistry.pluginInfo()
        if (devPlugin != null) {
            val pluginName = devInfo?.name ?: devPlugin.pluginName()
            val pluginKey = "dev:${pluginName}"
            functionEntries(project, pluginKey, pluginName, devPlugin).takeIf { it.isNotEmpty() }?.let { entries ->
                groups.add(Group(pluginKey, "$pluginName（开发）", entries))
            }
        }
        return groups
    }

    fun entries(project: Project?): List<Entry> = groups(project).flatMap { it.functions }

    fun enabledEntries(
        project: Project?,
        enabled: Collection<String>,
        includeNew: Boolean,
        disabled: Collection<String> = emptyList(),
    ): List<Entry> {
        val enabledSet = enabled.toSet()
        val disabledSet = disabled.toSet()
        return entries(project).filter { entry ->
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

    private fun functionEntries(project: Project?, pluginKey: String, pluginName: String, plugin: IPlugin): List<Entry> {
        val functions = if (project != null) plugin.functionCallings(project) else plugin.functionCallings("")
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

    private fun sanitize(value: String): String = value
        .map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_' }
        .joinToString("")
        .trim('_', '-')
}
