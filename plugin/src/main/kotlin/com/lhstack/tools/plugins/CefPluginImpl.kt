package com.lhstack.tools.plugins

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.util.IconLoader
import kotlinx.serialization.json.JsonObject
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

class CefPluginImpl(val classLoader: ClassLoader) : IPlugin {

    init {
        var resource: URL? = classLoader.getResource("pluginInfo.json") ?: throw RuntimeException("pluginInfo.json cannot null")
        resource!!.readBytes().let { String(it,StandardCharsets.UTF_8) }.let {  }
    }
    override fun pluginIcon(): Icon? {
        return IconLoader.findIcon("PluginIcon.svg",classLoader)
    }

    override fun pluginTabIcon(): Icon? {
        return IconLoader.findIcon("PluginTabIcon.svg",classLoader)
    }

    override fun pluginName(): String? {
        return classLoader.getResourceAsStream("PluginName.json")?.readBytes()?.let { String(it,StandardCharsets.UTF_8) }
    }

    override fun pluginDesc(): String? {
        return classLoader.getResourceAsStream("PluginDesc.txt")?.readBytes()?.let { String(it,StandardCharsets.UTF_8) }
    }

    override fun pluginVersion(): String {
        TODO("Not yet implemented")
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