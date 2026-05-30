package com.lhstack.tools.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.icons.AllIcons
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.lhstack.tools.const.Const
import com.lhstack.tools.plugins.PluginState
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefStringVisitor
import org.cef.handler.CefAppHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.jsoup.Jsoup
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JPanel

object AgentBrowserTools {
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_TEXT_LENGTH = 30_000
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private val projectSessions = ConcurrentHashMap<String, String>()
    private val proxyLock = Any()

    @Volatile
    private var configuredProxyServer: String? = null

    fun open(project: Project, url: String, visible: Boolean): Map<String, Any?> {
        val proxyStatus = ensureCefProxyConfigured()
        if (!proxyStatus.ok) {
            return mapOf(
                "ok" to false,
                "error" to proxyStatus.message,
                "proxy" to proxyStatus.toMap()
            )
        }
        if (!JBCefApp.isSupported()) {
            return error("当前 IDE Runtime 不支持 JCEF")
        }
        val normalizedUrl = normalizeUrl(url)
        val session = activeSession(project) ?: createSession(project, UUID.randomUUID().toString(), normalizedUrl).also {
            sessions[it.id] = it
            projectSessions[projectKey(project)] = it.id
        }
        if (visible) {
            val shown = show(project, session.id, confirm = false)
            if (shown["ok"] != true) {
                close(project, session.id)
                return shown
            }
        } else if (session.visible) {
            hide(project, session.id)
        }
        val loaded = session.load(normalizedUrl, DEFAULT_TIMEOUT_MS)
        if (!loaded.ok) {
            close(project, session.id)
            return loaded.toMap(session.id, normalizedUrl)
        }
        return mapOf(
            "ok" to true,
            "sessionId" to session.id,
            "url" to session.url(),
            "visible" to session.visible,
            "loaded" to true,
            "reused" to (session.initialUrl != normalizedUrl),
            "proxy" to proxyStatus.toMap()
        )
    }

    fun read(project: Project, sessionId: String, mode: String): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        return session.read(mode.ifBlank { "text" }, DEFAULT_TIMEOUT_MS)
    }

    fun snapshot(project: Project, sessionId: String, maxElements: Int): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        return session.snapshot(maxElements.coerceIn(1, 200), DEFAULT_TIMEOUT_MS)
    }

    fun click(project: Project, sessionId: String, selector: String, waitAfterMs: Long): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        if (selector.isBlank()) {
            return error("selector 不能为空")
        }
        val result = session.clickSelector(selector, DEFAULT_TIMEOUT_MS)
        sleepQuietly(waitAfterMs.coerceIn(0, 10_000))
        return result + mapOf("sessionId" to sessionId, "url" to session.url(), "selector" to selector)
    }

    fun clickRef(project: Project, sessionId: String, ref: String, waitAfterMs: Long): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        val selector = session.selectorForRef(ref) ?: return error("ref 不存在或已过期，请先调用 browser_snapshot: $ref")
        val result = session.clickSelector(selector, DEFAULT_TIMEOUT_MS)
        sleepQuietly(waitAfterMs.coerceIn(0, 10_000))
        return result + mapOf("sessionId" to sessionId, "url" to session.url(), "ref" to ref)
    }

    fun clickText(project: Project, sessionId: String, text: String, exact: Boolean, waitAfterMs: Long): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        if (text.isBlank()) {
            return error("text 不能为空")
        }
        val result = session.clickText(text, exact, DEFAULT_TIMEOUT_MS)
        sleepQuietly(waitAfterMs.coerceIn(0, 10_000))
        return result + mapOf("sessionId" to sessionId, "url" to session.url(), "text" to text, "exact" to exact)
    }

    fun type(project: Project, sessionId: String, selector: String, text: String, clear: Boolean): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        if (selector.isBlank()) {
            return error("selector 不能为空")
        }
        val result = session.typeSelector(selector, text, clear, DEFAULT_TIMEOUT_MS)
        return result + mapOf("sessionId" to sessionId, "url" to session.url(), "selector" to selector)
    }

    fun typeRef(project: Project, sessionId: String, ref: String, text: String, clear: Boolean): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        val selector = session.selectorForRef(ref) ?: return error("ref 不存在或已过期，请先调用 browser_snapshot: $ref")
        val result = session.typeSelector(selector, text, clear, DEFAULT_TIMEOUT_MS)
        return result + mapOf("sessionId" to sessionId, "url" to session.url(), "ref" to ref)
    }

    fun press(project: Project, sessionId: String, key: String): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        if (key.isBlank()) {
            return error("key 不能为空")
        }
        return session.press(key, DEFAULT_TIMEOUT_MS) + mapOf("sessionId" to sessionId, "url" to session.url(), "key" to key)
    }

    fun scroll(project: Project, sessionId: String, deltaY: Int): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        session.executeJavaScript("window.scrollBy(0, ${deltaY.coerceIn(-10_000, 10_000)});")
        sleepQuietly(500)
        return mapOf("ok" to true, "sessionId" to sessionId, "url" to session.url(), "deltaY" to deltaY)
    }

    fun waitForText(project: Project, sessionId: String, text: String, timeoutSeconds: Int): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        if (text.isBlank()) {
            return error("text 不能为空")
        }
        return session.waitForText(text, timeoutSeconds.coerceIn(1, 60))
            .let { it + mapOf("sessionId" to sessionId, "url" to session.url(), "text" to text) }
    }

    fun waitForSelector(project: Project, sessionId: String, selector: String, timeoutSeconds: Int): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        if (selector.isBlank()) {
            return error("selector 不能为空")
        }
        return session.waitForSelector(selector, timeoutSeconds.coerceIn(1, 60))
            .let { it + mapOf("sessionId" to sessionId, "url" to session.url(), "selector" to selector) }
    }

    fun screenshot(project: Project, sessionId: String): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        return session.screenshot(DEFAULT_TIMEOUT_MS) + mapOf("sessionId" to sessionId, "url" to session.url())
    }

    fun show(project: Project, sessionId: String): Map<String, Any?> {
        return show(project, sessionId, confirm = true)
    }

    private fun show(project: Project, sessionId: String, confirm: Boolean): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        if (confirm) {
            val accepted = confirmShow(project)
            if (!accepted) {
                return error("用户拒绝显示浏览器组件")
            }
        }
        return runOnEdt {
            val toolWindow = ensureBrowserToolWindow(project)
            val contentManager = toolWindow.contentManager
            val existing = contentManager.contents.firstOrNull { it.displayName == session.contentName }
            if (existing != null) {
                contentManager.setSelectedContent(existing)
            } else {
                val content = contentManager.factory.createContent(session.component, session.contentName, true)
                content.setDisposer(session.disposable)
                session.content = content
                contentManager.addContent(content)
                contentManager.setSelectedContent(content)
            }
            session.visible = true
            toolWindow.activate(null)
            mapOf("ok" to true, "sessionId" to sessionId, "visible" to true, "url" to session.url())
        }
    }

    fun hide(project: Project, sessionId: String): Map<String, Any?> {
        val session = session(sessionId) ?: return notFound(sessionId)
        return runOnEdt {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(Const.AGENT_BROWSER_WINDOW_ID)
            if (toolWindow != null) {
                val content = session.content ?: toolWindow.contentManager.contents.firstOrNull { it.displayName == session.contentName }
                if (content != null) {
                    toolWindow.contentManager.removeContent(content, false)
                }
                toolWindow.hide(null)
                ToolWindowManager.getInstance(project).unregisterToolWindow(Const.AGENT_BROWSER_WINDOW_ID)
            }
            session.content = null
            session.visible = false
            mapOf("ok" to true, "sessionId" to sessionId, "visible" to false, "url" to session.url())
        }
    }

    fun close(project: Project, sessionId: String): Map<String, Any?> {
        val session = sessions.remove(sessionId) ?: return notFound(sessionId)
        projectSessions.remove(projectKey(project), sessionId)
        return runOnEdt {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(Const.AGENT_BROWSER_WINDOW_ID)
            val content = session.content ?: toolWindow?.contentManager?.contents?.firstOrNull { it.displayName == session.contentName }
            if (content != null) {
                toolWindow?.contentManager?.removeContent(content, false)
            }
            if (toolWindow != null) {
                toolWindow.hide(null)
                ToolWindowManager.getInstance(project).unregisterToolWindow(Const.AGENT_BROWSER_WINDOW_ID)
            }
            Disposer.dispose(session.disposable)
            mapOf("ok" to true, "sessionId" to sessionId, "closed" to true)
        }
    }

    private fun ensureBrowserToolWindow(project: Project): com.intellij.openapi.wm.ToolWindow {
        val manager = ToolWindowManager.getInstance(project)
        manager.getToolWindow(Const.AGENT_BROWSER_WINDOW_ID)?.let { return it }
        return manager.registerToolWindow(Const.AGENT_BROWSER_WINDOW_ID, true, ToolWindowAnchor.RIGHT).apply {
            setIcon(AllIcons.General.Web)
            setToHideOnEmptyContent(true)
        }
    }

    private fun ensureCefProxyConfigured(): BrowserProxyStatus {
        val proxyServer = browserProxyServer() ?: return BrowserProxyStatus(
            ok = true,
            enabled = false,
            server = null,
            message = "浏览器代理未启用"
        )
        synchronized(proxyLock) {
            if (configuredProxyServer == proxyServer) {
                return BrowserProxyStatus(true, true, proxyServer, "浏览器代理已启用: $proxyServer")
            }
            if (configuredProxyServer != null && configuredProxyServer != proxyServer) {
                return BrowserProxyStatus(
                    ok = false,
                    enabled = true,
                    server = proxyServer,
                    message = "JCEF 已使用代理 ${configuredProxyServer} 初始化，无法在当前 IDE 进程中切换到 $proxyServer；请重启 IDE 后再使用浏览器工具"
                )
            }
            val state = runCatching { CefApp.getState() }.getOrNull()
            if (state != CefApp.CefAppState.NONE && state != CefApp.CefAppState.NEW) {
                return BrowserProxyStatus(
                    ok = false,
                    enabled = true,
                    server = proxyServer,
                    message = "JCEF 已启动，浏览器代理 $proxyServer 无法动态应用；请重启 IDE 后再使用浏览器工具"
                )
            }
            return runCatching {
                CefApp.addAppHandler(object : CefAppHandlerAdapter(emptyArray()) {
                    override fun onBeforeCommandLineProcessing(
                        processType: String?,
                        commandLine: org.cef.callback.CefCommandLine,
                    ) {
                        if (!commandLine.hasSwitch("proxy-server")) {
                            commandLine.appendSwitchWithValue("proxy-server", proxyServer)
                        }
                    }
                })
                configuredProxyServer = proxyServer
                BrowserProxyStatus(true, true, proxyServer, "浏览器代理已启用: $proxyServer")
            }.getOrElse { error ->
                BrowserProxyStatus(
                    ok = false,
                    enabled = true,
                    server = proxyServer,
                    message = error.message ?: "浏览器代理初始化失败"
                )
            }
        }
    }

    private fun browserProxyServer(): String? {
        val state = PluginState.getInstance().state
        if (!state.webToolProxyEnabled) {
            return null
        }
        val host = state.webToolProxyHost.trim()
        val port = state.webToolProxyPort
        if (host.isBlank() || port !in 1..65535) {
            return null
        }
        val scheme = when (AgentProxyType.fromId(state.webToolProxyType)) {
            AgentProxyType.SOCKS -> "socks5"
            AgentProxyType.HTTP -> "http"
        }
        return "$scheme://$host:$port"
    }

    private fun createSession(project: Project, sessionId: String, url: String): BrowserSession {
        val disposable = Disposer.newDisposable("agent-browser-$sessionId")
        Disposer.register(project, disposable)
        Disposer.register(disposable) {
            sessions.remove(sessionId)
            projectSessions.remove(projectKey(project), sessionId)
        }
        val browser = runOnEdt {
            val client = JBCefApp.getInstance().createClient()
            Disposer.register(disposable, client)
            val created = JBCefBrowser.createBuilder()
                .setOffScreenRendering(false)
                .setClient(client)
                .build()
            val jsQuery = JBCefJSQuery.create(created)
            Disposer.register(disposable, jsQuery)
            created.cefBrowser.createImmediately()
            Disposer.register(disposable, created)
            created to jsQuery
        }
        return BrowserSession(
            id = sessionId,
            browser = browser.first,
            jsQuery = browser.second,
            disposable = disposable,
            component = JPanel().apply {
                layout = java.awt.BorderLayout()
                add(browser.first.component, java.awt.BorderLayout.CENTER)
            },
            contentName = Const.AGENT_BROWSER_WINDOW_ID,
            initialUrl = url
        )
    }

    private fun activeSession(project: Project): BrowserSession? {
        return projectSessions[projectKey(project)]?.let { sessions[it] }
    }

    private fun session(sessionId: String): BrowserSession? {
        return sessions[sessionId.trim()]
    }

    private fun projectKey(project: Project): String {
        return project.locationHash
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
    }

    private fun confirmShow(project: Project): Boolean {
        val accepted = booleanArrayOf(false)
        ApplicationManager.getApplication().invokeAndWait {
            accepted[0] = Messages.showYesNoDialog(
                project,
                "AI 想显示浏览器窗口以便你查看操作过程，是否允许？",
                "显示 AI 浏览器",
                Messages.getQuestionIcon()
            ) == Messages.YES
        }
        return accepted[0]
    }

    private fun notFound(sessionId: String): Map<String, Any?> {
        return error("浏览器会话不存在或已关闭: $sessionId")
    }

    private fun error(message: String): Map<String, Any?> {
        return mapOf("ok" to false, "error" to message)
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0) {
            return
        }
        runCatching { Thread.sleep(ms) }
    }

    private fun <T> runOnEdt(action: () -> T): T {
        if (ApplicationManager.getApplication().isDispatchThread) {
            return action()
        }
        val future = CompletableFuture<T>()
        ApplicationManager.getApplication().invokeLater {
            try {
                future.complete(action())
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future.get(30, TimeUnit.SECONDS)
    }

    private fun String.jsString(): String {
        return buildString {
            append('"')
            this@jsString.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }

    private fun jsonToAny(element: JsonElement?): Any? {
        if (element == null || element.isJsonNull) {
            return null
        }
        return when {
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> primitive.asNumber
                    else -> primitive.asString
                }
            }
            element.isJsonArray -> element.asJsonArray.map { jsonToAny(it) }
            element.isJsonObject -> element.asJsonObject.entrySet().associate { it.key to jsonToAny(it.value) }
            else -> null
        }
    }

    private fun jsonObjectToMap(element: JsonElement?): Map<String, Any?> {
        if (element == null || !element.isJsonObject) {
            return emptyMap()
        }
        return element.asJsonObject.entrySet().associate { it.key to jsonToAny(it.value) }
    }

    private data class LoadResult(
        val ok: Boolean,
        val statusCode: Int,
        val error: String? = null,
    ) {
        fun toMap(sessionId: String, url: String): Map<String, Any?> {
            return mapOf(
                "ok" to ok,
                "sessionId" to sessionId,
                "url" to url,
                "statusCode" to statusCode,
                "error" to error
            )
        }
    }

    private data class BrowserProxyStatus(
        val ok: Boolean,
        val enabled: Boolean,
        val server: String?,
        val message: String,
    ) {
        fun toMap(): Map<String, Any?> {
            return mapOf(
                "ok" to ok,
                "enabled" to enabled,
                "server" to server,
                "message" to message
            )
        }
    }

    private class BrowserSession(
        val id: String,
        val browser: JBCefBrowser,
        val jsQuery: JBCefJSQuery,
        val disposable: Disposable,
        val component: JPanel,
        val contentName: String,
        val initialUrl: String,
    ) {
        private val pendingJs = ConcurrentHashMap<String, CompletableFuture<Map<String, Any?>>>()
        private val refSelectors = ConcurrentHashMap<String, String>()

        init {
            jsQuery.addHandler { request ->
                val payload = runCatching { JsonParser.parseString(request).asJsonObject }.getOrNull()
                val id = payload?.get("id")?.takeIf { !it.isJsonNull }?.asString
                val future = id?.let { pendingJs.remove(it) }
                if (future != null) {
                    future.complete(jsonObjectToMap(payload))
                }
                JBCefJSQuery.Response("ok")
            }
        }

        @Volatile
        var visible: Boolean = false

        @Volatile
        var content: com.intellij.ui.content.Content? = null

        fun url(): String = browser.cefBrowser.url ?: initialUrl

        fun load(url: String, timeoutMs: Long): LoadResult {
            val future = CompletableFuture<LoadResult>()
            val handler = object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true) {
                        future.complete(LoadResult(httpStatusCode in 200..399, httpStatusCode))
                    }
                }

                override fun onLoadError(
                    browser: CefBrowser,
                    frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?,
                ) {
                    if (frame?.isMain == true) {
                        future.complete(LoadResult(false, 0, errorText ?: "加载失败"))
                    }
                }
            }
            browser.jbCefClient.addLoadHandler(handler, browser.cefBrowser)
            return try {
                runOnEdt { browser.loadURL(url) }
                runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
                    .getOrElse { LoadResult(false, 0, it.message ?: "页面加载超时") }
            } finally {
                browser.jbCefClient.removeLoadHandler(handler, browser.cefBrowser)
            }
        }

        fun read(mode: String, timeoutMs: Long): Map<String, Any?> {
            val future = CompletableFuture<String>()
            val visitor = CefStringVisitor { value -> future.complete(value.orEmpty()) }
            runOnEdt {
                if (mode == "html") {
                    browser.cefBrowser.getSource(visitor)
                } else {
                    browser.cefBrowser.getText(visitor)
                }
            }
            val raw = runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrElse { return error(it.message ?: "读取页面失败") }
            val text = if (mode == "html") Jsoup.parse(raw, url()).text() else raw
            return mapOf(
                "ok" to true,
                "sessionId" to id,
                "url" to url(),
                "mode" to mode,
                "content" to text.take(MAX_TEXT_LENGTH),
                "truncated" to (text.length > MAX_TEXT_LENGTH),
                "length" to text.length
            )
        }

        fun snapshot(maxElements: Int, timeoutMs: Long): Map<String, Any?> {
            val result = evaluate(
                """
                    return (function() {
                      const visible = (el) => {
                        const r = el.getBoundingClientRect();
                        const s = window.getComputedStyle(el);
                        return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
                      };
                      const cssPath = (el) => {
                        if (el.id) return '#' + CSS.escape(el.id);
                        const parts = [];
                        let node = el;
                        while (node && node.nodeType === 1 && node !== document.body && parts.length < 6) {
                          let part = node.tagName.toLowerCase();
                          if (node.name) part += '[name="' + CSS.escape(node.name) + '"]';
                          else {
                            let index = 1, prev = node;
                            while ((prev = prev.previousElementSibling) != null) {
                              if (prev.tagName === node.tagName) index++;
                            }
                            part += ':nth-of-type(' + index + ')';
                          }
                          parts.unshift(part);
                          node = node.parentElement;
                        }
                        return parts.length ? parts.join(' > ') : el.tagName.toLowerCase();
                      };
                      const label = (el) => (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.title || '').replace(/\s+/g, ' ').trim();
                      const selector = 'a[href],button,input,textarea,select,[role="button"],[onclick]';
                      const elements = Array.from(document.querySelectorAll(selector))
                        .filter(visible)
                        .slice(0, ${maxElements})
                        .map((el, index) => ({
                          ref: 'e' + (index + 1),
                          tag: el.tagName.toLowerCase(),
                          type: el.getAttribute('type') || '',
                          text: label(el).slice(0, 160),
                          href: el.href || '',
                          name: el.getAttribute('name') || '',
                          placeholder: el.getAttribute('placeholder') || '',
                          disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
                          selector: cssPath(el)
                        }));
                      return {
                        title: document.title || '',
                        url: location.href,
                        text: (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').trim().slice(0, 4000),
                        elements: elements,
                        elementCount: elements.length
                      };
                    })();
                """.trimIndent(),
                timeoutMs
            )
            val snapshot = result["result"] as? Map<*, *> ?: return result
            refSelectors.clear()
            (snapshot["elements"] as? List<*>)?.forEach { raw ->
                val item = raw as? Map<*, *> ?: return@forEach
                val ref = item["ref"]?.toString().orEmpty()
                val selector = item["selector"]?.toString().orEmpty()
                if (ref.isNotBlank() && selector.isNotBlank()) {
                    refSelectors[ref] = selector
                }
            }
            return mapOf("ok" to true, "sessionId" to id, "url" to url(), "snapshot" to snapshot)
        }

        fun selectorForRef(ref: String): String? = refSelectors[ref.trim()]

        fun clickSelector(selector: String, timeoutMs: Long): Map<String, Any?> {
            return evaluate(
                """
                    const el = document.querySelector(${selector.jsString()});
                    if (!el) return {ok:false, found:false, error:'selector not found'};
                    el.scrollIntoView({block:'center', inline:'center'});
                    el.click();
                    return {ok:true, found:true, tag:el.tagName.toLowerCase(), text:(el.innerText || el.value || '').replace(/\s+/g,' ').trim().slice(0,160), href:el.href || '', disabled:!!el.disabled};
                """.trimIndent(),
                timeoutMs
            ).unwrapResult()
        }

        fun clickText(text: String, exact: Boolean, timeoutMs: Long): Map<String, Any?> {
            return evaluate(
                """
                    const target = ${text.jsString()};
                    const exact = ${exact};
                    const elements = Array.from(document.querySelectorAll('a[href],button,[role="button"],input[type="button"],input[type="submit"],[onclick]'));
                    const label = (el) => (el.innerText || el.value || el.getAttribute('aria-label') || el.title || '').replace(/\s+/g,' ').trim();
                    const el = elements.find(e => exact ? label(e) === target : label(e).includes(target));
                    if (!el) return {ok:false, found:false, error:'text not found'};
                    el.scrollIntoView({block:'center', inline:'center'});
                    el.click();
                    return {ok:true, found:true, tag:el.tagName.toLowerCase(), text:label(el).slice(0,160), href:el.href || '', disabled:!!el.disabled};
                """.trimIndent(),
                timeoutMs
            ).unwrapResult()
        }

        fun typeSelector(selector: String, text: String, clear: Boolean, timeoutMs: Long): Map<String, Any?> {
            return evaluate(
                """
                    const el = document.querySelector(${selector.jsString()});
                    if (!el) return {ok:false, found:false, error:'selector not found'};
                    el.scrollIntoView({block:'center', inline:'center'});
                    el.focus();
                    if (${clear}) el.value = '';
                    el.value = (el.value || '') + ${text.jsString()};
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                    return {ok:true, found:true, tag:el.tagName.toLowerCase(), value:el.value || '', name:el.name || '', placeholder:el.placeholder || ''};
                """.trimIndent(),
                timeoutMs
            ).unwrapResult()
        }

        fun press(key: String, timeoutMs: Long): Map<String, Any?> {
            return evaluate(
                """
                    const key = ${key.jsString()};
                    const el = document.activeElement || document.body;
                    ['keydown','keyup'].forEach(type => {
                      el.dispatchEvent(new KeyboardEvent(type, {key:key, code:key, bubbles:true, cancelable:true}));
                    });
                    if (key === 'Enter' && el && el.form) el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit();
                    return {ok:true, activeTag: el ? el.tagName.toLowerCase() : '', key:key};
                """.trimIndent(),
                timeoutMs
            ).unwrapResult()
        }

        fun waitForText(text: String, timeoutSeconds: Int): Map<String, Any?> {
            return evaluate(
                """
                    return new Promise((resolve) => {
                      const target = ${text.jsString()};
                      const deadline = Date.now() + ${timeoutSeconds * 1000};
                      const tick = () => {
                        const found = document.body && document.body.innerText && document.body.innerText.includes(target);
                        if (found) resolve({ok:true, found:true});
                        else if (Date.now() >= deadline) resolve({ok:false, found:false, error:'timeout'});
                        else setTimeout(tick, 250);
                      };
                      tick();
                    });
                """.trimIndent(),
                (timeoutSeconds + 2) * 1000L
            ).unwrapResult()
        }

        fun waitForSelector(selector: String, timeoutSeconds: Int): Map<String, Any?> {
            return evaluate(
                """
                    return new Promise((resolve) => {
                      const selector = ${selector.jsString()};
                      const deadline = Date.now() + ${timeoutSeconds * 1000};
                      const tick = () => {
                        const el = document.querySelector(selector);
                        if (el) resolve({ok:true, found:true, tag:el.tagName.toLowerCase(), text:(el.innerText || el.value || '').replace(/\s+/g,' ').trim().slice(0,160)});
                        else if (Date.now() >= deadline) resolve({ok:false, found:false, error:'timeout'});
                        else setTimeout(tick, 250);
                      };
                      tick();
                    });
                """.trimIndent(),
                (timeoutSeconds + 2) * 1000L
            ).unwrapResult()
        }

        fun screenshot(timeoutMs: Long): Map<String, Any?> {
            val image = runCatching { browser.cefBrowser.createScreenshot(true).get(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrElse { return error(it.message ?: "截图失败") }
            val file = java.nio.file.Files.createTempFile("jtools-browser-", ".png").toFile()
            ImageIO.write(image, "png", file)
            return mapOf("ok" to true, "path" to file.absolutePath, "width" to image.width, "height" to image.height)
        }

        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.unwrapResult(): Map<String, Any?> {
            val result = this["result"] as? Map<String, Any?>
                ?: return this
            return result
        }

        private fun evaluate(scriptBody: String, timeoutMs: Long): Map<String, Any?> {
            val requestId = UUID.randomUUID().toString()
            val future = CompletableFuture<Map<String, Any?>>()
            pendingJs[requestId] = future
            val callback = jsQuery.inject("JSON.stringify(payload)")
            val script = """
                (function() {
                  const payload = {id: ${requestId.jsString()}, ok: false};
                  const finish = (ok, value) => {
                    payload.ok = ok;
                    if (ok) payload.result = value;
                    else payload.error = String(value && value.message ? value.message : value);
                    $callback;
                  };
                  try {
                    Promise.resolve((function() {
                      $scriptBody
                    })()).then(v => finish(true, v)).catch(e => finish(false, e));
                  } catch (e) {
                    finish(false, e);
                  }
                })();
            """.trimIndent()
            runOnEdt { browser.cefBrowser.executeJavaScript(script, url(), 0) }
            return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrElse {
                    pendingJs.remove(requestId)
                    error(it.message ?: "浏览器脚本执行超时")
                }
        }

        fun executeJavaScript(script: String) {
            runOnEdt { browser.cefBrowser.executeJavaScript(script, url(), 0) }
        }
    }
}
