package com.lhstack.tools.debugger

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import java.io.PrintWriter
import java.io.Writer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Binds transport sessions to their creating credential as well as authenticating each request. */
internal class McpAccessFilter(
    private val clients: Map<String, String>,
    private val port: () -> Int,
) : Filter {
    private val sessionOwners = ConcurrentHashMap<String, String>()

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val req = request as HttpServletRequest
        val resp = response as HttpServletResponse
        val origin = req.getHeader("Origin")
        if (origin != null && origin !in setOf("http://127.0.0.1:${port()}", "http://localhost:${port()}")) {
            resp.sendError(403, "Invalid Origin")
            return
        }
        val authorization = req.getHeader("Authorization").orEmpty()
        val token = authorization.removePrefix("Bearer ")
        val client = clients.entries.firstOrNull { MessageDigest.isEqual(it.key.toByteArray(), token.toByteArray()) }?.value
        if (!authorization.startsWith("Bearer ") || client == null) {
            resp.sendError(401, "Missing or invalid debugger token")
            return
        }
        val sessionId = if (req.requestURI == "/mcp") req.getHeader("Mcp-Session-Id") else req.getParameter("sessionId")
        if (sessionId != null && sessionOwners[sessionId] != client) {
            resp.sendError(403, "MCP session belongs to another client or has expired")
            return
        }
        req.setAttribute("debugger.owner", "mcp:$client")
        chain.doFilter(request, sessionResponse(req, resp, client))
    }

    private fun sessionResponse(request: HttpServletRequest, response: HttpServletResponse, client: String) =
        object : HttpServletResponseWrapper(response) {
            private var capture: PrintWriter? = null

            override fun setHeader(name: String, value: String) {
                if (name.equals("Mcp-Session-Id", ignoreCase = true)) sessionOwners[value] = client
                super.setHeader(name, value)
            }

            override fun getWriter(): PrintWriter {
                if (request.requestURI != "/sse") return super.getWriter()
                return capture ?: endpointWriter(super.getWriter(), client).also { capture = it }
            }
        }

    /** Legacy SSE announces its session in the endpoint event rather than a response header. */
    private fun endpointWriter(delegate: PrintWriter, client: String): PrintWriter {
        val line = StringBuilder()
        return PrintWriter(object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) {
                for (index in offset until offset + length) {
                    val character = buffer[index]
                    if (character == '\n') {
                        val text = line.toString().trim()
                        if (text.startsWith("data:") && text.contains("?sessionId=")) {
                            sessionOwners[text.substringAfter("?sessionId=")] = client
                        }
                        line.setLength(0)
                    } else if (line.length < 1024) line.append(character)
                }
                delegate.write(buffer, offset, length)
            }
            override fun flush() = delegate.flush()
            override fun close() = delegate.close()
        }, true)
    }
}
