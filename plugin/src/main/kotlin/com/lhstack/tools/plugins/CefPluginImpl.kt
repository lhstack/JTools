package com.lhstack.tools.plugins

import com.google.gson.GsonBuilder
import com.intellij.openapi.util.IconLoader
import org.apache.commons.lang3.StringUtils
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.swing.Icon
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

    init {
        val resource: URL =
            classLoader.getResource("pluginInfo.json") ?: throw RuntimeException("pluginInfo.json cannot null")
        cefPluginInfo = String(resource.readBytes(), StandardCharsets.UTF_8).let {
            GsonBuilder().create().fromJson(it, CefPluginInfo::class.java)
        }
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

class CefResourceHandler(private var url: String, classLoader: ClassLoader) : CefResourceHandlerAdapter() {
    private var bytes: ByteArray? = null
    private var offset: Int? = null
    private var isOpen: Boolean = false

    init {
        try {
            if (url.startsWith("cp://", ignoreCase = true)) {
                url = url.substring("cp://".length)
                bytes = classLoader.getResource(url)?.readBytes()
            } else {
                bytes = URI.create(url).toURL().readBytes()
            }
            offset = 0
            isOpen = true
        } catch (e: Throwable) {
            isOpen = false
        }
    }

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        return isOpen
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
        if (StringUtils.endsWithIgnoreCase(url, ".html")) {
            response.mimeType = "text/html"
        } else if (StringUtils.endsWithIgnoreCase(url, ".css")) {
            response.mimeType = "text/css"
        } else if (StringUtils.endsWithIgnoreCase(url, ".js")) {
            response.mimeType = "application/javascript"
        } else {
            response.mimeType = "text/plain"
        }

        response.status = 200 // HTTP 状态码 200
        bytes?.size?.let { responseLength.set(it) }
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