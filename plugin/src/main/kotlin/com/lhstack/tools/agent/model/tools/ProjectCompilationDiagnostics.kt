package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.util.Key
import com.intellij.task.ProjectTaskContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ProjectTaskContext is the cross-product boundary: product-specific build integrations may
 * attach diagnostics here without coupling the generic compile tool to Java, Rust, Go, etc.
 */
internal object ProjectCompilationDiagnostics {
    private val KEY = Key.create<Collector>("com.lhstack.tools.compileProject.diagnostics")
    private val ACTIVE = java.util.concurrent.ConcurrentHashMap<Any, Collector>()

    fun prepare(context: ProjectTaskContext) {
        val collector = Collector()
        context.putUserData(KEY, collector)
        context.sessionId?.let { ACTIVE[it] = collector }
    }

    fun activeCollector(context: ProjectTaskContext): Collector? =
        context.sessionId?.let(ACTIVE::get)

    fun finish(context: ProjectTaskContext) {
        context.sessionId?.let { ACTIVE.remove(it) }
    }

    fun collector(context: ProjectTaskContext): Collector =
        requireNotNull(context.getUserData(KEY)) { "Compilation diagnostics context was not prepared" }

    fun collect(context: ProjectTaskContext): Snapshot =
        context.getUserData(KEY)?.snapshot() ?: Snapshot(emptyList(), emptyList())

    data class Diagnostic(
        val message: String,
        val file: String? = null,
        val line: Int? = null,
        val column: Int? = null,
    )

    class Collector {
        private val errors = CopyOnWriteArrayList<Diagnostic>()
        private val warnings = CopyOnWriteArrayList<Diagnostic>()

        fun error(diagnostic: Diagnostic) {
            errors.add(diagnostic)
        }

        fun warning(diagnostic: Diagnostic) {
            warnings.add(diagnostic)
        }

        fun snapshot(): Snapshot = Snapshot(errors.toList(), warnings.toList())
    }

    data class Snapshot(
        private val errorDiagnostics: List<Diagnostic>,
        private val warningDiagnostics: List<Diagnostic>,
    ) {
        val errorCount: Int get() = errorDiagnostics.size
        val warningCount: Int get() = warningDiagnostics.size
        val errors: JsonArray get() = errorDiagnostics.toJson()
        val warnings: JsonArray get() = warningDiagnostics.toJson()
    }

    private fun List<Diagnostic>.toJson() = JsonArray().apply {
        for (diagnostic in this@toJson) {
            add(JsonObject().apply {
                addProperty("message", diagnostic.message)
                diagnostic.file?.let { addProperty("file", it) }
                diagnostic.line?.let { addProperty("line", it) }
                diagnostic.column?.let { addProperty("column", it) }
            })
        }
    }
}
