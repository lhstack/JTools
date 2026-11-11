package com.lhstack.tools.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.handler.CefDownloadHandler

class JcefDownloadHandlersTest {
    @Test
    fun `download callback prompts for destination using the runtime return contract`() {
        val handler = JcefDownloadHandlers.promptForDestination()
        var fileName: String? = null
        var showDialog = false
        val callback = CefBeforeDownloadCallback { name, prompt ->
            fileName = name
            showDialog = prompt
        }
        val method = CefDownloadHandler::class.java.methods.single { it.name == "onBeforeDownload" }
        val result = method.invoke(handler, null, null, "report.txt", callback)
        assertEquals("report.txt", fileName)
        assertTrue(showDialog)
        assertEquals(if (method.returnType == java.lang.Boolean.TYPE) true else null, result)
        assertEquals(handler, handler)
        assertNotEquals(handler, JcefDownloadHandlers.promptForDestination())
    }
}
