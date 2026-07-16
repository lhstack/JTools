package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.BuildIssueEvent
import com.intellij.build.events.Failure
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Collects the platform BuildEvent stream for one ProjectTaskManager execution. */
internal class ProjectBuildEventCollector(
    private val project: Project,
    private val sessionId: Any,
) : BuildProgressListener, Disposable {
    private val errors = CopyOnWriteArrayList<Diagnostic>()
    private val warnings = CopyOnWriteArrayList<Diagnostic>()
    private val stdout = BoundedOutput()
    private val stderr = BoundedOutput()
    private val observedBuild = AtomicBoolean(false)
    private val structuredDiagnosticsAvailable = AtomicBoolean(false)
    private val acceptedBuildIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Any>().apply { add(sessionId) }

    fun subscribe() {
        project.getService(BuildViewManager::class.java).addListener(this, this)
    }

    override fun onEvent(buildId: Any, event: BuildEvent) {
        if (!belongsToExecution(buildId, event)) return
        observedBuild.set(true)
        when (event) {
            is FileMessageEvent -> collectFileMessage(event)
            is BuildIssueEvent -> collectBuildIssue(event)
            is MessageEvent -> collectMessage(event)
            is OutputBuildEvent -> collectOutput(event)
            is FinishEvent -> collectFailures(event)
        }
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

    private fun belongsToExecution(buildId: Any, event: BuildEvent): Boolean {
        val belongs = buildId in acceptedBuildIds ||
            event.id in acceptedBuildIds ||
            event.parentId in acceptedBuildIds ||
            (event is StartBuildEvent && event.buildDescriptor.id in acceptedBuildIds)
        if (belongs) {
            acceptedBuildIds.add(buildId)
            acceptedBuildIds.add(event.id)
            if (event is StartBuildEvent) acceptedBuildIds.add(event.buildDescriptor.id)
        }
        return belongs
    }

    private fun collectFileMessage(event: FileMessageEvent) {
        val position = event.filePosition
        collectMessage(
            event.kind,
            Diagnostic(
                message = event.message,
                description = event.description,
                file = position.file?.path,
                line = position.startLine.takeIf { it >= 0 }?.plus(1),
                column = position.startColumn.takeIf { it >= 0 }?.plus(1),
            ),
        )
    }

    private fun collectBuildIssue(event: BuildIssueEvent) {
        collectMessage(
            event.kind,
            Diagnostic(event.issue.title, event.issue.description),
        )
    }

    private fun collectMessage(event: MessageEvent) {
        collectMessage(event.kind, Diagnostic(event.message, event.description))
    }

    private fun collectMessage(kind: MessageEvent.Kind, diagnostic: Diagnostic) {
        when (kind) {
            MessageEvent.Kind.ERROR -> errors.add(diagnostic)
            MessageEvent.Kind.WARNING -> warnings.add(diagnostic)
            else -> Unit
        }
    }

    private fun collectOutput(event: OutputBuildEvent) {
        if (event.isStdOut) stdout.append(event.message) else stderr.append(event.message)
    }

    private fun collectFailures(event: FinishEvent) {
        val result = event.result as? FailureResult ?: return
        result.failures.forEach(::collectFailure)
    }

    private fun collectFailure(failure: Failure) {
        errors.add(Diagnostic(failure.message, failure.description))
        failure.causes.forEach(::collectFailure)
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
