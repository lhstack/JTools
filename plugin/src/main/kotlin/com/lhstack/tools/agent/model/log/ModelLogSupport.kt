package com.lhstack.tools.agent.model.log

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 模型日志脱敏 helper。完全照抄 awake-claw repository/helpers.rs 的
 * simplify_log_value / truncate_log_text / simplify_log_string 等。
 *
 * 作用：写入 model_request_logs 前把请求/响应体里的内联 base64 媒体替换成占位符，
 * 并把 error 类字段截断到上限，避免日志表膨胀。语义与 awake 一一对应。
 */
object ModelLogSupport {

    const val LOG_TEXT_LIMIT = 8192

    /** 照抄 truncate_log_text：按字符数截断到上限。 */
    fun truncateLogText(value: String): String {
        if (value.length <= LOG_TEXT_LIMIT) {
            return value
        }
        return value.substring(0, LOG_TEXT_LIMIT)
    }

    /** 照抄 simplify_log_value。 */
    fun simplifyLogValue(value: JsonElement): JsonElement = when {
        value.isJsonArray -> JsonArray().apply {
            value.asJsonArray.forEach { add(simplifyLogValue(it)) }
        }

        value.isJsonObject -> JsonObject().apply {
            for ((key, child) in value.asJsonObject.entrySet()) {
                add(key, simplifyLogField(key, child))
            }
        }

        value.isJsonPrimitive && value.asJsonPrimitive.isString ->
            JsonPrimitive(simplifyLogString("", value.asString))

        else -> value
    }

    /** 照抄 simplify_log_field。 */
    private fun simplifyLogField(key: String, value: JsonElement): JsonElement = when {
        value.isJsonPrimitive && value.asJsonPrimitive.isString ->
            JsonPrimitive(simplifyLogString(key, value.asString))

        value.isJsonArray -> JsonArray().apply {
            value.asJsonArray.forEach { add(simplifyLogValue(it)) }
        }

        value.isJsonObject -> JsonObject().apply {
            for ((childKey, childValue) in value.asJsonObject.entrySet()) {
                add(childKey, simplifyLogField(childKey, childValue))
            }
        }

        else -> value
    }

    /** 照抄 simplify_log_string：媒体 key 的内联 base64 替换成占位，error key 截断。 */
    private fun simplifyLogString(key: String, value: String): String {
        val isMediaKey = key in MEDIA_KEYS
        if (isMediaKey && looksLikeInlineBase64(value)) {
            return "[base64 omitted, chars=${value.length}]"
        }
        if (isErrorKey(key)) {
            return truncateLogText(value)
        }
        return value
    }

    private fun isErrorKey(key: String): Boolean {
        val lower = key.lowercase()
        return lower == "error" || lower == "error_data" || lower == "exception" || lower.endsWith("_error")
    }

    /** 照抄 looks_like_inline_base64。 */
    private fun looksLikeInlineBase64(value: String): Boolean {
        if (value.startsWith("data:") && value.contains(";base64,")) {
            return true
        }
        return value.length > LOG_TEXT_LIMIT &&
            value.all { ch -> ch.isLetterOrDigit() && ch.code < 128 || ch == '+' || ch == '/' || ch == '=' || ch == '\r' || ch == '\n' }
    }

    private val MEDIA_KEYS = setOf("data", "image_url", "file_data", "audio", "video", "input_audio")
}
