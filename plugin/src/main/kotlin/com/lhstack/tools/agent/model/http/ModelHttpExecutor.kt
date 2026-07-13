package com.lhstack.tools.agent.model.http

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration

/**
 * 模型 HTTP 执行器。使用 OkHttp + Okio，承接 awake 的 reqwest bytes_stream 语义：
 * - 普通请求：同步执行 Call，返回完整响应文本。
 * - 流式请求：保留响应体与 BufferedSource，交给 SseParser 逐事件读取。
 * - 取消：ModelCancel.cancel() 直接调用 Call.cancel()，立即关闭底层连接和响应读取。
 *
 * SSE 生命周期由 SseParser.close() 负责关闭 Response；调用方用 use 包住 parser，
 * 因此收到 OpenAI/Anthropic 结束事件后会马上释放请求资源。
 */
class ModelHttpExecutor(
    private val client: OkHttpClient,
) {

    fun sendJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        cancel: ModelCancel?,
    ): String {
        val response = execute(jsonRequest(url, headers, body), cancel)
        response.use {
            try {
                val text = it.body?.string().orEmpty()
                ensureSuccess(it, text)
                return text
            } finally {
                cancel?.clearInterrupt()
            }
        }
    }

    fun sendJsonForElement(
        url: String,
        headers: Map<String, String>,
        body: String,
        cancel: ModelCancel?,
    ): JsonElement = JsonParser.parseString(sendJson(url, headers, body, cancel))

    fun getForText(
        url: String,
        headers: Map<String, String>,
        cancel: ModelCancel?,
    ): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .applyHeaders(headers)
            .build()
        val response = execute(request, cancel)
        response.use {
            try {
                val text = it.body?.string().orEmpty()
                ensureSuccess(it, text)
                return text
            } finally {
                cancel?.clearInterrupt()
            }
        }
    }

    /** 返回仍持有网络响应体的 SSE parser；调用方必须 use。 */
    fun sendJsonStream(
        url: String,
        headers: Map<String, String>,
        body: String,
        cancel: ModelCancel?,
    ): SseParser {
        if (cancel?.isCancelled() == true) {
            throw ModelRequestCancelledException()
        }
        val call = client.newCall(jsonRequest(url, headers, body))
        cancel?.registerInterrupt { call.cancel() }
        val response = try {
            call.execute()
        } catch (error: IOException) {
            cancel?.clearInterrupt()
            if (cancel?.isCancelled() == true || call.isCanceled()) {
                throw ModelRequestCancelledException()
            }
            throw error
        }
        try {
            val responseBody = response.body ?: throw IOException("model API stream response has no body")
            if (!response.isSuccessful) {
                val text = responseBody.string()
                throw ModelHttpStatusException(response.code, text)
            }
            return SseParser(response, responseBody.source(), cancel, call::cancel)
        } catch (error: Throwable) {
            cancel?.clearInterrupt()
            response.close()
            throw error
        }
    }

    private fun execute(request: Request, cancel: ModelCancel?): Response {
        if (cancel?.isCancelled() == true) {
            throw ModelRequestCancelledException()
        }
        val call = client.newCall(request)
        cancel?.registerInterrupt { call.cancel() }
        return try {
            call.execute()
        } catch (error: IOException) {
            cancel?.clearInterrupt()
            if (cancel?.isCancelled() == true || call.isCanceled()) {
                throw ModelRequestCancelledException()
            }
            throw error
        }
    }

    private fun jsonRequest(url: String, headers: Map<String, String>, body: String): Request =
        Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .applyHeaders(headers)
            .build()

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder = apply {
        header("Content-Type", "application/json")
        headers.forEach { (name, value) -> header(name, value) }
    }

    private fun ensureSuccess(response: Response, body: String) {
        if (!response.isSuccessful) {
            throw ModelHttpStatusException(response.code, body)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/** OkHttp 客户端默认连接/读取策略。流式响应不设 read timeout，直到结束事件或取消。 */
object ModelHttpClientDefaults {
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
    val CALL_TIMEOUT: Duration = Duration.ofMinutes(10)
}

class ModelHttpStatusException(
    val status: Int,
    val bodyText: String,
) : RuntimeException("model API returned $status: $bodyText")
