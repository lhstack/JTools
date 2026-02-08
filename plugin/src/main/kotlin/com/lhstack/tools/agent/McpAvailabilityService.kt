package com.lhstack.tools.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.lhstack.tools.ext.errorNotify
import com.lhstack.tools.plugins.pluginState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class McpAvailabilityService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(McpAvailabilityService::class.java)
    private val statuses = ConcurrentHashMap<String, Boolean?>()
    private val listeners = CopyOnWriteArrayList<AvailabilityListener>()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val initialDelayMs = 10_000L
    private val intervalMs = 60_000L
    private var future: ScheduledFuture<*>? = null

    init {
        schedule()
        scheduler.execute { checkAll() }
    }

    fun getAvailability(serverId: String?): Boolean? {
        if (serverId.isNullOrBlank()) {
            return null
        }
        return statuses[serverId]
    }

    fun addListener(listener: AvailabilityListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AvailabilityListener) {
        listeners.remove(listener)
    }

    override fun dispose() {
        future?.cancel(true)
        future = null
    }

    private fun schedule() {
        future = scheduler.scheduleWithFixedDelay(
            { checkAll() },
            initialDelayMs,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun checkAll() {
        if (project.isDisposed) {
            return
        }
        val state = project.pluginState()
        if (!state.agentMcpEnabled) {
            return
        }
        val servers = McpSupport.safeServers(state.agentMcpServers)
        servers.forEach { server ->
            McpSupport.ensureServerId(server)
            requestCheck(server)
        }
    }

    fun requestCheck(server: McpServerState) {
        if (project.isDisposed) {
            return
        }
        McpSupport.ensureServerId(server)
        if (server.id.isBlank()) {
            return
        }
        if (!inFlight.add(server.id)) {
            return
        }
        scheduler.execute {
            try {
                checkServer(server)
            } finally {
                inFlight.remove(server.id)
            }
        }
    }

    private fun checkServer(server: McpServerState) {
            if (!server.enabled) {
                updateStatus(server.id, null, server.name, null)
                return
            }
            if (!isConfigReady(server)) {
                updateStatus(server.id, null, server.name, null)
                return
            }
            val ok = try {
                McpClientManager.getClient(server).ping()
                true
            } catch (e: Throwable) {
                updateStatus(server.id, false, server.name, e.message)
                false
            }
            if (ok) {
                updateStatus(server.id, true, server.name, null)
            }
    }

    private fun isConfigReady(server: McpServerState): Boolean {
        return when (McpTransportType.fromId(server.transport)) {
            McpTransportType.STDIO -> server.stdioCommand.isNotBlank()
            McpTransportType.SSE, McpTransportType.STREAMABLE_HTTP -> server.url.isNotBlank()
        }
    }

    private fun updateStatus(serverId: String, available: Boolean?, name: String, errorMessage: String?) {
        if (serverId.isBlank()) {
            return
        }
        val previous = statuses.put(serverId, available)
        if (previous == available) {
            return
        }
        listeners.forEach { listener ->
            listener.onStatusChanged(serverId, available)
        }
        if (available == false) {
            val reason = errorMessage?.takeIf { it.isNotBlank() } ?: "ping failed"
            ApplicationManager.getApplication().invokeLater {
                project.errorNotify("MCP 不可用", "$name: $reason")
            }
        }
    }

    fun interface AvailabilityListener {
        fun onStatusChanged(serverId: String, available: Boolean?)
    }

    companion object {
        fun getInstance(project: Project): McpAvailabilityService = project.service()
    }
}
