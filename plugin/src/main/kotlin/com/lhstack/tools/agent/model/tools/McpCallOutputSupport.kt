package com.lhstack.tools.agent.model.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** Large MCP results are files, readable through normal shell commands. */
internal class McpCallOutputSupport {
    private val gson = Gson()
    private val pretty = GsonBuilder().setPrettyPrinting().create()

    fun validateLimit(limit: Int) {
        validateConfiguredLimit(limit.toString())
    }

    fun deliver(result: JsonElement, limit: Int): JsonElement {
        validateLimit(limit)
        val chars = ToolOutputText.count(gson.toJson(result))
        if (chars <= limit) return result
        val directory = Files.createTempDirectory("jtools-mcp-output-")
        val path = directory.resolve("result.json")
        try {
            val text = pretty.toJson(result)
            Files.writeString(path, text, UTF_8)
            return receipt(path, result, chars, ToolOutputText.count(text), Files.size(path)).also {
                require(ToolOutputText.size(it) <= limit) { "MCP 返回字符上限无法容纳结果文件信息" }
            }
        } catch (error: Exception) {
            try {
                Files.deleteIfExists(path)
                Files.deleteIfExists(directory)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    private fun receipt(path: Path, result: JsonElement, original: Int, chars: Int, bytes: Long): JsonObject {
        val value = result.takeIf { it.isJsonObject }?.asJsonObject
        return JsonObject().apply {
            value?.get("isError")?.let { add("isError", it.deepCopy()) }
            addProperty("summary", "MCP 结果超过返回上限，完整内容已保存为 UTF-8 JSON 文件，可用 bash 按需分批读取。")
            addProperty("content_blocks", value?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0)
            addProperty("has_structured_content", value?.get("structuredContent")?.let { !it.isJsonNull } == true)
            addProperty("original_chars", original)
            addProperty("path", path.toString())
            addProperty("file_chars", chars)
            addProperty("file_bytes", bytes)
        }
    }

    companion object {
        const val MIN_OUTPUT_CHARS = 1024

        fun validateConfiguredLimit(raw: String): Int {
            val limit = raw.toIntOrNull()
            require(limit != null && limit >= MIN_OUTPUT_CHARS) {
                "MCP 调用最大返回字符必须为不小于 $MIN_OUTPUT_CHARS 的整数"
            }
            return limit
        }
    }
}
