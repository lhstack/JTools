package com.lhstack.tools.sandbox

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.lhstack.tools.plugins.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 沙箱启动不引导 Agent DB，也不扫描、安装或清理正式插件。 */
object SandboxBootstrap {
    private val starter = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "jtools-sandbox-bootstrap").apply { isDaemon = true } }
    fun start() {
        JToolsPaths.home(System.getProperties())
        starter.scheduleWithFixedDelay({
            val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
            if (project != null) {
                starter.shutdown()
                try {
                    val loader = SandboxBootstrap::class.java.classLoader
                    val name = loader.getResourceAsStream("META-INF/JToolsSandbox.txt")?.bufferedReader()?.use { it.readText().trim() }
                        ?: error("沙箱缺少 plugin-debugger 运行端")
                    val runtime = loader.loadClass(name).getConstructor().newInstance() as SandboxRuntime
                    runtime.start(project, SandboxPluginHost(project))
                } catch (error: Throwable) {
                    Files.writeString(Path.of(System.getProperty("jtools.sandbox.root"), "startup-error.txt"), error.stackTraceToString())
                }
            }
        }, 0, 250, TimeUnit.MILLISECONDS)
    }
}
