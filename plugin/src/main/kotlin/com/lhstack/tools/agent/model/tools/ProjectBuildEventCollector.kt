package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Collects build output for one ProjectTaskManager execution without Build View experimental APIs. */
internal class ProjectBuildEventCollector(
    private val project: Project,
    private val sessionId: Any,
) : Disposable {
    private val errors = CopyOnWriteArrayList<Diagnostic>()
    private val warnings = CopyOnWriteArrayList<Diagnostic>()
    private val stdout = BoundedOutput()
    private val stderr = BoundedOutput()
    private val observedBuild = AtomicBoolean(false)
    private val structuredDiagnosticsAvailable = AtomicBoolean(false)
    private val activeExternalTask = AtomicReference<ExternalSystemTaskId?>()
    private val externalListener = externalSystemListener()

    fun subscribe() {
        ExternalSystemProgressNotificationManager.getInstance()
            .addNotificationListener(externalListener, this)
    }

    fun structuredDiagnosticsStarted() {
        structuredDiagnosticsAvailable.set(true)
    }

    fun error(diagnostic: Diagnostic) {
        errors.add(diagnostic)
    }

    fun warning(diagnostic: Diagnostic) {
        warnings.add(diagnostic)
    }

    fun snapshot(): Snapshot = Snapshot(
        observedBuild.get(),
        structuredDiagnosticsAvailable.get(),
        errors.distinct(),
        warnings.distinct(),
        stdout.value(),
        stderr.value(),
        stdout.truncated || stderr.truncated,
    )

    override fun dispose() {
        ACTIVE_BY_SESSION.remove(sessionId, this)
        ACTIVE_BY_PROJECT.remove(project, this)
    }

    private fun externalSystemListener(): ExternalSystemTaskNotificationListener {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            val values = args.orEmpty()
            when (method.name) {
                "onStart" -> values.filterIsInstance<ExternalSystemTaskId>().firstOrNull()?.let(::startExternalTask)
                "onTaskOutput" -> collectExternalOutput(values)
                "onFailure" -> collectExternalFailure(values)
            }
            defaultInvocationResult(method.returnType)
        }
        return Proxy.newProxyInstance(
            ExternalSystemTaskNotificationListener::class.java.classLoader,
            arrayOf(ExternalSystemTaskNotificationListener::class.java),
            handler,
        ) as ExternalSystemTaskNotificationListener
    }

    private fun startExternalTask(taskId: ExternalSystemTaskId) {
        if (taskId.type != ExternalSystemTaskType.EXECUTE_TASK) return
        if (taskId.ideProjectId != ExternalSystemTaskId.getProjectId(project)) return
        if (activeExternalTask.compareAndSet(null, taskId)) observedBuild.set(true)
    }

    private fun collectExternalOutput(values: Array<out Any?>) {
        val taskId = values.filterIsInstance<ExternalSystemTaskId>().firstOrNull() ?: return
        if (taskId != activeExternalTask.get()) return
        val text = values.filterIsInstance<String>().firstOrNull() ?: return
        if (externalOutputIsStdout(values)) stdout.append(text) else stderr.append(text)
    }

    private fun collectExternalFailure(values: Array<out Any?>) {
        val taskId = values.filterIsInstance<ExternalSystemTaskId>().firstOrNull() ?: return
        if (taskId != activeExternalTask.get()) return
        val failure = values.filterIsInstance<Throwable>().firstOrNull() ?: return
        errors.add(Diagnostic(failure.message ?: failure.toString()))
    }

    private fun externalOutputIsStdout(values: Array<out Any?>): Boolean {
        values.filterIsInstance<Boolean>().firstOrNull()?.let { return it }
        val outputType = requireNotNull(values.firstOrNull { value ->
            value?.javaClass?.name == "com.intellij.execution.process.ProcessOutputType"
        }) { "External-system build output did not provide an output channel" }
        val isStdout = requireNotNull(outputType.javaClass.methods.firstOrNull {
            it.name == "isStdout" && it.parameterCount == 0
        }) { "Unsupported external-system output channel type: ${outputType.javaClass.name}" }
        return isStdout.invoke(outputType) as Boolean
    }

    private fun defaultInvocationResult(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }

    data class Diagnostic(
        val message: String?,
        val description: String? = null,
        val file: String? = null,
        val line: Int? = null,
        val column: Int? = null,
    )

    data class Snapshot(
        val buildEventsAvailable: Boolean,
        val structuredDiagnosticsAvailable: Boolean,
        private val errorDiagnostics: List<Diagnostic>,
        private val warningDiagnostics: List<Diagnostic>,
        val stdout: String,
        val stderr: String,
        val outputTruncated: Boolean,
    ) {
        val errorCount: Int get() = errorDiagnostics.size
        val warningCount: Int get() = warningDiagnostics.size
        val errors: JsonArray get() = errorDiagnostics.toJson()
        val warnings: JsonArray get() = warningDiagnostics.toJson()
    }

    private class BoundedOutput {
        private val content = StringBuilder()
        var truncated: Boolean = false
            private set

        fun append(text: String) {
            if (truncated) return
            val remaining = MAX_OUTPUT_CHARS - content.length
            if (remaining <= 0) {
                truncated = true
                return
            }
            if (text.length <= remaining) {
                content.append(text)
            } else {
                content.append(text, 0, remaining)
                truncated = true
            }
        }

        fun value(): String = content.toString()
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 65_536
        private val ACTIVE_BY_SESSION = java.util.concurrent.ConcurrentHashMap<Any, ProjectBuildEventCollector>()
        private val ACTIVE_BY_PROJECT = java.util.concurrent.ConcurrentHashMap<Project, ProjectBuildEventCollector>()

        fun create(project: Project, sessionId: Any): ProjectBuildEventCollector {
            val collector = ProjectBuildEventCollector(project, sessionId)
            check(ACTIVE_BY_PROJECT.putIfAbsent(project, collector) == null) {
                "A build_project execution is already running for `${project.name}`"
            }
            check(ACTIVE_BY_SESSION.putIfAbsent(sessionId, collector) == null) {
                ACTIVE_BY_PROJECT.remove(project, collector)
                "A build collector is already registered for this ProjectTaskContext session"
            }
            try {
                collector.subscribe()
                return collector
            } catch (error: Throwable) {
                ACTIVE_BY_SESSION.remove(sessionId, collector)
                ACTIVE_BY_PROJECT.remove(project, collector)
                throw error
            }
        }

        fun active(project: Project): ProjectBuildEventCollector? = ACTIVE_BY_PROJECT[project]
    }
}


private fun List<ProjectBuildEventCollector.Diagnostic>.toJson() = JsonArray().apply {
    for (diagnostic in this@toJson) {
        add(JsonObject().apply {
            diagnostic.message?.let { addProperty("message", it) }
            diagnostic.description?.takeIf { it.isNotBlank() && it != diagnostic.message }
                ?.let { addProperty("description", it) }
            diagnostic.file?.let { addProperty("file", it) }
            diagnostic.line?.let { addProperty("line", it) }
            diagnostic.column?.let { addProperty("column", it) }
        })
    }
}
