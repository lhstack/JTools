package com.lhstack.tools.agent.model.log

import com.intellij.openapi.diagnostic.Logger
import com.lhstack.tools.const.Const
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 编码消息首字延迟观测。只写阶段、标识和耗时，不写请求正文、响应正文或工具参数。
 */
class FirstTokenTrace(
    private val sessionId: Long,
    private val taskId: Long,
    private val turnId: String,
) {
    private val traceId = UUID.randomUUID().toString()
    private val startedAt = System.nanoTime()
    private val markedPhases = mutableSetOf<String>()

    fun mark(phase: String) {
        val shouldWrite = synchronized(markedPhases) { markedPhases.add(phase) }
        if (!shouldWrite) return

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val line = buildString {
            append(Instant.now())
            append(" trace_id=")
            append(traceId)
            append(" session_id=")
            append(sessionId)
            append(" task_id=")
            append(taskId)
            append(" turn_id=")
            append(turnId)
            append(" phase=")
            append(phase)
            append(" elapsed_ms=")
            append(elapsedMs)
        }

        try {
            synchronized(FILE_LOCK) {
                Files.createDirectories(LOG_FILE.parent)
                Files.writeString(
                    LOG_FILE,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
            }
        } catch (error: Exception) {
            LOG.warn("无法写入首字延迟日志 `$LOG_FILE`", error)
        }
    }

    private companion object {
        private val LOG_FILE = File(Const.JTOOLS_PLUGIN_HOME, "agent/first-token.log").toPath()
        private val FILE_LOCK = Any()
        private val LOG = Logger.getInstance(FirstTokenTrace::class.java)
    }
}
