package com.lhstack.tools.sandbox

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.lang.UrlClassLoader
import com.lhstack.tools.plugins.*
import java.awt.BorderLayout
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import javax.swing.*

/** 单个沙箱只持有一个产物运行；所有生命周期和 UI 变更串行进入 EDT。 */
class SandboxPluginHost(private val project: Project) : SandboxHost {
    private val gson = Gson()
    @Volatile private var plugin: IPlugin? = null
    private var loader: URLClassLoader? = null
    private val panels = java.util.concurrent.ConcurrentHashMap<String, Pair<JComponent, JFrame>>()
    private val events = mutableListOf<Map<String, Any?>>()
    private var sequence = 0L
    private var state = "EMPTY"
    private var runId: String? = null
    private var installed = false
    private var opened = false
    private fun event(stage: String, error: Throwable? = null) = synchronized(events) {
        events.add(mapOf("seq" to ++sequence, "stage" to stage.take(8000), "time" to System.currentTimeMillis(), "error" to error?.stackTraceToString()?.take(16000)))
        while (events.size > 2000) events.removeAt(0)
    }
    override fun execute(command: String, argumentsJson: String): String {
        val args = JsonParser.parseString(argumentsJson).asJsonObject
        var result: Any? = null
        var failure: Throwable? = null
        val action = Runnable {
            try {
                result = when (command) {
                    "run" -> run(args)
                    "status" -> status()
                    "events" -> synchronized(events) { events.filter { (it["seq"] as Long) > (args.get("after")?.asLong ?: 0) }.take(200) }
                    "panel.open" -> openPanel()
                    "panel.close" -> { closePanel(args.get("panel_id").asString); status() }
                    "stop" -> { stop(); status() }
                    else -> error("未知生命周期命令: $command")
                }
            } catch (error: Throwable) { event(command, error); failure = error }
        }
        if (SwingUtilities.isEventDispatchThread()) action.run() else ApplicationManager.getApplication().invokeAndWait(action)
        failure?.let { throw it }
        return gson.toJson(result)
    }
    private fun run(args: JsonObject): Any {
        check(plugin == null) { "必须先停止旧运行；清除泄漏的全局状态请重启沙箱" }
        val root = Path.of(System.getProperty("jtools.sandbox.root")).toRealPath()
        val artifact = Path.of(args.get("artifact").asString).toRealPath()
        require(artifact.startsWith(root.resolve("artifacts"))) { "只加载控制端暂存的产物" }
        runId = UUID.randomUUID().toString()
        state = "LOADING"
        val classes = root.resolve("runs").resolve(runId!!)
        Files.createDirectories(classes)
        try {
            ZipFile(artifact.toFile()).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val target = classes.resolve(entry.name).normalize()
                    require(target.startsWith(classes)) { "压缩包路径越界: ${entry.name}" }
                    if (entry.isDirectory) Files.createDirectories(target) else {
                        Files.createDirectories(target.parent)
                        zip.getInputStream(entry).use { Files.copy(it, target) }
                    }
                }
            }
            val entry = classes.resolve("META-INF/ToolsPlugin.txt")
            plugin = if (Files.isRegularFile(entry)) {
                val classLoader = URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader)
                loader = classLoader
                (classLoader.loadClass(Files.readString(entry).trim()).getConstructor().newInstance() as IPlugin).also {
                    val support = it.support(Helper.JTOOLS_VERSION, Helper.getIdeInfo())
                    require(support.isSupport) { support.message ?: "插件不支持当前宿主" }
                }
            } else {
                val info = gson.fromJson(Files.readString(classes.resolve("pluginInfo.json")), CefPluginInfo::class.java)
                val paths = arrayListOf(classes)
                CefPluginImpl(PluginClassLoader(UrlClassLoader.build().files(paths).parent(javaClass.classLoader).allowLock(false), paths), info, MemoryCache())
            }
            event("loaded")
            plugin!!.install(); installed = true; event("install")
            val logger = object : Logger {
                private fun log(level: String, msg: Any?) { synchronized(events) { event("log.$level: $msg") } }
                override fun info(msg: Any?) = log("info", msg)
                override fun warn(msg: Any?) = log("warn", msg)
                override fun debug(msg: Any?) = log("debug", msg)
                override fun error(msg: Any?) = log("error", msg)
                override fun activeConsolePanel(): Logger = this
            }
            plugin!!.openProject(project, logger) {
                val action = Runnable { openPanel() }
                if (SwingUtilities.isEventDispatchThread()) action.run() else ApplicationManager.getApplication().invokeLater(action)
            }; opened = true; event("openProject")
            if (plugin!!.pluginType() != PluginType.JAVA_NON_UI) openPanel()
            state = "RUNNING"
            return status()
        } catch (error: Throwable) {
            state = "FAILED"; event("run.failed", error)
            throw error
        }
    }
    private fun openPanel(): Map<String, Any?> {
        val current = checkNotNull(plugin) { "没有运行插件" }
        check(current.pluginType() != PluginType.JAVA_NON_UI) { "非 UI 插件没有面板" }
        if (!current.supportMultiOpens() && panels.isNotEmpty()) return status()
        val component = checkNotNull(current.createPanel(project)) { "createPanel 返回 null" }
        val id = UUID.randomUUID().toString()
        val frame = JFrame("JTools 沙箱 — ${current.pluginName()}")
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.contentPane.add(component, BorderLayout.CENTER)
        frame.setSize(1000, 720)
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) { closePanel(id) }
        })
        panels[id] = component to frame
        frame.isVisible = true
        event("createPanel:$id")
        current.showPanel(project, component); event("showPanel:$id")
        return status()
    }
    private fun closePanel(id: String) {
        val (component, frame) = panels[id] ?: error("面板不存在或已关闭: $id")
        try { plugin!!.closePanel(project, component); event("closePanel:$id") }
        finally { panels.remove(id); frame.dispose() }
    }
    private fun stop() {
        var failure: Throwable? = null
        fun cleanup(stage: String, action: () -> Unit) {
            try { action(); event(stage) } catch (error: Throwable) {
                event(stage, error)
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        panels.keys.toList().forEach { id -> cleanup("panel.close") { closePanel(id) } }
        if (opened) cleanup("closeProject") { plugin!!.closeProject(project) }
        if (installed) cleanup("unInstall") { plugin!!.unInstall() }
        cleanup("classloader.close") { loader?.close() }
        plugin = null; loader = null; opened = false; installed = false
        state = if (failure == null) "STOPPED" else "CLEANUP_FAILED"
        failure?.let { throw it }
    }
    private fun status(): Map<String, Any?> = mapOf("run_id" to runId, "state" to state, "panels" to panels.keys.toList(), "event_cursor" to sequence)
    override fun panel(panelId: String): JComponent = panels[panelId]?.first ?: error("面板已失效: $panelId")
    override fun browser(panelId: String): Any {
        panel(panelId)
        return (plugin as? CefPluginImpl ?: error("当前运行不是 JS 插件")).sandboxBrowser(project)
    }
    private class MemoryCache : CefCacheManager {
        private val values = mutableMapOf<String, MutableMap<String, String>>()
        private fun cache(global: Boolean, project: Project) = values.getOrPut(if (global) "global" else project.locationHash) { mutableMapOf() }
        override fun set(global: Boolean, project: Project, key: String, value: String) { cache(global, project)[key] = value }
        override fun get(global: Boolean, project: Project, key: String) = cache(global, project)[key]
        override fun getOrDefault(global: Boolean, project: Project, key: String, defaultValue: String) = cache(global, project).getOrDefault(key, defaultValue)
        override fun getAll(global: Boolean, project: Project): Map<String, String> = cache(global, project).toMap()
        override fun keys(global: Boolean, project: Project): Set<String> = cache(global, project).keys.toSet()
        override fun exists(global: Boolean, project: Project, key: String) = cache(global, project).containsKey(key)
        override fun clear(global: Boolean, project: Project) { cache(global, project).clear() }
        override fun setAll(global: Boolean, project: Project, data: Map<String, String>) { cache(global, project).putAll(data) }
        override fun size(global: Boolean, project: Project): Int = cache(global, project).size
        override fun remove(global: Boolean, project: Project, key: String) { cache(global, project).remove(key) }
    }
}
