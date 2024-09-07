package com.lhstack.tools.plugins

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColorChooser
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefClient
import com.jetbrains.rd.util.AtomicReference
import com.lhstack.tools.ext.fullMsg
import com.lhstack.tools.ext.gson
import org.apache.commons.lang3.StringUtils
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
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
import java.awt.event.KeyEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.min


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

    private val cefClients: HashMap<String, JBCefClient> = hashMapOf()

    private val httpClients: HashMap<String, CloseableHttpClient> = hashMapOf()

    private val backgroundColor: AtomicReference<String> = AtomicReference("")

    private val fontColor: AtomicReference<String> = AtomicReference("")

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
                        //cefQuery({request:"getPluginInfo",onSuccess: res => console.log(res),onFailure: (code,msg) => console.log(code,msg)})
                        when (queryCommand.type) {
                            "getPluginInfo" -> callback.success(this.gson.toJson(cefPluginInfo))
                            "global.cache.set" -> {
                                cefCacheManager.set(true, project, queryCommand.commands[0], queryCommand.commands[1])
                                callback.success("success")
                            }

                            "global.cache.get" -> callback.success(
                                cefCacheManager.get(
                                    true,
                                    project,
                                    queryCommand.commands[0]
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

                            "global.cache.clear" -> {
                                cefCacheManager.clear(true, project)
                                callback.success("success")
                            }

                            "global.cache.remove" -> {
                                cefCacheManager.remove(true, project, queryCommand.commands[0])
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

                            "project.cache.clear" -> {
                                cefCacheManager.clear(false, project)
                                callback.success("success")
                            }

                            "project.cache.remove" -> {
                                cefCacheManager.remove(false, project, queryCommand.commands[0])
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
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame?, httpStatusCode: Int) {
                    var bgColor = backgroundColor.get()
                    var color = fontColor.get()
                    if (StringUtils.isNotBlank(color) || StringUtils.isNotBlank(bgColor)) {
                        if (StringUtils.isNotBlank(color)) {
                            color = " color: $color !important;"
                        }
                        if (StringUtils.isNotBlank(bgColor)) {
                            bgColor = " background-color: $bgColor !important;"
                        }
                        browser.executeJavaScript(
                            """
                            var style = document.createElement('style');
                            style.innerHTML = '* { $color $bgColor }';
                            document.head.appendChild(style);
                        """.trimIndent(), browser.url, 0
                        )
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
                    if (StringUtils.startsWithAny(request?.url, "https://", "http://")) {
                        return null
                    }
                    return object : CefResourceRequestHandlerAdapter() {

                        override fun onBeforeResourceLoad(
                            browser: CefBrowser?,
                            frame: CefFrame?,
                            request: CefRequest?,
                        ): Boolean {
                            return super.onBeforeResourceLoad(browser, frame, request)
                        }

                        override fun getResourceHandler(
                            browser: CefBrowser?,
                            frame: CefFrame?,
                            request: CefRequest,
                        ): org.cef.handler.CefResourceHandler {
                            return CefResourceHandler(request.url, classLoader, httpClients[project.locationHash]!!)
                        }
                    }
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
                        model.addItem(1, "goBack")
                    }
                    model.addItem(2, "返回首页")
                    model.addItem(3, "自定义背景颜色")
                    model.addItem(4, "自定义字体颜色")
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
                        browser.loadURL(cefPluginInfo.indexPage)
                    } else if (commandId == 3) {
                        SwingUtilities.invokeLater {
                            val color = ColorChooser.chooseColor(jbBrowser.component, "自定义背景色", JBColor.BLACK)
                            color?.let {
                                var thisFontColor = backgroundColor.get()
                                if (StringUtils.isNotBlank(thisFontColor)) {
                                    thisFontColor = " color: $thisFontColor !important;"
                                }
                                backgroundColor.getAndSet(
                                    "rgba(%d, %d, %d, %.2f)".format(
                                        it.red,
                                        it.green,
                                        it.blue,
                                        it.alpha / 255.0
                                    )
                                )
                                browser.executeJavaScript(
                                    """
                                    var style = document.createElement('style');
                                    style.innerHTML = '* { background-color: ${backgroundColor.get()} !important;$thisFontColor }';
                                    document.head.appendChild(style);
                                """.trimIndent(), browser.url, 0
                                )
                            }
                        }
                    } else if (commandId == 4) {
                        SwingUtilities.invokeLater {
                            val color = ColorChooser.chooseColor(jbBrowser.component, "自定义字体颜色", JBColor.BLACK)
                            color?.let {
                                var bgColor = backgroundColor.get()
                                if (StringUtils.isNotBlank(bgColor)) {
                                    bgColor = " background-color: $bgColor !important;"
                                }
                                fontColor.getAndSet(
                                    "rgba(%d, %d, %d, %.2f)".format(
                                        it.red,
                                        it.green,
                                        it.blue,
                                        it.alpha / 255.0
                                    )
                                )
                                browser.executeJavaScript(
                                    """
                                    var style = document.createElement('style');
                                    style.innerHTML = '* { color: ${fontColor.get()} !important; ${bgColor}}';
                                    document.head.appendChild(style);
                                """.trimIndent(), browser.url, 0
                                )
                            }
                        }
                    }
                    return true
                }


            }, jbBrowser.cefBrowser)
            Disposer.register(project) {
                jbBrowser.dispose()
                jbCefClient.dispose()
            }
            cefClients[project.locationHash] = jbCefClient
            httpClients[project.locationHash] = HttpClients.createSystem()
            jbBrowser.loadURL(cefPluginInfo.indexPage)
            jbBrowser
        }.component
    }

    override fun pluginType(): PluginType {
        return PluginType.JS
    }

    override fun openProject(project: Project, openThisPage: Runnable) {

    }

    override fun closeProject(project: Project) {
        browsers.remove(project.locationHash)
        httpClients.remove(project.locationHash)?.close()
    }

    override fun unInstall() {
        browsers.forEach { (k, v) -> v.dispose() }
        cefClients.forEach { (k, v) -> v.dispose() }
        httpClients.forEach { (k, v) -> v.close() }
    }

    override fun appClose() {
        browsers.clear()
        httpClients.clear()
    }

    override fun pluginIcon(): Icon? {
        return IconLoader.findIcon(cefPluginInfo.pluginIcon, classLoader.urlClassLoader)
    }

    override fun pluginTabIcon(): Icon? {
        return IconLoader.findIcon(cefPluginInfo.pluginTabIcon, classLoader.urlClassLoader)
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
}

class CefResourceHandler(private var url: String, classLoader: PluginClassLoader, httpClient: CloseableHttpClient) :
    CefResourceHandlerAdapter() {
    private var bytes: ByteArray? = null
    private var offset: Int? = null
    private var isOpen: Boolean = false
    private var responseHeader: HashMap<String, String> = hashMapOf()
    private var httpResource = false

    init {
        try {
            if (url.startsWith("cp://", ignoreCase = true)) {
                url = url.substring("cp://".length)
                bytes = classLoader.getResourceAsStream(url)?.use {
                    it.readAllBytes()
                }
            } else {
                //需要http客户端
                val httpResponse = httpClient.execute(HttpGet(url))
                httpResponse.allHeaders.forEach { responseHeader[it.name] = it.value }
                bytes = EntityUtils.toByteArray(httpResponse.entity)
                httpResource = true
            }
            offset = 0
            isOpen = true
        } catch (e: Throwable) {
            isOpen = false
        }
    }


    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        callback?.Continue()
        return isOpen
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
        if (httpResource) {
            responseHeader.forEach { (k, v) -> response.setHeaderByName(k, v, true) }
        } else {
            if (StringUtils.endsWithIgnoreCase(url, ".html")) {
                response.mimeType = "text/html"
            } else if (StringUtils.endsWithIgnoreCase(url, ".css")) {
                response.mimeType = "text/css"
            } else if (StringUtils.endsWithIgnoreCase(url, ".js")) {
                response.mimeType = "application/javascript"
            } else if (StringUtils.endsWithIgnoreCase(url, ".png")) {
                response.mimeType = "image/png"
            } else if (StringUtils.endsWithAny(url.lowercase(), ".jpg", "jpeg")) {
                response.mimeType = "image/jpg"
            } else if (StringUtils.endsWithIgnoreCase(url, ".gif")) {
                response.mimeType = "image/gif"
            }
            response.status = 200
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

    fun getAll(global: Boolean, project: Project): Map<String, String>

    fun clear(global: Boolean, project: Project)

    fun remove(global: Boolean, project: Project, key: String)
}

class CefPluginCefCacheManager(val pluginInfo: PluginInfo) : CefCacheManager {
    override fun set(global: Boolean, project: Project, key: String, value: String) {
        val jsCache = if (global) {
            this.pluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        } else {
            project.projectPluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        }
        jsCache[key] = value
    }

    override fun get(global: Boolean, project: Project, key: String): String? {
        val jsCache = if (global) {
            this.pluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        } else {
            project.projectPluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        }
        return jsCache[key]
    }

    override fun getAll(global: Boolean, project: Project): Map<String, String> {
        return if (global) {
            this.pluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        } else {
            project.projectPluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        }
    }

    override fun clear(global: Boolean, project: Project) {
        val jsCache = if (global) {
            this.pluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        } else {
            project.projectPluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        }
        jsCache.clear()
    }

    override fun remove(global: Boolean, project: Project, key: String) {
        val jsCache = if (global) {
            this.pluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        } else {
            project.projectPluginState().jsPluginCache.computeIfAbsent(pluginInfo.id) {
                hashMapOf()
            }
        }
        jsCache.remove(key)
    }

}