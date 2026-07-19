package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * web_fetch 工具。完全照抄 awake-claw tools.rs 的 WebFetchTool。
 *
 * 发送 HTTP 请求，支持 GET/POST/PUT/DELETE、headers、proxy、多种 body_type
 * （none/text/json/form/multipart/file）。响应按 max_response_bytes 截断。
 *
 * 与 awake 差异：reqwest -> JDK11 HttpClient。multipart 手工按 boundary 拼装
 * （JDK HttpClient 无原生 multipart）。取消通过 ModelCancel 检查 + 请求前后判定。
 * body_type 兼容 awake 的别名：form 含 x-www-form-urlencoded/urlencoded，
 * multipart 含 form_data。
 */
class WebFetchTool(
    private val workspace: WorkspaceTools,
    private val defaultProxy: String?,
    private val defaultTimeoutSecs: Long,
    private val defaultMaxResponseBytes: Long,
    private val cancel: ModelCancel? = null,
) : ToolDyn {

    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = NAME,
        description = "发送 HTTP 请求，支持请求方法、请求头、代理和多种请求体。",
        parameters = JsonParser.parseString(DEFINITION_JSON),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val obj = args.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val url = stringField(obj, "url") ?: throw ToolException.missingBody("url")
        val method = parseHttpMethod(stringField(obj, "method"))
        val timeoutSecs = longField(obj, "timeout_secs")?.coerceIn(1, 300) ?: defaultTimeoutSecs.coerceIn(1, 300)
        val maxResponseBytes = longField(obj, "max_response_bytes")?.coerceIn(1, 5_000_000)
            ?: defaultMaxResponseBytes.coerceIn(1, 5_000_000)

        checkCancelled()

        val clientBuilder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSecs))
        val proxy = stringField(obj, "proxy")?.takeIf { it.isNotBlank() } ?: defaultProxy?.takeIf { it.isNotBlank() }
        proxy?.let { clientBuilder.proxy(proxySelector(it)) }
        val client = clientBuilder.build()

        val requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSecs))

        obj.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (name, value) ->
            val headerValue = value.takeIf { it.isJsonPrimitive }?.asString
                ?: throw ToolException.invalidJsonBody("header `$name` must be a string")
            runCatching { requestBuilder.header(name, headerValue) }
                .onFailure { throw ToolException("invalid header `$name`") }
        }

        applyBody(requestBuilder, method, obj)

        checkCancelled()
        val response = try {
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Throwable) {
            if (cancel?.isCancelled() == true) throw ToolException.cancelled()
            throw e
        }

        val bytes = response.body()
        val truncated = bytes.size > maxResponseBytes
        val bodyBytes = if (truncated) bytes.copyOfRange(0, maxResponseBytes.toInt()) else bytes
        val status = response.statusCode()

        return JsonObject().apply {
            addProperty("status", status)
            addProperty("success", status in 200..299)
            addProperty("final_url", response.uri().toString())
            add("headers", responseHeaders(response))
            addProperty("body", String(bodyBytes, StandardCharsets.UTF_8))
            addProperty("bytes_read", bodyBytes.size)
            addProperty("truncated", truncated)
        }
    }

    /** 照抄 apply_web_fetch_body：按 body_type 组装请求体。 */
    private fun applyBody(builder: HttpRequest.Builder, method: String, obj: JsonObject) {
        val bodyType = stringField(obj, "body_type")
        if (bodyType == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
            return
        }
        when (bodyType) {
            "none" -> builder.method(method, HttpRequest.BodyPublishers.noBody())
            "text" -> {
                val body = stringField(obj, "body") ?: throw ToolException.missingBody(bodyType)
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            }
            "json" -> {
                val jsonBody = stringField(obj, "json_body")?.takeIf { it.isNotBlank() }
                    ?: throw ToolException.missingBody(bodyType)
                try {
                    JsonParser.parseString(jsonBody)
                } catch (e: Throwable) {
                    throw ToolException.invalidJsonBody(e.message ?: e.toString())
                }
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
            }
            "x-www-form-urlencoded", "urlencoded", "form" -> {
                val form = obj.get("form")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw ToolException.missingBody(bodyType)
                val encoded = form.entrySet().joinToString("&") { (key, value) ->
                    val v = value.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    "${urlEncode(key)}=${urlEncode(v)}"
                }
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                builder.method(method, HttpRequest.BodyPublishers.ofString(encoded))
            }
            "form_data", "multipart" -> {
                val parts = obj.get("form_data")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: throw ToolException.missingBody(bodyType)
                val boundary = "----jtools${UUID.randomUUID().toString().replace("-", "")}"
                val body = buildMultipart(parts, boundary)
                builder.header("Content-Type", "multipart/form-data; boundary=$boundary")
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body))
            }
            "file" -> {
                val file = obj.get("file")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw ToolException.missingBody(bodyType)
                val path = stringField(file, "path") ?: throw ToolException.missingBody(bodyType)
                val resolved = try {
                    workspace.resolveExistingPath(path)
                } catch (e: ToolException) {
                    throw ToolException("file error: ${e.message}")
                }
                stringField(file, "mime")?.let { builder.header("Content-Type", it) }
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(resolved.readBytes()))
            }
            else -> throw ToolException.invalidBodyType(bodyType)
        }
    }

    /** 照抄 build_multipart_form：文本段 / 文件段按 boundary 拼装。 */
    private fun buildMultipart(parts: com.google.gson.JsonArray, boundary: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun writeText(text: String) = out.write(text.toByteArray(StandardCharsets.UTF_8))

        for (element in parts) {
            if (!element.isJsonObject) continue
            val part = element.asJsonObject
            val name = stringField(part, "name") ?: throw ToolException.missingBody("form_data part")
            val filePath = stringField(part, "file_path")
            writeText("--$boundary\r\n")
            if (filePath != null) {
                val resolved = try {
                    workspace.resolveExistingPath(filePath)
                } catch (e: ToolException) {
                    throw ToolException("file error: ${e.message}")
                }
                val fileName = stringField(part, "file_name") ?: resolved.name
                writeText("Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n")
                val mime = stringField(part, "mime") ?: "application/octet-stream"
                writeText("Content-Type: $mime\r\n\r\n")
                out.write(resolved.readBytes())
                writeText("\r\n")
            } else {
                val value = stringField(part, "value")
                    ?: throw ToolException.missingBody("form_data part `$name`")
                writeText("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                writeText(value)
                writeText("\r\n")
            }
        }
        writeText("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun proxySelector(proxy: String): ProxySelector {
        val uri = URI.create(proxy)
        val host = uri.host ?: throw ToolException("invalid proxy `$proxy`")
        val port = if (uri.port > 0) uri.port else 80
        return ProxySelector.of(InetSocketAddress(host, port))
    }

    private fun responseHeaders(response: HttpResponse<*>): JsonObject = JsonObject().apply {
        response.headers().map().forEach { (name, values) ->
            addProperty(name, values.joinToString(", "))
        }
    }

    private fun checkCancelled() {
        if (cancel?.isCancelled() == true) throw ToolException.cancelled()
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun stringField(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun longField(obj: JsonObject, key: String): Long? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    /** 照抄 parse_http_method：默认 GET，只允许 GET/POST/PUT/DELETE。 */
    private fun parseHttpMethod(method: String?): String {
        val m = method?.trim()?.takeIf { it.isNotEmpty() } ?: "GET"
        return when (m.uppercase()) {
            "GET", "POST", "PUT", "DELETE" -> m.uppercase()
            else -> throw ToolException.invalidMethod(m)
        }
    }

    companion object {
        const val NAME = "web_fetch"

        private val DEFINITION_JSON = """
        {
            "type": "object",
            "properties": {
                "method": {
                    "type": "string",
                    "enum": ["GET", "POST", "PUT", "DELETE"],
                    "description": "可选。请求方法，省略时为 GET。"
                },
                "url": { "type": "string", "description": "必填。HTTP 或 HTTPS 绝对地址。" },
                "headers": {
                    "type": "object",
                    "additionalProperties": { "type": "string" },
                    "description": "可选。请求头。"
                },
                "proxy": {
                    "type": "string",
                    "description": "可选。代理地址，覆盖默认代理。"
                },
                "timeout_secs": { "type": "integer", "description": "可选。请求超时秒数。" },
                "max_response_bytes": { "type": "integer", "description": "可选。最大响应字节数，默认使用配置值。" },
                "body_type": {
                    "type": "string",
                    "enum": ["none", "text", "json", "form", "multipart", "file"],
                    "description": "可选。请求体类型。"
                },
                "body": { "type": "string", "description": "可选。纯文本请求体。" },
                "json_body": { "type": "string", "description": "可选。JSON 字符串请求体。" },
                "form": {
                    "type": "object",
                    "additionalProperties": { "type": "string" },
                    "description": "可选。表单字段。"
                },
                "form_data": {
                    "type": "array",
                    "description": "可选。Multipart 表单项。",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": { "type": "string" },
                            "value": { "type": "string" },
                            "file_path": { "type": "string", "description": "可选。文件项的工作区相对路径。" },
                            "file_name": { "type": "string" },
                            "mime": { "type": "string" }
                        },
                        "required": ["name"]
                    }
                },
                "file": {
                    "type": "object",
                    "description": "可选。原始文件上传请求体。",
                    "properties": {
                        "path": { "type": "string", "description": "必填。工作区相对路径。" },
                        "mime": { "type": "string" }
                    },
                    "required": ["path"]
                }
            },
            "required": ["url"]
        }
        """.trimIndent()
    }
}
