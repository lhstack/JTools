package com.lhstack.tools.agent

import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ui.jcef.JBCefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefDragHandler
import org.cef.handler.CefKeyboardHandler
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import org.cef.callback.CefDragData
import java.awt.event.KeyEvent
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.JLabel

internal class AgentChatBrowser(
    private val gson: Gson,
    private val onCommand: (AgentBrowserCommand) -> Any?,
    private val page: String = "chat",
    private val onReady: (() -> Unit)? = null,
    private val onDropFiles: ((List<java.io.File>) -> Unit)? = null,
    private val onEscapeKey: (() -> Boolean)? = null,
) : Disposable {
    private val browser: JBCefBrowser?
    private var ready = false
    private var pendingState: Any? = null
    private val renderTimer = javax.swing.Timer(40) { flushState() }.apply { isRepeats = false }
    val component: JComponent

    init {
        if (!JBCefApp.isSupported()) {
            browser = null
            component = JLabel("当前 IDE Runtime 不支持 JCEF，无法打开 Agent 对话界面")
        } else {
            val app = JBCefApp.getInstance()
            val client = app.createClient()
            client.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 1)
            val created = JBCefBrowser.createBuilder()
                .setClient(client)
                .setCreateImmediately(true)
                .build()
            created.component.background = UIUtil.getPanelBackground()
            browser = created
            Disposer.register(this, created)
            Disposer.register(this) { client.dispose() }

            val query = JBCefJSQuery.create(created as com.intellij.ui.jcef.JBCefBrowserBase)
            query.addHandler { request ->
                runCatching {
                    val command = parseCommand(request)
                    val result = java.util.concurrent.atomic.AtomicReference<Any?>()
                    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                    val execute = Runnable {
                        runCatching { onCommand(command) }
                            .onSuccess(result::set)
                            .onFailure(failure::set)
                    }
                    if (ApplicationManager.getApplication().isDispatchThread) execute.run()
                    else ApplicationManager.getApplication().invokeAndWait(execute)
                    failure.get()?.let { throw it }
                    JBCefJSQuery.Response(gson.toJson(mapOf("ok" to true, "data" to result.get())))
                }.getOrElse { JBCefJSQuery.Response("", 500, it.message ?: "Command failed") }
            }
            Disposer.register(this, query)
            if (onEscapeKey != null) {
                client.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
                    override fun onKeyEvent(browser: CefBrowser, event: CefKeyboardHandler.CefKeyEvent): Boolean {
                        if (event.type == CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN &&
                            event.windows_key_code == KeyEvent.VK_ESCAPE && !event.is_system_key
                        ) {
                            return onEscapeKey.invoke()
                        }
                        return false
                    }
                }, created.cefBrowser)
            }
            if (onDropFiles != null) {
                client.addDragHandler(object : CefDragHandler {
                    override fun onDragEnter(browser: CefBrowser, dragData: CefDragData, mask: Int): Boolean {
                        val names = java.util.Vector<String>()
                        dragData.getFileNames(names)
                        val files = names.map { java.io.File(it) }.filter(java.io.File::isFile)
                        if (files.isEmpty()) return false
                        ApplicationManager.getApplication().invokeLater { onDropFiles.invoke(files) }
                        return true
                    }
                }, created.cefBrowser)
            }
            // 兜底拦截主框架导航：任何离开本地对话页（http://jtools.agent/...）的导航都改为
            // 交给 IDE 用外部浏览器打开，避免 JCEF 内部跳走整个对话页且无法返回。
            // 前端已对 <a> 点击做了拦截，这里覆盖中键点击、window.location 等绕过前端的场景。
            client.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    browser: CefBrowser,
                    frame: CefFrame,
                    request: CefRequest,
                    userGesture: Boolean,
                    isRedirect: Boolean,
                ): Boolean {
                    if (!frame.isMain) return false
                    // 只接管真实用户手势触发的导航；初始 loadHTML 与 JCEF 内部导航 userGesture=false，放行。
                    if (!userGesture) return false
                    val url = request.url ?: return false
                    // 本地对话页放行；只把 http/https/mailto 等真实外链交给外部浏览器，其余（about:、data: 等内部协议）放行。
                    if (url.startsWith(LOCAL_PAGE_URL_PREFIX)) return false
                    if (!isExternalLink(url)) return false
                    ApplicationManager.getApplication().invokeLater {
                        onCommand(AgentBrowserCommand("link.open", com.google.gson.JsonObject().apply {
                            addProperty("text", url)
                        }))
                    }
                    return true
                }
            }, created.cefBrowser)
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain) return
                    browser.executeJavaScript(
                        "window.jtoolsInvoke=function(request){return new Promise(function(resolve,reject){${query.inject(
                                "request",
                                "function(response){resolve(response);}",
                                "function(error_code,error_message){reject(new Error(error_message || String(error_code)));}",
                            )}})};window.dispatchEvent(new Event('jtools-ready'));",
                        "http://jtools.agent/index.html",
                        0,
                    )
                    ready = true
                    flushState()
                    ApplicationManager.getApplication().invokeLater { onReady?.invoke() }
                }
            }, created.cefBrowser)
            component = created.component
            created.loadHTML(loadPage(), "http://jtools.agent/index.html")
        }
    }

    fun replaceState(state: Any) {
        if (browser == null) return
        pendingState = state
        if (ready) renderTimer.restart()
    }

    private fun flushState() {
        val state = pendingState ?: return
        val cef = browser?.cefBrowser ?: return
        if (!ready) return
        cef.executeJavaScript(
            "window.jtoolsAgent && window.jtoolsAgent.replace(${gson.toJson(state)});",
            "http://jtools.agent/index.html",
            0,
        )
        if (pendingState === state) pendingState = null
    }

    private fun parseCommand(request: String): AgentBrowserCommand {
        val root = JsonParser.parseString(request).asJsonObject
        return AgentBrowserCommand(
            type = root.get("type")?.asString.orEmpty(),
            payload = root.getAsJsonObject("payload") ?: com.google.gson.JsonObject(),
        )
    }

    override fun dispose() {
        renderTimer.stop()
        pendingState = null
        ready = false
    }

    private companion object {
        // 本地对话页地址前缀；导航目标以此开头视为内部页面，其余一律交给外部浏览器。
        const val LOCAL_PAGE_URL_PREFIX = "http://jtools.agent/"

        // 需要交给外部浏览器打开的真实外链协议；about:/data:/blob: 等 JCEF 内部协议不在此列。
        private val EXTERNAL_LINK_SCHEME = Regex("^(https?|mailto|ftp|tel):", RegexOption.IGNORE_CASE)

        // 是否为应交给外部浏览器的真实外链：仅接管明确的可浏览协议，避免误拦内部导航。
        fun isExternalLink(url: String): Boolean = EXTERNAL_LINK_SCHEME.containsMatchIn(url)
    }

    private fun loadPage(): String {
        val cssText = resourceText("/agent-web/app.css")
        val script = resourceText("/agent-web/app.js").replace("</script>", "<\\/script>")
        return """<!doctype html><html style="background:${css(UIUtil.getPanelBackground())}"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>$cssText</style></head><body><div id="app"></div><script>window.__JTOOLS_PAGE__=${gson.toJson(page)};</script><script type="module">$script</script></body></html>"""
    }

    private fun css(color: java.awt.Color): String = "#${ColorUtil.toHex(color)}"

    private fun resourceText(path: String): String =
        requireNotNull(AgentChatBrowser::class.java.getResourceAsStream(path)) { "Missing Agent UI resource: $path" }
            .use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
}

internal data class AgentBrowserCommand(
    @SerializedName("type") val type: String = "",
    @SerializedName("payload") val payload: com.google.gson.JsonObject = com.google.gson.JsonObject(),
)
