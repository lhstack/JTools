package com.lhstack.tools.agent.resources

import com.lhstack.tools.concurrent.AgentExecutors
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves only explicitly authorized session files; byte ranges allow seeking without base64 copies.
 */
class LocalMediaServer : AutoCloseable {
    private val files = ConcurrentHashMap<String, Pair<File, String>>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = AgentExecutors.shared
            createContext("/media/") { exchange ->
                try {
                    serve(exchange)
                } finally {
                    exchange.close()
                }
            }
            start()
        }

    @Synchronized
    fun register(file: File): Map<String, String> {
        require(file.isFile) { "媒体资源必须为普通文件" }
        val mime =
            when (file.extension.lowercase()) {
                "webm" -> "video/webm"
                "mp4",
                "m4v" -> "video/mp4"
                "ogv",
                "ogg" -> "video/ogg"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "oga" -> "audio/ogg"
                else -> error("不支持的媒体文件类型: ${file.extension}")
            }
        val token = files.entries.firstOrNull { it.value.first == file }?.key ?: UUID.randomUUID().toString()
        files[token] = file to mime
        return mapOf(
            "url" to "http://127.0.0.1:${server.address.port}/media/$token",
            "mime" to mime,
        )
    }

    private fun serve(exchange: HttpExchange) {
        if (exchange.requestMethod !in setOf("GET", "HEAD")) {
            exchange.responseHeaders.set("Allow", "GET, HEAD")
            exchange.sendResponseHeaders(405, -1)
            return
        }
        val resource = files[exchange.requestURI.path.removePrefix("/media/")]
        if (resource == null || !resource.first.isFile) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        RandomAccessFile(resource.first, "r").use { input ->
            val length = input.length()
            val range = exchange.requestHeaders.getFirst("Range")
            val bounds = if (range == null) 0L to length - 1 else byteRange(range, length)
            val headers = exchange.responseHeaders
            headers.set("Access-Control-Allow-Origin", "*")
            headers.set("Accept-Ranges", "bytes")
            headers.set("Content-Type", resource.second)
            headers.set("Cache-Control", "no-store")
            if (bounds == null) {
                headers.set("Content-Range", "bytes */$length")
                exchange.sendResponseHeaders(416, -1)
                return
            }
            val (start, end) = bounds
            val count = (end - start + 1).coerceAtLeast(0)
            if (range != null) headers.set("Content-Range", "bytes $start-$end/$length")
            headers.set("Content-Length", count.toString())
            exchange.sendResponseHeaders(
                if (range == null) 200 else 206,
                if (exchange.requestMethod == "HEAD" || count == 0L) -1 else count,
            )
            if (exchange.requestMethod == "HEAD" || count == 0L) return
            input.seek(start)
            var remaining = count
            val buffer = ByteArray(64 * 1024)
            try {
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(read > 0) { "媒体文件在读取时发生变化" }
                    try {
                        exchange.responseBody.write(buffer, 0, read)
                    } catch (_: java.io.IOException) {
                        // The player closes its HTTP response when seeking or leaving the message.
                        return
                    }
                    remaining -= read
                }
            } finally {
                exchange.responseBody.close()
            }
        }
    }

    private fun byteRange(header: String, length: Long): Pair<Long, Long>? {
        if (length <= 0) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: return null
        val (first, last) = match.destructured
        if (first.isEmpty()) {
            val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return (length - suffix).coerceAtLeast(0) to length - 1
        }
        val start = first.toLongOrNull()?.takeIf { it < length } ?: return null
        val end = if (last.isEmpty()) length - 1 else last.toLongOrNull() ?: return null
        if (end < start) return null
        return start to minOf(end, length - 1)
    }

    override fun close() {
        server.stop(0)
        files.clear()
    }
}
