package com.lhstack.tools.plugins

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.fileChooser.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.lhstack.tools.ext.endsWithAny
import com.lhstack.tools.ext.endsWithIgnoreCase
import com.lhstack.tools.ext.fullMsg
import com.lhstack.tools.ext.gson
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.*
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.misc.EventFlags
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.min

@State(name = "cef", storages = [Storage("ToolsPluginState.xml")])
@Service
class CefPluginCacheState : PersistentStateComponent<CefPluginCacheState.State> {
    private var state = State()

    class State {
        var jsPluginCache = hashMapOf<String, HashMap<String, String>>()
    }

    companion object {
        fun getInstance(project: Project) = project.getService(CefPluginCacheState::class.java).state

        fun getInstance() = service<CefPluginCacheState>().state
    }

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        this.state = state
    }
}

class CefPluginInfo(
    val pluginIcon: String,
    val pluginTabIcon: String,
    val pluginName: String,
    val pluginDesc: String,
    val pluginVersion: String,
    val indexPage: String,
)

class CefPluginImpl(
    private val classLoader: PluginClassLoader,
    private val cefPluginInfo: CefPluginInfo,
    private val cefCacheManager: CefCacheManager,
) : IPlugin {

    private val browsers: HashMap<String, JBCefBrowser> = hashMapOf()

    private val disposables: HashMap<String, Disposable> = hashMapOf()

    private val loggerMap: HashMap<String, Logger> = hashMapOf()

    override fun createPanel(project: Project): JComponent {
        return browsers.computeIfAbsent(project.locationHash) {
            val jbCefApp = JBCefApp.getInstance()
            val jbCefClient = jbCefApp.createClient()
            val jbBrowser = JBCefBrowser.createBuilder()
                .setOffScreenRendering(false)
                .setClient(jbCefClient).build()
            val cefMessageRouter = CefMessageRouter.create(object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(
                    browser: CefBrowser,
                    frame: CefFrame,
                    queryId: Long,
                    request: String,
                    persistent: Boolean,
                    callback: CefQueryCallback,
                ): Boolean {
                    try {
                        val queryCommand = this.gson.fromJson(request, CefQueryCommand::class.java)
                        //cefQuery({request:JSON.stringify({"type":"log",commands:["debug","this is debug log"]}),onSuccess: res => console.log(res),onFailure: (code,msg) => console.log(code,msg)})
                        when (queryCommand.type) {
                            
                            // ==================== 帮助文档 ====================
                            "help" -> {
                                val apiDoc = JS_PLUGIN_API_DOC
                                callback.success(this.gson.toJson(apiDoc))
                            }

                            "help.markdown" -> {
                                callback.success(JS_PLUGIN_API_MARKDOWN)
                            }

                            "help.list" -> {
                                callback.success(this.gson.toJson(JS_PLUGIN_API_DOC.map { it["name"] }))
                            }

                            // ==================== 日志 ====================
                            "log" -> {
                                loggerMap[project.locationHash]?.let {
                                    when (queryCommand.commands[0]) {
                                        "debug" -> it.debug(queryCommand.commands[1])
                                        "info" -> it.info(queryCommand.commands[1])
                                        "warn" -> it.warn(queryCommand.commands[1])
                                        "error" -> it.error(queryCommand.commands[1])
                                    }
                                }
                                callback.success("success")
                            }

                            "getPluginInfo" -> callback.success(this.gson.toJson(cefPluginInfo))

                            "getSysEnv" -> callback.success(System.getenv(queryCommand.commands[0]))

                            "getSysProperty" -> callback.success(System.getProperty(queryCommand.commands[0]) ?: "")

                            //获取 JTools 版本
                            "getJToolsVersion" -> callback.success(Helper.JTOOLS_VERSION.toString())

                            //获取 IDE 信息
                            "getIdeInfo" -> callback.success(this.gson.toJson(Helper.getIdeInfo()))

                            //获取项目路径
                            "getProjectBasePath" -> callback.success(project.basePath ?: "")

                            //读取系统文件内容
                            "readSysFile" -> {
                                val path = Paths.get(queryCommand.commands[0])
                                if (Files.exists(path)) {
                                    callback.success(Files.readString(path))
                                } else {
                                    callback.failure(404, "File not found: ${queryCommand.commands[0]}")
                                }
                            }

                            //读取本插件文件内容
                            "readPluginFile" -> {
                                val stream = classLoader.getResourceAsStream(queryCommand.commands[0])
                                if (stream != null) {
                                    stream.use {
                                        callback.success(String(it.readAllBytes(), StandardCharsets.UTF_8))
                                    }
                                } else {
                                    callback.failure(404, "Plugin file not found: ${queryCommand.commands[0]}")
                                }
                            }

                            //发送通知
                            "notify" -> {
                                Helper.notify(
                                    project.locationHash,
                                    queryCommand.commands[0], // title
                                    queryCommand.commands[1], // content
                                    queryCommand.commands.getOrElse(2) { "INFORMATION" } // type
                                )
                                callback.success("success")
                            }

                            //在编辑器中打开文件
                            "openFileInEditor" -> {
                                Helper.openFileInEditor(project.locationHash, Paths.get(queryCommand.commands[0]))
                                callback.success("success")
                            }

                            // ==================== 文件操作 ====================

                            //写入系统文件
                            "writeSysFile" -> {
                                val path = Paths.get(queryCommand.commands[0])
                                val content = queryCommand.commands[1]
                                val append = queryCommand.commands.getOrElse(2) { "false" }.toBoolean()
                                if (append) {
                                    Files.writeString(path, content, StandardCharsets.UTF_8, 
                                        java.nio.file.StandardOpenOption.CREATE, 
                                        java.nio.file.StandardOpenOption.APPEND)
                                } else {
                                    Files.writeString(path, content, StandardCharsets.UTF_8)
                                }
                                callback.success("success")
                            }

                            //检查文件/目录是否存在
                            "fileExists" -> {
                                val path = Paths.get(queryCommand.commands[0])
                                callback.success(Files.exists(path).toString())
                            }

                            //检查是否是目录
                            "isDirectory" -> {
                                val path = Paths.get(queryCommand.commands[0])
                                callback.success(Files.isDirectory(path).toString())
                            }

                            //列出目录内容
                            "listDir" -> {
                                val path = Paths.get(queryCommand.commands[0])
                                if (Files.isDirectory(path)) {
                                    val files = Files.list(path).use { stream ->
                                        stream.map { file ->
                                            mapOf(
                                                "name" to file.fileName.toString(),
                                                "path" to file.toString(),
                                                "isDirectory" to Files.isDirectory(file),
                                                "size" to if (Files.isRegularFile(file)) Files.size(file) else 0L,
                                                "lastModified" to Files.getLastModifiedTime(file).toMillis()
                                            )
                                        }.toList()
                                    }
                                    callback.success(this.gson.toJson(files))
                                } else {
                                    callback.failure(400, "Path is not a directory: ${queryCommand.commands[0]}")
                                }
                            }

                            //创建目录
                            "createDir" -> {
                                val path = Paths.get(queryCommand.commands[0])
                                Files.createDirectories(path)
                                callback.success("success")
                            }

                            //删除文件或目录
                            "deleteFile" -> {
                                val path = Paths.get(queryCommand.commands[0])
                                if (Files.exists(path)) {
                                    if (Files.isDirectory(path)) {
                                        Files.walk(path)
                                            .sorted(Comparator.reverseOrder())
                                            .forEach { Files.delete(it) }
                                    } else {
                                        Files.delete(path)
                                    }
                                    callback.success("success")
                                } else {
                                    callback.failure(404, "File not found: ${queryCommand.commands[0]}")
                                }
                            }

                            //复制文件
                            "copyFile" -> {
                                val source = Paths.get(queryCommand.commands[0])
                                val target = Paths.get(queryCommand.commands[1])
                                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                callback.success("success")
                            }

                            //移动/重命名文件
                            "moveFile" -> {
                                val source = Paths.get(queryCommand.commands[0])
                                val target = Paths.get(queryCommand.commands[1])
                                Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                callback.success("success")
                            }

                            //获取文件信息
                            "getFileInfo" -> {
                                val path = Paths.get(queryCommand.commands[0])
                                if (Files.exists(path)) {
                                    val info = mapOf(
                                        "name" to path.fileName.toString(),
                                        "path" to path.toString(),
                                        "absolutePath" to path.toAbsolutePath().toString(),
                                        "isDirectory" to Files.isDirectory(path),
                                        "isFile" to Files.isRegularFile(path),
                                        "size" to if (Files.isRegularFile(path)) Files.size(path) else 0L,
                                        "lastModified" to Files.getLastModifiedTime(path).toMillis(),
                                        "readable" to Files.isReadable(path),
                                        "writable" to Files.isWritable(path),
                                        "executable" to Files.isExecutable(path)
                                    )
                                    callback.success(this.gson.toJson(info))
                                } else {
                                    callback.failure(404, "File not found: ${queryCommand.commands[0]}")
                                }
                            }

                            // ==================== 剪贴板操作 ====================

                            //复制到剪贴板
                            "copyToClipboard" -> {
                                val content = queryCommand.commands[0]
                                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                clipboard.setContents(StringSelection(content), null)
                                callback.success("success")
                            }

                            //从剪贴板获取
                            "getFromClipboard" -> {
                                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                                val content = try {
                                    clipboard.getData(DataFlavor.stringFlavor) as? String ?: ""
                                } catch (e: Exception) {
                                    ""
                                }
                                callback.success(content)
                            }

                            // ==================== 对话框 ====================

                            //输入对话框
                            "showInputDialog" -> {
                                val future = CompletableFuture<String?>()
                                ApplicationManager.getApplication().invokeLater {
                                    val result = Messages.showInputDialog(
                                        project,
                                        queryCommand.commands.getOrElse(0) { "请输入" }, // message
                                        queryCommand.commands.getOrElse(1) { "输入" }, // title
                                        null, // icon
                                        queryCommand.commands.getOrElse(2) { "" }, // initialValue
                                        null // validator
                                    )
                                    future.complete(result)
                                }
                                val result = future.get()
                                if (result != null) {
                                    callback.success(result)
                                } else {
                                    callback.success("")
                                }
                            }

                            //确认对话框
                            "showConfirmDialog" -> {
                                val future = CompletableFuture<Int>()
                                ApplicationManager.getApplication().invokeLater {
                                    val result = Messages.showYesNoCancelDialog(
                                        project,
                                        queryCommand.commands.getOrElse(0) { "确认操作？" }, // message
                                        queryCommand.commands.getOrElse(1) { "确认" }, // title
                                        Messages.getQuestionIcon()
                                    )
                                    future.complete(result)
                                }
                                // 0=Yes, 1=No, 2=Cancel
                                callback.success(future.get().toString())
                            }

                            //消息对话框
                            "showMessageDialog" -> {
                                ApplicationManager.getApplication().invokeLater {
                                    Messages.showMessageDialog(
                                        project,
                                        queryCommand.commands.getOrElse(0) { "" }, // message
                                        queryCommand.commands.getOrElse(1) { "提示" }, // title
                                        when (queryCommand.commands.getOrElse(2) { "info" }) {
                                            "error" -> Messages.getErrorIcon()
                                            "warning" -> Messages.getWarningIcon()
                                            else -> Messages.getInformationIcon()
                                        }
                                    )
                                }
                                callback.success("success")
                            }

                            //文件选择对话框
                            "chooseFile" -> {
                                val future = CompletableFuture<String>()
                                ApplicationManager.getApplication().invokeLater {
                                    val descriptor = FileChooserDescriptor(true, false, true, true, false, false)
                                        .withTitle(queryCommand.commands.getOrElse(0) { "选择文件" })
                                    val files = FileChooser.chooseFiles(descriptor, project, null)
                                    if (files.isNotEmpty()) {
                                        future.complete(files[0].path)
                                    } else {
                                        future.complete("")
                                    }
                                }
                                callback.success(future.get())
                            }

                            //多文件选择对话框
                            "chooseFiles" -> {
                                val future = CompletableFuture<List<String>>()
                                ApplicationManager.getApplication().invokeLater {
                                    val descriptor = FileChooserDescriptor(true, false, true, true, false, true)
                                        .withTitle(queryCommand.commands.getOrElse(0) { "选择文件" })
                                    val files = FileChooser.chooseFiles(descriptor, project, null)
                                    future.complete(files.map { it.path })
                                }
                                callback.success(this.gson.toJson(future.get()))
                            }

                            //目录选择对话框
                            "chooseDirectory" -> {
                                val future = CompletableFuture<String>()
                                ApplicationManager.getApplication().invokeLater {
                                    val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
                                        .withTitle(queryCommand.commands.getOrElse(0) { "选择目录" })
                                    val files = FileChooser.chooseFiles(descriptor, project, null)
                                    if (files.isNotEmpty()) {
                                        future.complete(files[0].path)
                                    } else {
                                        future.complete("")
                                    }
                                }
                                callback.success(future.get())
                            }

                            //保存文件对话框
                            "saveFileDialog" -> {
                                val future = CompletableFuture<String>()
                                ApplicationManager.getApplication().invokeLater {
                                    val descriptor = FileSaverDescriptor(
                                        queryCommand.commands.getOrElse(0) { "保存文件" }, // title
                                        queryCommand.commands.getOrElse(1) { "" } // description
                                    )
                                    val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                                    val wrapper = dialog.save(queryCommand.commands.getOrElse(2) { "file.txt" }) // default filename
                                    if (wrapper != null) {
                                        future.complete(wrapper.file.absolutePath)
                                    } else {
                                        future.complete("")
                                    }
                                }
                                callback.success(future.get())
                            }

                            // ==================== 系统操作 ====================

                            //在浏览器中打开 URL
                            "openUrl" -> {
                                BrowserUtil.browse(queryCommand.commands[0])
                                callback.success("success")
                            }

                            //执行系统命令
                            "executeCommand" -> {
                                val command = queryCommand.commands[0]
                                val workDir = queryCommand.commands.getOrElse(1) { project.basePath ?: "." }
                                val timeout = queryCommand.commands.getOrElse(2) { "30000" }.toLong()
                                
                                CompletableFuture.supplyAsync {
                                    try {
                                        val processBuilder = ProcessBuilder()
                                        if (System.getProperty("os.name").lowercase().contains("win")) {
                                            processBuilder.command("cmd", "/c", command)
                                        } else {
                                            processBuilder.command("sh", "-c", command)
                                        }
                                        processBuilder.directory(java.io.File(workDir))
                                        processBuilder.redirectErrorStream(true)
                                        
                                        val process = processBuilder.start()
                                        val output = StringBuilder()
                                        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                                            var line: String?
                                            while (reader.readLine().also { line = it } != null) {
                                                output.append(line).append("\n")
                                            }
                                        }
                                        
                                        val completed = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                                        if (!completed) {
                                            process.destroyForcibly()
                                            throw RuntimeException("Command timeout after ${timeout}ms")
                                        }
                                        
                                        mapOf(
                                            "exitCode" to process.exitValue(),
                                            "output" to output.toString().trim()
                                        )
                                    } catch (e: Exception) {
                                        mapOf(
                                            "exitCode" to -1,
                                            "output" to e.message
                                        )
                                    }
                                }.thenAccept { result ->
                                    callback.success(this.gson.toJson(result))
                                }
                            }

                            //获取操作系统信息
                            "getOsInfo" -> {
                                val info = mapOf(
                                    "name" to System.getProperty("os.name"),
                                    "version" to System.getProperty("os.version"),
                                    "arch" to System.getProperty("os.arch"),
                                    "userHome" to System.getProperty("user.home"),
                                    "userName" to System.getProperty("user.name"),
                                    "javaVersion" to System.getProperty("java.version"),
                                    "javaVendor" to System.getProperty("java.vendor"),
                                    "fileSeparator" to System.getProperty("file.separator"),
                                    "lineSeparator" to System.lineSeparator(),
                                    "tempDir" to System.getProperty("java.io.tmpdir")
                                )
                                callback.success(this.gson.toJson(info))
                            }

                            //获取当前时间戳
                            "currentTimeMillis" -> {
                                callback.success(System.currentTimeMillis().toString())
                            }

                            //刷新项目文件
                            "refreshProject" -> {
                                ApplicationManager.getApplication().invokeLater {
                                    VirtualFileManager.getInstance().refreshWithoutFileWatcher(true)
                                }
                                callback.success("success")
                            }

                            "global.cache.set" -> {
                                cefCacheManager.set(true, project, queryCommand.commands[0], queryCommand.commands[1])
                                callback.success("success")
                            }

                            "global.cache.get" -> callback.success(
                                cefCacheManager.get(
                                    true,
                                    project,
                                    queryCommand.commands[0]
                                ) ?: ""
                            )

                            "global.cache.getOrDefault" -> callback.success(
                                cefCacheManager.getOrDefault(
                                    true,
                                    project,
                                    queryCommand.commands[0],
                                    queryCommand.commands.getOrElse(1) { "" }
                                )
                            )

                            "global.cache.getAll" -> callback.success(
                                this.gson.toJson(
                                    cefCacheManager.getAll(
                                        true,
                                        project
                                    )
                                )
                            )

                            "global.cache.keys" -> callback.success(
                                this.gson.toJson(cefCacheManager.keys(true, project))
                            )

                            "global.cache.exists" -> callback.success(
                                cefCacheManager.exists(true, project, queryCommand.commands[0]).toString()
                            )

                            "global.cache.size" -> callback.success(
                                cefCacheManager.size(true, project).toString()
                            )

                            "global.cache.clear" -> {
                                cefCacheManager.clear(true, project)
                                callback.success("success")
                            }

                            "global.cache.remove" -> {
                                cefCacheManager.remove(true, project, queryCommand.commands[0])
                                callback.success("success")
                            }

                            "global.cache.setAll" -> {
                                val data = this.gson.fromJson<Map<String, String>>(
                                    queryCommand.commands[0],
                                    object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                                )
                                cefCacheManager.setAll(true, project, data)
                                callback.success("success")
                            }

                            "project.cache.set" -> {
                                cefCacheManager.set(false, project, queryCommand.commands[0], queryCommand.commands[1])
                                callback.success("success")
                            }

                            "project.cache.get" -> callback.success(
                                cefCacheManager.get(
                                    false,
                                    project,
                                    queryCommand.commands[0]
                                ) ?: ""
                            )

                            "project.cache.getOrDefault" -> callback.success(
                                cefCacheManager.getOrDefault(
                                    false,
                                    project,
                                    queryCommand.commands[0],
                                    queryCommand.commands.getOrElse(1) { "" }
                                )
                            )

                            "project.cache.getAll" -> callback.success(
                                this.gson.toJson(
                                    cefCacheManager.getAll(
                                        false,
                                        project
                                    )
                                )
                            )

                            "project.cache.keys" -> callback.success(
                                this.gson.toJson(cefCacheManager.keys(false, project))
                            )

                            "project.cache.exists" -> callback.success(
                                cefCacheManager.exists(false, project, queryCommand.commands[0]).toString()
                            )

                            "project.cache.size" -> callback.success(
                                cefCacheManager.size(false, project).toString()
                            )

                            "project.cache.clear" -> {
                                cefCacheManager.clear(false, project)
                                callback.success("success")
                            }

                            "project.cache.remove" -> {
                                cefCacheManager.remove(false, project, queryCommand.commands[0])
                                callback.success("success")
                            }

                            "project.cache.setAll" -> {
                                val data = this.gson.fromJson<Map<String, String>>(
                                    queryCommand.commands[0],
                                    object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                                )
                                cefCacheManager.setAll(false, project, data)
                                callback.success("success")
                            }
                        }
                    } catch (e: Throwable) {
                        callback.failure(500, e.fullMsg())
                    }
                    return true
                }
            })
            jbCefClient.cefClient.addMessageRouter(cefMessageRouter)
            jbCefClient.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
                override fun onKeyEvent(browser: CefBrowser, event: CefKeyboardHandler.CefKeyEvent): Boolean {
                    if (event.type == CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) {
                        val keyCode = event.windows_key_code
                        if (keyCode == KeyEvent.VK_F5 && !event.is_system_key) {
                            browser.reload()
                            return true
                        }
                        if (keyCode == KeyEvent.VK_F5 && (event.modifiers and EventFlags.EVENTFLAG_SHIFT_DOWN) != 0) {
                            browser.reloadIgnoreCache()
                            return true
                        }

                        if (keyCode == KeyEvent.VK_F12) {
                            jbBrowser.openDevtools()
                            return true
                        }
                    }
                    return false
                }
            }, jbBrowser.cefBrowser)
            jbCefClient.addDownloadHandler(object : CefDownloadHandlerAdapter() {
                override fun onBeforeDownload(
                    browser: CefBrowser?,
                    downloadItem: CefDownloadItem?,
                    suggestedName: String?,
                    callback: CefBeforeDownloadCallback?,
                ) {
                    callback?.Continue(suggestedName, true)
                }
            }, jbBrowser.cefBrowser)
            jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadStart(browser: CefBrowser, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
                    // 在页面开始加载时注入 SDK，确保用户代码执行前 JTools 已可用
                    if (frame?.isMain == true) {
                        injectJToolsSDK(browser)
                    }
                }
            }, jbBrowser.cefBrowser)

            jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun getResourceRequestHandler(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    isNavigation: Boolean,
                    isDownload: Boolean,
                    requestInitiator: String?,
                    disableDefaultHandling: BoolRef?,
                ): CefResourceRequestHandler? {
                    val url = request?.url ?: return null
                    // 只处理我们的虚拟域名和 cp:// 协议
                    if (url.startsWith("http://jtools.plugin", ignoreCase = true) ||
                        url.startsWith("cp://", ignoreCase = true)) {
                        return object : CefResourceRequestHandlerAdapter() {
                            override fun getResourceHandler(
                                browser: CefBrowser?,
                                frame: CefFrame?,
                                request: CefRequest,
                            ): org.cef.handler.CefResourceHandler {
                                return CefResourceHandler(request.url, classLoader)
                            }
                        }
                    }
                    return null
                }
            }, jbBrowser.cefBrowser)
            jbCefClient.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {

                override fun onBeforeContextMenu(
                    browser: CefBrowser,
                    frame: CefFrame?,
                    params: CefContextMenuParams,
                    model: CefMenuModel,
                ) {
                    //清除之前的按钮
                    model.clear()
                    if (browser.canGoBack()) {
                        model.addItem(1, "返回上一页")
                    }
                    model.addItem(2, "返回首页")
                }

                override fun onContextMenuCommand(
                    browser: CefBrowser,
                    frame: CefFrame?,
                    params: CefContextMenuParams?,
                    commandId: Int,
                    eventFlags: Int,
                ): Boolean {
                    //DevTools
                    if (commandId == 1) {
                        if (browser.canGoBack()) {
                            browser.goBack()
                        }
                    } else if (commandId == 2) {
                        browser.loadURL(normalizePluginUrl(cefPluginInfo.indexPage))
                    }
                    return true
                }


            }, jbBrowser.cefBrowser)
            val disposable = Disposer.newDisposable()
            Disposer.register(disposable, jbBrowser)
            disposables[project.locationHash] = disposable
            jbBrowser.loadURL(normalizePluginUrl(cefPluginInfo.indexPage))
            jbBrowser
        }.component
    }

    /**
     * 规范化插件 URL
     * 使用 http://jtools.local/ 虚拟域名，让 CEF 能正确解析相对路径
     */
    private fun normalizePluginUrl(url: String): String {
        return when {
            url.startsWith("http://", ignoreCase = true) -> url
            url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("cp://", ignoreCase = true) -> url
            // 使用虚拟的本地域名
            url.startsWith("/") -> "http://jtools.plugin$url"
            else -> "http://jtools.plugin/$url"
        }
    }

    override fun pluginType(): PluginType {
        return PluginType.JAVA
    }

    override fun openProject(project: Project, logger: Logger, openThisPage: Runnable) {
        loggerMap[project.locationHash] = logger
    }

    override fun closeProject(project: Project) {
        browsers.remove(project.locationHash)
        disposables.remove(project.locationHash)?.let { Disposer.dispose(it) }
    }

    override fun unInstall() {
        disposables.values.forEach { Disposer.dispose(it) }
        disposables.clear()
        browsers.clear()
    }

    override fun appClose() {
        disposables.values.forEach { Disposer.dispose(it) }
        disposables.clear()
        browsers.clear()
    }

    override fun pluginIcon(): Icon {
        return IconLoader.findIcon(cefPluginInfo.pluginIcon, classLoader.urlClassLoader) 
            ?: IconLoader.getIcon("/icons/plugin.svg", CefPluginImpl::class.java)
    }

    override fun pluginTabIcon(): Icon {
        return IconLoader.findIcon(cefPluginInfo.pluginTabIcon, classLoader.urlClassLoader)
            ?: IconLoader.getIcon("/icons/plugin.svg", CefPluginImpl::class.java)
    }

    override fun pluginName(): String {
        return cefPluginInfo.pluginName
    }

    override fun pluginDesc(): String {
        return cefPluginInfo.pluginDesc
    }

    override fun pluginVersion(): String {
        return cefPluginInfo.pluginVersion
    }

    /**
     * 注入 JTools SDK 到浏览器
     */
    private fun injectJToolsSDK(browser: CefBrowser) {
        val sdkScript = CefPluginImpl::class.java.getResourceAsStream("/js/jtools-sdk.js")?.use {
            String(it.readAllBytes(), StandardCharsets.UTF_8)
        }
        if (sdkScript != null) {
            browser.executeJavaScript(sdkScript, "jtools-sdk.js", 0)
        }
    }
}

class CefResourceHandler(private var url: String, private val classLoader: PluginClassLoader) :
    CefResourceHandlerAdapter() {
    private var bytes: ByteArray? = null
    private var offset: Int? = null
    private var isOpen: Boolean = false
    private var responseHeader: HashMap<String, String> = hashMapOf()
    private var httpResource = false
    private var resourcePath: String = ""

    init {
        try {
            resourcePath = parseResourcePath(url)
            if (resourcePath.isNotEmpty()) {
                bytes = classLoader.getResourceAsStream(resourcePath)?.use {
                    it.readAllBytes()
                }
                offset = 0
                isOpen = bytes != null
                
                // 调试：如果加载失败，打印日志
                if (!isOpen) {
                    System.err.println("[JTools] Resource not found: $resourcePath (original url: $url)")
                }
            }
        } catch (e: Throwable) {
            System.err.println("[JTools] Error loading resource: $url -> $e")
            isOpen = false
        }
    }

    /**
     * 解析 URL 为插件内部资源路径
     * 支持格式:
     * - cp://path/to/file.js                    -> path/to/file.js
     * - http://jtools.plugin/path/to/file       -> path/to/file
     */
    private fun parseResourcePath(url: String): String {
        val path = when {
            // 兼容旧的 cp:// 协议
            url.startsWith("cp://", ignoreCase = true) -> {
                url.substring("cp://".length)
            }
            // http://jtools.plugin/ 虚拟域名
            url.startsWith("http://jtools.plugin/", ignoreCase = true) -> {
                url.substring("http://jtools.plugin/".length)
            }
            url.startsWith("http://jtools.plugin", ignoreCase = true) -> {
                url.substring("http://jtools.plugin".length)
            }
            // 其他情况
            url.contains("://") -> {
                url.substringAfter("://").substringAfter("/", "")
            }
            else -> url
        }
        
        // 移除开头的斜杠，统一为相对路径；移除查询参数
        return path.removePrefix("/").substringBefore("?").substringBefore("#")
    }


    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        callback?.Continue()
        return isOpen
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
        if (httpResource) {
            responseHeader.forEach { (k, v) -> response.setHeaderByName(k, v, true) }
        } else {
            // 使用 resourcePath 来判断 MIME 类型
            response.mimeType = when {
                resourcePath.endsWithIgnoreCase(".html") -> "text/html"
                resourcePath.endsWithIgnoreCase(".css") -> "text/css"
                resourcePath.endsWithIgnoreCase(".js") -> "application/javascript"
                resourcePath.endsWithIgnoreCase(".json") -> "application/json"
                resourcePath.endsWithIgnoreCase(".png") -> "image/png"
                resourcePath.lowercase().endsWithAny(".jpg", ".jpeg") -> "image/jpeg"
                resourcePath.endsWithIgnoreCase(".gif") -> "image/gif"
                resourcePath.endsWithIgnoreCase(".svg") -> "image/svg+xml"
                resourcePath.endsWithIgnoreCase(".ico") -> "image/x-icon"
                resourcePath.endsWithIgnoreCase(".webp") -> "image/webp"
                resourcePath.endsWithIgnoreCase(".woff") -> "font/woff"
                resourcePath.endsWithIgnoreCase(".woff2") -> "font/woff2"
                resourcePath.endsWithIgnoreCase(".ttf") -> "font/ttf"
                resourcePath.endsWithIgnoreCase(".eot") -> "application/vnd.ms-fontobject"
                resourcePath.endsWithIgnoreCase(".xml") -> "application/xml"
                resourcePath.endsWithIgnoreCase(".txt") -> "text/plain"
                resourcePath.endsWithIgnoreCase(".md") -> "text/markdown"
                resourcePath.endsWithIgnoreCase(".pdf") -> "application/pdf"
                resourcePath.endsWithIgnoreCase(".zip") -> "application/zip"
                resourcePath.endsWithIgnoreCase(".mp3") -> "audio/mpeg"
                resourcePath.endsWithIgnoreCase(".mp4") -> "video/mp4"
                resourcePath.endsWithIgnoreCase(".webm") -> "video/webm"
                else -> "application/octet-stream"
            }
            response.status = if (isOpen) 200 else 404
            bytes?.size?.let { responseLength.set(it) }
        }
    }

    override fun readResponse(buffer: ByteArray, bufferSize: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        if (!isOpen) {
            callback.cancel()
            return false
        }

        val bytesToRead = min(bufferSize.toDouble(), (bytes!!.size - offset!!).toDouble()).toInt()
        if (bytesToRead <= 0) {
            callback.Continue()
            return false
        }

        System.arraycopy(bytes!!, offset!!, buffer, 0, bytesToRead)
        offset = offset!! + bytesToRead
        bytesRead.set(bytesToRead)

        if (offset!! >= bytes!!.size) {
            isOpen = false
        }
        callback.Continue()
        return true
    }

    override fun cancel() {
        // 处理取消请求的逻辑
        isOpen = false
    }
}

class CefQueryCommand(val type: String, val commands: Array<String>) {

}

interface CefCacheManager {
    fun set(global: Boolean, project: Project, key: String, value: String)

    fun get(global: Boolean, project: Project, key: String): String?

    fun getOrDefault(global: Boolean, project: Project, key: String, defaultValue: String): String

    fun getAll(global: Boolean, project: Project): Map<String, String>

    fun keys(global: Boolean, project: Project): Set<String>

    fun exists(global: Boolean, project: Project, key: String): Boolean

    fun clear(global: Boolean, project: Project)

    fun remove(global: Boolean, project: Project, key: String)

    fun setAll(global: Boolean, project: Project, data: Map<String, String>)

    fun size(global: Boolean, project: Project): Int
}

class CefPluginCefCacheManager(val pluginInfo: PluginInfo) : CefCacheManager {

    private fun getCache(global: Boolean, project: Project): HashMap<String, String> {
        return if (global) {
            CefPluginCacheState.getInstance().jsPluginCache.computeIfAbsent(pluginInfo.id) { hashMapOf() }
        } else {
            CefPluginCacheState.getInstance(project).jsPluginCache.computeIfAbsent(pluginInfo.id) { hashMapOf() }
        }
    }

    override fun set(global: Boolean, project: Project, key: String, value: String) {
        getCache(global, project)[key] = value
    }

    override fun get(global: Boolean, project: Project, key: String): String? {
        return getCache(global, project)[key]
    }

    override fun getOrDefault(global: Boolean, project: Project, key: String, defaultValue: String): String {
        return getCache(global, project)[key] ?: defaultValue
    }

    override fun getAll(global: Boolean, project: Project): Map<String, String> {
        return getCache(global, project).toMap()
    }

    override fun keys(global: Boolean, project: Project): Set<String> {
        return getCache(global, project).keys.toSet()
    }

    override fun exists(global: Boolean, project: Project, key: String): Boolean {
        return getCache(global, project).containsKey(key)
    }

    override fun clear(global: Boolean, project: Project) {
        getCache(global, project).clear()
    }

    override fun remove(global: Boolean, project: Project, key: String) {
        getCache(global, project).remove(key)
    }

    override fun setAll(global: Boolean, project: Project, data: Map<String, String>) {
        getCache(global, project).putAll(data)
    }

    override fun size(global: Boolean, project: Project): Int {
        return getCache(global, project).size
    }
}

// JS 插件 API 文档
private val JS_PLUGIN_API_DOC = listOf(
    // 帮助
    mapOf(
        "name" to "help",
        "category" to "帮助",
        "description" to "获取所有 API 文档（JSON 格式）",
        "params" to emptyList<String>(),
        "returns" to "API 文档数组",
        "example" to """cefQuery({request:JSON.stringify({type:"help",commands:[]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "help.markdown",
        "category" to "帮助",
        "description" to "获取所有 API 文档（Markdown 格式）",
        "params" to emptyList<String>(),
        "returns" to "Markdown 格式的文档",
        "example" to """cefQuery({request:JSON.stringify({type:"help.markdown",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "help.list",
        "category" to "帮助",
        "description" to "获取所有 API 名称列表",
        "params" to emptyList<String>(),
        "returns" to "API 名称数组",
        "example" to """cefQuery({request:JSON.stringify({type:"help.list",commands:[]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),

    // 日志
    mapOf(
        "name" to "log",
        "category" to "日志",
        "description" to "输出日志到 JTools 控制台",
        "params" to listOf("level: debug|info|warn|error", "message: 日志内容"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"log",commands:["info","Hello World"]}),onSuccess:res=>console.log(res)})"""
    ),

    // 插件信息
    mapOf(
        "name" to "getPluginInfo",
        "category" to "插件信息",
        "description" to "获取当前插件信息",
        "params" to emptyList<String>(),
        "returns" to "{pluginIcon, pluginTabIcon, pluginName, pluginDesc, pluginVersion, indexPage}",
        "example" to """cefQuery({request:JSON.stringify({type:"getPluginInfo",commands:[]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "getJToolsVersion",
        "category" to "插件信息",
        "description" to "获取 JTools 版本号",
        "params" to emptyList<String>(),
        "returns" to "版本号数字，如 1122",
        "example" to """cefQuery({request:JSON.stringify({type:"getJToolsVersion",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "getIdeInfo",
        "category" to "插件信息",
        "description" to "获取 IDE 信息",
        "params" to emptyList<String>(),
        "returns" to "{apiVersion, fullVersion, majorVersion, minorVersion, buildBaselineVersion, versionName, fullApplicationName}",
        "example" to """cefQuery({request:JSON.stringify({type:"getIdeInfo",commands:[]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "getProjectBasePath",
        "category" to "插件信息",
        "description" to "获取当前项目路径",
        "params" to emptyList<String>(),
        "returns" to "项目绝对路径",
        "example" to """cefQuery({request:JSON.stringify({type:"getProjectBasePath",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),

    // 系统信息
    mapOf(
        "name" to "getSysEnv",
        "category" to "系统信息",
        "description" to "获取系统环境变量",
        "params" to listOf("name: 环境变量名"),
        "returns" to "环境变量值",
        "example" to """cefQuery({request:JSON.stringify({type:"getSysEnv",commands:["PATH"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "getSysProperty",
        "category" to "系统信息",
        "description" to "获取 Java 系统属性",
        "params" to listOf("name: 属性名"),
        "returns" to "属性值",
        "example" to """cefQuery({request:JSON.stringify({type:"getSysProperty",commands:["user.home"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "getOsInfo",
        "category" to "系统信息",
        "description" to "获取操作系统信息",
        "params" to emptyList<String>(),
        "returns" to "{name, version, arch, userHome, userName, javaVersion, javaVendor, fileSeparator, lineSeparator, tempDir}",
        "example" to """cefQuery({request:JSON.stringify({type:"getOsInfo",commands:[]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "currentTimeMillis",
        "category" to "系统信息",
        "description" to "获取当前时间戳（毫秒）",
        "params" to emptyList<String>(),
        "returns" to "时间戳字符串",
        "example" to """cefQuery({request:JSON.stringify({type:"currentTimeMillis",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),

    // 文件读取
    mapOf(
        "name" to "readSysFile",
        "category" to "文件操作",
        "description" to "读取系统文件内容",
        "params" to listOf("path: 文件绝对路径"),
        "returns" to "文件内容",
        "example" to """cefQuery({request:JSON.stringify({type:"readSysFile",commands:["/path/to/file.txt"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "readPluginFile",
        "category" to "文件操作",
        "description" to "读取插件内部文件",
        "params" to listOf("path: 插件内相对路径"),
        "returns" to "文件内容",
        "example" to """cefQuery({request:JSON.stringify({type:"readPluginFile",commands:["config.json"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "writeSysFile",
        "category" to "文件操作",
        "description" to "写入系统文件",
        "params" to listOf("path: 文件路径", "content: 文件内容", "append?: 是否追加(默认false)"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"writeSysFile",commands:["/path/to/file.txt","Hello World","false"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "fileExists",
        "category" to "文件操作",
        "description" to "检查文件或目录是否存在",
        "params" to listOf("path: 路径"),
        "returns" to "true/false",
        "example" to """cefQuery({request:JSON.stringify({type:"fileExists",commands:["/path/to/file"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "isDirectory",
        "category" to "文件操作",
        "description" to "检查路径是否是目录",
        "params" to listOf("path: 路径"),
        "returns" to "true/false",
        "example" to """cefQuery({request:JSON.stringify({type:"isDirectory",commands:["/path/to/dir"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "listDir",
        "category" to "文件操作",
        "description" to "列出目录内容",
        "params" to listOf("path: 目录路径"),
        "returns" to "[{name, path, isDirectory, size, lastModified}]",
        "example" to """cefQuery({request:JSON.stringify({type:"listDir",commands:["/path/to/dir"]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "createDir",
        "category" to "文件操作",
        "description" to "创建目录（包括父目录）",
        "params" to listOf("path: 目录路径"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"createDir",commands:["/path/to/new/dir"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "deleteFile",
        "category" to "文件操作",
        "description" to "删除文件或目录（递归删除）",
        "params" to listOf("path: 路径"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"deleteFile",commands:["/path/to/file"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "copyFile",
        "category" to "文件操作",
        "description" to "复制文件",
        "params" to listOf("source: 源文件路径", "target: 目标路径"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"copyFile",commands:["/source/file","/target/file"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "moveFile",
        "category" to "文件操作",
        "description" to "移动/重命名文件",
        "params" to listOf("source: 源路径", "target: 目标路径"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"moveFile",commands:["/old/path","/new/path"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "getFileInfo",
        "category" to "文件操作",
        "description" to "获取文件详细信息",
        "params" to listOf("path: 文件路径"),
        "returns" to "{name, path, absolutePath, isDirectory, isFile, size, lastModified, readable, writable, executable}",
        "example" to """cefQuery({request:JSON.stringify({type:"getFileInfo",commands:["/path/to/file"]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),

    // 剪贴板
    mapOf(
        "name" to "copyToClipboard",
        "category" to "剪贴板",
        "description" to "复制文本到剪贴板",
        "params" to listOf("content: 要复制的内容"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"copyToClipboard",commands:["Hello World"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "getFromClipboard",
        "category" to "剪贴板",
        "description" to "从剪贴板获取文本",
        "params" to emptyList<String>(),
        "returns" to "剪贴板内容",
        "example" to """cefQuery({request:JSON.stringify({type:"getFromClipboard",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),

    // 通知
    mapOf(
        "name" to "notify",
        "category" to "通知",
        "description" to "发送 IDE 通知",
        "params" to listOf("title: 标题", "content: 内容", "type?: INFORMATION|WARNING|ERROR (默认INFORMATION)"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"notify",commands:["标题","内容","INFORMATION"]}),onSuccess:res=>console.log(res)})"""
    ),

    // 对话框
    mapOf(
        "name" to "showInputDialog",
        "category" to "对话框",
        "description" to "显示输入对话框",
        "params" to listOf("message?: 提示信息", "title?: 标题", "initialValue?: 默认值"),
        "returns" to "用户输入内容（取消返回空字符串）",
        "example" to """cefQuery({request:JSON.stringify({type:"showInputDialog",commands:["请输入名称","输入","默认值"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "showConfirmDialog",
        "category" to "对话框",
        "description" to "显示确认对话框",
        "params" to listOf("message?: 提示信息", "title?: 标题"),
        "returns" to "0=Yes, 1=No, 2=Cancel",
        "example" to """cefQuery({request:JSON.stringify({type:"showConfirmDialog",commands:["确定要删除吗？","确认"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "showMessageDialog",
        "category" to "对话框",
        "description" to "显示消息对话框",
        "params" to listOf("message?: 消息内容", "title?: 标题", "type?: info|warning|error"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"showMessageDialog",commands:["操作成功","提示","info"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "chooseFile",
        "category" to "对话框",
        "description" to "文件选择对话框",
        "params" to listOf("title?: 对话框标题"),
        "returns" to "选中的文件路径（取消返回空字符串）",
        "example" to """cefQuery({request:JSON.stringify({type:"chooseFile",commands:["选择文件"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "chooseFiles",
        "category" to "对话框",
        "description" to "多文件选择对话框",
        "params" to listOf("title?: 对话框标题"),
        "returns" to "选中的文件路径数组",
        "example" to """cefQuery({request:JSON.stringify({type:"chooseFiles",commands:["选择文件"]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "chooseDirectory",
        "category" to "对话框",
        "description" to "目录选择对话框",
        "params" to listOf("title?: 对话框标题"),
        "returns" to "选中的目录路径（取消返回空字符串）",
        "example" to """cefQuery({request:JSON.stringify({type:"chooseDirectory",commands:["选择目录"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "saveFileDialog",
        "category" to "对话框",
        "description" to "保存文件对话框",
        "params" to listOf("title?: 标题", "description?: 描述", "defaultFilename?: 默认文件名"),
        "returns" to "保存路径（取消返回空字符串）",
        "example" to """cefQuery({request:JSON.stringify({type:"saveFileDialog",commands:["保存文件","","output.txt"]}),onSuccess:res=>console.log(res)})"""
    ),

    // 编辑器
    mapOf(
        "name" to "openFileInEditor",
        "category" to "编辑器",
        "description" to "在 IDE 编辑器中打开文件",
        "params" to listOf("path: 文件路径"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"openFileInEditor",commands:["/path/to/file.txt"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "refreshProject",
        "category" to "编辑器",
        "description" to "刷新项目文件",
        "params" to emptyList<String>(),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"refreshProject",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),

    // 系统操作
    mapOf(
        "name" to "openUrl",
        "category" to "系统操作",
        "description" to "在浏览器中打开 URL",
        "params" to listOf("url: 网址"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"openUrl",commands:["https://www.google.com"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "executeCommand",
        "category" to "系统操作",
        "description" to "执行系统命令",
        "params" to listOf("command: 命令", "workDir?: 工作目录(默认项目目录)", "timeout?: 超时毫秒(默认30000)"),
        "returns" to "{exitCode, output}",
        "example" to """cefQuery({request:JSON.stringify({type:"executeCommand",commands:["ls -la","/tmp","5000"]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),

    // 全局缓存
    mapOf(
        "name" to "global.cache.set",
        "category" to "全局缓存",
        "description" to "设置全局缓存（跨项目）",
        "params" to listOf("key: 键", "value: 值"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.set",commands:["myKey","myValue"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "global.cache.get",
        "category" to "全局缓存",
        "description" to "获取全局缓存",
        "params" to listOf("key: 键"),
        "returns" to "缓存值",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.get",commands:["myKey"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "global.cache.getOrDefault",
        "category" to "全局缓存",
        "description" to "获取全局缓存，不存在返回默认值",
        "params" to listOf("key: 键", "defaultValue?: 默认值"),
        "returns" to "缓存值或默认值",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.getOrDefault",commands:["myKey","default"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "global.cache.getAll",
        "category" to "全局缓存",
        "description" to "获取所有全局缓存",
        "params" to emptyList<String>(),
        "returns" to "{key: value, ...}",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.getAll",commands:[]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "global.cache.keys",
        "category" to "全局缓存",
        "description" to "获取所有全局缓存键",
        "params" to emptyList<String>(),
        "returns" to "[key1, key2, ...]",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.keys",commands:[]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "global.cache.exists",
        "category" to "全局缓存",
        "description" to "检查全局缓存键是否存在",
        "params" to listOf("key: 键"),
        "returns" to "true/false",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.exists",commands:["myKey"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "global.cache.size",
        "category" to "全局缓存",
        "description" to "获取全局缓存数量",
        "params" to emptyList<String>(),
        "returns" to "数量",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.size",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "global.cache.remove",
        "category" to "全局缓存",
        "description" to "删除全局缓存",
        "params" to listOf("key: 键"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.remove",commands:["myKey"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "global.cache.clear",
        "category" to "全局缓存",
        "description" to "清空所有全局缓存",
        "params" to emptyList<String>(),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.clear",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "global.cache.setAll",
        "category" to "全局缓存",
        "description" to "批量设置全局缓存",
        "params" to listOf("data: JSON字符串 {key:value,...}"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"global.cache.setAll",commands:[JSON.stringify({a:"1",b:"2"})]}),onSuccess:res=>console.log(res)})"""
    ),

    // 项目缓存
    mapOf(
        "name" to "project.cache.set",
        "category" to "项目缓存",
        "description" to "设置项目缓存（仅当前项目）",
        "params" to listOf("key: 键", "value: 值"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.set",commands:["myKey","myValue"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "project.cache.get",
        "category" to "项目缓存",
        "description" to "获取项目缓存",
        "params" to listOf("key: 键"),
        "returns" to "缓存值",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.get",commands:["myKey"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "project.cache.getOrDefault",
        "category" to "项目缓存",
        "description" to "获取项目缓存，不存在返回默认值",
        "params" to listOf("key: 键", "defaultValue?: 默认值"),
        "returns" to "缓存值或默认值",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.getOrDefault",commands:["myKey","default"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "project.cache.getAll",
        "category" to "项目缓存",
        "description" to "获取所有项目缓存",
        "params" to emptyList<String>(),
        "returns" to "{key: value, ...}",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.getAll",commands:[]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "project.cache.keys",
        "category" to "项目缓存",
        "description" to "获取所有项目缓存键",
        "params" to emptyList<String>(),
        "returns" to "[key1, key2, ...]",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.keys",commands:[]}),onSuccess:res=>console.log(JSON.parse(res))})"""
    ),
    mapOf(
        "name" to "project.cache.exists",
        "category" to "项目缓存",
        "description" to "检查项目缓存键是否存在",
        "params" to listOf("key: 键"),
        "returns" to "true/false",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.exists",commands:["myKey"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "project.cache.size",
        "category" to "项目缓存",
        "description" to "获取项目缓存数量",
        "params" to emptyList<String>(),
        "returns" to "数量",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.size",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "project.cache.remove",
        "category" to "项目缓存",
        "description" to "删除项目缓存",
        "params" to listOf("key: 键"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.remove",commands:["myKey"]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "project.cache.clear",
        "category" to "项目缓存",
        "description" to "清空所有项目缓存",
        "params" to emptyList<String>(),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.clear",commands:[]}),onSuccess:res=>console.log(res)})"""
    ),
    mapOf(
        "name" to "project.cache.setAll",
        "category" to "项目缓存",
        "description" to "批量设置项目缓存",
        "params" to listOf("data: JSON字符串 {key:value,...}"),
        "returns" to "success",
        "example" to """cefQuery({request:JSON.stringify({type:"project.cache.setAll",commands:[JSON.stringify({a:"1",b:"2"})]}),onSuccess:res=>console.log(res)})"""
    )
)

// Markdown 格式的 API 文档
private val JS_PLUGIN_API_MARKDOWN: String by lazy {
    val sb = StringBuilder()
    sb.appendLine("# JTools JS 插件 API 文档")
    sb.appendLine()
    sb.appendLine("## 调用方式")
    sb.appendLine("```javascript")
    sb.appendLine("""cefQuery({""")
    sb.appendLine("""  request: JSON.stringify({type: "API名称", commands: ["参数1", "参数2"]}),""")
    sb.appendLine("""  onSuccess: res => console.log(res),""")
    sb.appendLine("""  onFailure: (code, msg) => console.error(code, msg)""")
    sb.appendLine("""})""")
    sb.appendLine("```")
    sb.appendLine()

    // 按分类分组
    val grouped = JS_PLUGIN_API_DOC.groupBy { it["category"] as String }
    
    grouped.forEach { (category, apis) ->
        sb.appendLine("## $category")
        sb.appendLine()
        sb.appendLine("| API | 描述 | 参数 | 返回值 |")
        sb.appendLine("|-----|------|------|--------|")
        apis.forEach { api ->
            val name = api["name"]
            val desc = api["description"]
            val params = (api["params"] as List<*>).joinToString(", ").ifEmpty { "-" }
            val returns = api["returns"]
            sb.appendLine("| `$name` | $desc | $params | $returns |")
        }
        sb.appendLine()
    }

    sb.appendLine("## 示例")
    sb.appendLine()
    grouped.entries.take(3).forEach { (category, apis) ->
        sb.appendLine("### $category")
        apis.take(2).forEach { api ->
            sb.appendLine("```javascript")
            sb.appendLine("// ${api["description"]}")
            sb.appendLine(api["example"])
            sb.appendLine("```")
        }
        sb.appendLine()
    }

    sb.toString()
}
