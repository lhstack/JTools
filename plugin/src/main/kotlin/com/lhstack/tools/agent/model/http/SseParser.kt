package com.lhstack.tools.agent.model.http

import okhttp3.Response
import okio.BufferedSource
import java.io.EOFException

/**
 * SSE parser，严格按 WHATWG/EventSource 的事件组装规则读取 Okio BufferedSource：
 * - 支持 LF、CRLF、单独 CR 行结束。
 * - 空行 dispatch 一个事件。
 * - 多个 data 行用换行拼接，并去掉最后一个换行。
 * - event/id/retry 字段按协议处理；注释行忽略。
 * - EOF 时只 dispatch 未完成但已有 data 的事件，然后返回 null。
 * - 非取消 IO 错误向上抛出，不伪装成正常 EOF。
 */
class SseParser(
    private val response: Response,
    private val source: BufferedSource,
    private val cancel: ModelCancel?,
    private val cancelCall: (() -> Unit)? = null,
) : AutoCloseable {

    init {
        cancel?.registerInterrupt {
            cancelCall?.invoke()
            response.close()
        }
    }

    fun next(): SseEvent? {
        if (cancel?.isCancelled() == true) {
            throw ModelRequestCancelledException()
        }
        val event = EventBuffer()
        var firstLine = true
        while (true) {
            var line = readLine() ?: return null
            if (firstLine) {
                firstLine = false
                if (line.startsWith("\uFEFF")) line = line.substring(1)
            }
            if (line.isEmpty()) {
                event.dispatch()?.let { return it }
                continue
            }
            event.accept(line)
        }
    }

    private fun readLine(): String? {
        return try {
            if (source.exhausted()) return null
            val line = source.readUtf8LineStrict(Long.MAX_VALUE)
            line
        } catch (e: EOFException) {
            null
        } catch (e: Throwable) {
            if (cancel?.isCancelled() == true) {
                throw ModelRequestCancelledException()
            }
            throw IllegalStateException("SSE stream read failed", e)
        }
    }

    override fun close() {
        cancel?.clearInterrupt()
        response.close()
    }

    private class EventBuffer {
        private var eventName = ""
        private val data = StringBuilder()
        private var hasData = false

        fun accept(line: String) {
            if (line.startsWith(":")) return
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> eventName = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
                // id/retry are intentionally not exposed in SseEvent, but are valid SSE fields.
                "id", "retry" -> Unit
            }
        }

        fun dispatch(): SseEvent? {
            if (!hasData) {
                eventName = ""
                return null
            }
            return SseEvent(eventName.ifEmpty { "message" }, data.toString())
        }
    }
}
