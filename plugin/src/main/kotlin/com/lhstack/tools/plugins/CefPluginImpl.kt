package com.lhstack.tools.plugins

import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.jcef.*
import com.lhstack.tools.ext.gson
import org.apache.commons.lang3.StringUtils
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.*
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.nio.charset.StandardCharsets
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
)

class CefPluginImpl(private val classLoader: ClassLoader) : IPlugin {

    private val cefPluginInfo: CefPluginInfo

    private val browsers: HashMap<String, JBCefBrowser> = hashMapOf()

    private val cefClients: HashMap<String, JBCefClient> = hashMapOf()


    private val httpClients: HashMap<String, CloseableHttpClient> = hashMapOf()

    init {
        val resource =
            classLoader.getResourceAsStream("pluginInfo.json") ?: throw RuntimeException("pluginInfo.json cannot null")
        resource.use {
            cefPluginInfo = String(it.readAllBytes(), StandardCharsets.UTF_8).let { s ->
                GsonBuilder().create().fromJson(s, CefPluginInfo::class.java)
            }
        }

    }

    override fun createPanel(project: Project): JComponent {
        return browsers.computeIfAbsent(project.locationHash) { key ->
            val jbCefApp = JBCefApp.getInstance()
            val jbCefClient = jbCefApp.createClient()
            val jbBrowser = JBCefBrowser.createBuilder()
                .setOffScreenRendering(false)
                .setClient(jbCefClient).build()
            jbCefClient.addDownloadHandler(object : CefDownloadHandlerAdapter() {
                override fun onBeforeDownload(
                    browser: CefBrowser?,
                    downloadItem: CefDownloadItem?,
                    suggestedName: String?,
                    callback: CefBeforeDownloadCallback?
                ) {
                    callback?.Continue(suggestedName, true)
                }
            }, jbBrowser.cefBrowser)
            val functions = jsFunctions(jbBrowser, cefPluginInfo)
            jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(
                    browser: CefBrowser?,
                    isLoading: Boolean,
                    canGoBack: Boolean,
                    canGoForward: Boolean
                ) {
                    if (isLoading) {
                        val script = functions.joinToString("\r\n")
                        browser?.executeJavaScript(script, "cp://index.html", 0)
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
                    disableDefaultHandling: BoolRef?
                ): CefResourceRequestHandler {
                    return object : CefResourceRequestHandlerAdapter() {
                        override fun getResourceHandler(
                            browser: CefBrowser?,
                            frame: CefFrame?,
                            request: CefRequest
                        ): org.cef.handler.CefResourceHandler {
                            return CefResourceHandler(request.url, classLoader, httpClients[project.locationHash]!!)
                        }
                    }
                }
            }, jbBrowser.cefBrowser)
            jbCefClient.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {

                override fun onBeforeContextMenu(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    params: CefContextMenuParams,
                    model: CefMenuModel
                ) {
                    //清除之前的按钮
                    model.clear()
                    model.addItem(1, "DevTools")
                    model.addItem(2, "重新加载")
                }

                override fun onContextMenuCommand(
                    browser: CefBrowser,
                    frame: CefFrame?,
                    params: CefContextMenuParams?,
                    commandId: Int,
                    eventFlags: Int
                ): Boolean {
                    //DevTools
                    if (commandId == 1) {
                        SwingUtilities.invokeLater { jbBrowser.openDevtools() }
                    }else if(commandId == 2){
                        browser.reload()
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
            jbBrowser.loadURL("cp://index.html")
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
        return IconLoader.findIcon(cefPluginInfo.pluginIcon, classLoader)
    }

    override fun pluginTabIcon(): Icon? {
        return IconLoader.findIcon(cefPluginInfo.pluginTabIcon, classLoader)
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

class CefResourceHandler(private var url: String, classLoader: ClassLoader, httpClient: CloseableHttpClient) :
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

fun jsFunctions(cefBrowserBase: JBCefBrowserBase, cefPluginInfo: CefPluginInfo): Set<String> {
    val getPluginInfo = JBCefJSQuery.create(cefBrowserBase)
    getPluginInfo.addHandler {
        JBCefJSQuery.Response(it.gson.toJson(cefPluginInfo))
    }
    val scripts = mutableSetOf<String>()
    scripts.add(
        """
        window.pluginInfo = function(success,fail){
            ${getPluginInfo.inject("", "success", "fail")}
        };
    """.trimIndent()
    )
    return scripts
}