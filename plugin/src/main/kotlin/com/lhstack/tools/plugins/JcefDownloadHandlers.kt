package com.lhstack.tools.plugins

import java.lang.reflect.Proxy
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.handler.CefDownloadHandler

/** JCEF changed onBeforeDownload from void to boolean; bind to the running IDE's interface. */
internal object JcefDownloadHandlers {
    fun promptForDestination(): CefDownloadHandler =
        Proxy.newProxyInstance(
            CefDownloadHandler::class.java.classLoader,
            arrayOf(CefDownloadHandler::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "onBeforeDownload" -> {
                    check(
                        method.returnType == java.lang.Void.TYPE ||
                            method.returnType == java.lang.Boolean.TYPE
                    ) {
                        "Unsupported JCEF download return type: ${method.returnType}"
                    }
                    val callback =
                        requireNotNull(arguments[3] as CefBeforeDownloadCallback?) {
                            "JCEF download callback is missing"
                        }
                    callback.Continue(arguments[2] as String?, true)
                    if (method.returnType == java.lang.Boolean.TYPE) true else null
                }
                "onDownloadUpdated" -> null
                "equals" -> proxy === arguments[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "JTools download handler"
                else -> error("Unsupported JCEF download callback: $method")
            }
        } as CefDownloadHandler
}
