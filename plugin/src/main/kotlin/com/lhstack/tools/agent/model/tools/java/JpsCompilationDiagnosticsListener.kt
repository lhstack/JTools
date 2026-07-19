package com.lhstack.tools.agent.model.tools.java

import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.lhstack.tools.agent.model.tools.ProjectBuildEventCollector

/** Optional Java/JPS adapter for the generic project build collector. */
class JpsCompilationDiagnosticsListener(private val project: Project) : CompilationStatusListener {
    override fun compilationFinished(
        aborted: Boolean,
        errors: Int,
        warnings: Int,
        compileContext: CompileContext,
    ) {
        val collector = ProjectBuildEventCollector.active(project) ?: return
        collector.structuredDiagnosticsStarted()
        collect(compileContext, CompilerMessageCategory.ERROR, collector::error)
        collect(compileContext, CompilerMessageCategory.WARNING, collector::warning)
    }

    private fun collect(
        context: CompileContext,
        category: CompilerMessageCategory,
        accept: (ProjectBuildEventCollector.Diagnostic) -> Unit,
    ) {
        context.getMessages(category).forEach { accept(it.toDiagnostic()) }
    }

    private fun CompilerMessage.toDiagnostic(): ProjectBuildEventCollector.Diagnostic {
        val location = navigatable as? OpenFileDescriptor
        return ProjectBuildEventCollector.Diagnostic(
            message = message,
            file = virtualFile?.path,
            line = location?.line?.takeIf { it >= 0 }?.plus(1),
            column = location?.column?.takeIf { it >= 0 }?.plus(1),
        )
    }
}
