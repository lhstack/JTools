package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** File-backed capture; command output size never controls process lifetime. */
internal class BashOutputSupport {
    data class Capture(val directory: Path) {
        fun raw(stream: String): Path = directory.resolve("$stream.raw")
    }
    private data class StreamFile(val path: Path, val chars: Int, val bytes: Long)
    private data class Output(val stdout: StreamFile, val stderr: StreamFile)

    fun begin(limit: Int): Capture {
        validateConfiguredLimit(limit.toString())
        return Capture(Files.createTempDirectory("jtools-bash-output-"))
    }

    fun deliver(capture: Capture, charset: Charset, exitCode: Int?, timedOut: Boolean, cancelled: Boolean, limit: Int): JsonObject {
        try {
            val output = Output(convert(capture, "stdout", charset), convert(capture, "stderr", charset))
            val response = status(exitCode, timedOut, cancelled).apply {
                addProperty("stdout", "")
                addProperty("stderr", "")
            }
            val total = output.stdout.chars.toLong() + output.stderr.chars
            if (total <= limit) {
                response.addProperty("stdout", Files.readString(output.stdout.path, UTF_8))
                response.addProperty("stderr", Files.readString(output.stderr.path, UTF_8))
                if (ToolOutputText.size(response) <= limit) {
                    discard(capture)
                    return response
                }
            }
            val result = receipt(output, exitCode, timedOut, cancelled)
            require(ToolOutputText.size(result) <= limit) { "Bash 返回字符上限过小，无法容纳文件信息" }
            return result
        } catch (error: Exception) {
            try {
                discard(capture)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    fun discard(capture: Capture) {
        if (!Files.exists(capture.directory)) return
        Files.list(capture.directory).use { paths -> paths.forEach { Files.delete(it) } }
        Files.delete(capture.directory)
    }

    private fun convert(capture: Capture, stream: String, charset: Charset): StreamFile {
        val path = capture.directory.resolve("$stream.txt")
        var chars = 0
        var previousHigh = false
        Files.newBufferedReader(capture.raw(stream), charset).use { reader ->
            Files.newBufferedWriter(path, UTF_8).use { writer ->
                val buffer = CharArray(8192)
                while (true) {
                    val length = reader.read(buffer)
                    if (length < 0) break
                    writer.write(buffer, 0, length)
                    for (index in 0 until length) {
                        val ch = buffer[index]
                        if (!(previousHigh && Character.isLowSurrogate(ch))) chars = Math.addExact(chars, 1)
                        previousHigh = Character.isHighSurrogate(ch)
                    }
                }
            }
        }
        Files.delete(capture.raw(stream))
        return StreamFile(path, chars, Files.size(path))
    }

    private fun status(exitCode: Int?, timedOut: Boolean, cancelled: Boolean) = JsonObject().apply {
        if (exitCode == null) add("exit_code", JsonNull.INSTANCE) else addProperty("exit_code", exitCode)
        addProperty("success", exitCode == 0 && !timedOut && !cancelled)
        if (timedOut) addProperty("timed_out", true)
        if (cancelled) addProperty("cancelled", true)
    }

    private fun receipt(output: Output, exitCode: Int?, timedOut: Boolean, cancelled: Boolean) =
        status(exitCode, timedOut, cancelled).apply {
            addProperty("summary", "Bash 输出超过返回上限，完整已捕获内容已保存为 UTF-8 文件，可用 bash 按需分批读取。")
            add("files", JsonObject().apply {
                for ((name, file) in listOf("stdout" to output.stdout, "stderr" to output.stderr)) {
                    add(name, JsonObject().apply {
                        addProperty("path", file.path.toString())
                        addProperty("chars", file.chars)
                        addProperty("bytes", file.bytes)
                    })
                }
            })
        }

    companion object {
        const val MIN_OUTPUT_CHARS = 1024
        fun validateConfiguredLimit(raw: String): Int {
            val limit = raw.toIntOrNull()
            require(limit != null && limit >= MIN_OUTPUT_CHARS) { "Bash 最大返回字符必须为不小于 $MIN_OUTPUT_CHARS 的整数" }
            return limit
        }
    }
}
