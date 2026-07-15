package com.lhstack.tools.agent.model.tools.java

import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskListener
import com.intellij.task.ProjectTaskManager
import com.lhstack.tools.agent.model.tools.ProjectCompilationDiagnostics
import java.util.concurrent.ConcurrentHashMap

/** Java/JPS adapter loaded only when the optional com.intellij.java dependency is present. */
class JavaCompilationDiagnosticsListener(private val project: Project) :
    CompilationStatusListener,
    ProjectTaskListener {

    override fun started(context: ProjectTaskContext) {
        val collector = ProjectCompilationDiagnostics.activeCollector(context) ?: return
        ACTIVE_COLLECTORS[project] = collector
    }

    override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, compileContext: CompileContext) {
        val collector = ACTIVE_COLLECTORS[project] ?: return
        collectMessages(compileContext, CompilerMessageCategory.ERROR, collector::error)
        collectMessages(compileContext, CompilerMessageCategory.WARNING, collector::warning)
    }

    override fun finished(result: ProjectTaskManager.Result) {
        ACTIVE_COLLECTORS.remove(project)
    }

    private fun collectMessages(
        context: CompileContext,
        category: CompilerMessageCategory,
        accept: (ProjectCompilationDiagnostics.Diagnostic) -> Unit,
    ) {
        for (message in context.getMessages(category)) {
            accept(message.toDiagnostic())
        }
    }

    private fun CompilerMessage.toDiagnostic(): ProjectCompilationDiagnostics.Diagnostic {
        val location = navigatable as? OpenFileDescriptor
        return ProjectCompilationDiagnostics.Diagnostic(
            message = message,
            file = virtualFile?.path,
            line = location?.line?.takeIf { it >= 0 }?.plus(1),
            column = location?.column?.takeIf { it >= 0 }?.plus(1),
        )
    }

    companion object {
        private val ACTIVE_COLLECTORS =
            ConcurrentHashMap<Project, ProjectCompilationDiagnostics.Collector>()
    }
}
