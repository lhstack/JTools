package com.lhstack.tools.debugger

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

internal object SandboxStartupDiagnostics {
    fun pendingMessage(root: Path): String {
        val error = root.resolve("startup-error.txt")
        if (Files.isRegularFile(error)) return "沙箱运行端启动失败：${tail(error)}"
        return describe(tail(root.resolve("process.log")))
    }

    internal fun describe(log: String): String = when {
        log.contains("AgreementUi") || log.contains("showDataSharingAgreement") || log.contains("showEndUserAgreement") ->
            "IDE 首次启动协议或数据共享流程可能阻塞启动；请检查沙箱窗口。必要许可协议需手动确认一次，专用配置会保留初始化状态；不会自动接受协议。"

        log.contains("ConfigImport") ->
            "IDE 正处于配置导入流程；请检查沙箱窗口，不要导入日常配置。"

        else -> "运行端尚未就绪；使用 status.process 检查进程、startup.logs 查看日志；若长时间未就绪，请检查沙箱窗口是否有许可或启动提示。"
    }

    private fun tail(path: Path): String {
        if (!Files.isRegularFile(path)) return ""
        val buffer = ByteBuffer.allocate(32000)
        Files.newByteChannel(path).use { channel ->
            channel.position((channel.size() - buffer.capacity()).coerceAtLeast(0))
            channel.read(buffer)
        }
        return String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
    }
}
