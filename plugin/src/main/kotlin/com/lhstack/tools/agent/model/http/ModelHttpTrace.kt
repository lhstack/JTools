package com.lhstack.tools.agent.model.http

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger

/**
 * 模型 HTTP 调用轨迹。完全照抄 awake-claw 的 ModelHttpTrace（src/service/model_provider.rs）。
 *
 * 关键行为（与 awake 测试约定一致）：
 * - first_request 只记录第一次 request，后续 request 不覆盖（工具回环里首轮才是日志真源）。
 * - retry_count 只由显式 retry() 累加，不从重复 request 推断。
 * - response_data 成功时只带 structured_response + retry_count；失败时带 last_response。
 *
 * 线程安全：内部状态用 synchronized 保护，对齐 Rust 的 Mutex。
 */
class ModelHttpTrace(private val traceId: String) {

    private val lock = Any()
    private var retryCount: Int = 0
    private var firstRequest: JsonElement = JsonNull.INSTANCE
    private var lastResponse: JsonElement = JsonNull.INSTANCE

    fun request(url: String, body: JsonElement) {
        LOG.info("[model-http] trace_id=$traceId request url=$url body=$body")
        synchronized(lock) {
            if (firstRequest.isJsonNull) {
                firstRequest = JsonObject().apply {
                    addProperty("url", url)
                    add("body", body)
                }
            }
        }
    }

    fun response(url: String, body: JsonElement) {
        LOG.info("[model-http] trace_id=$traceId response url=$url body=$body")
        synchronized(lock) {
            lastResponse = JsonObject().apply {
                addProperty("type", "response")
                addProperty("url", url)
                add("body", body)
            }
        }
    }

    fun error(url: String, status: String?, error: String, body: JsonElement?) {
        LOG.info("[model-http] trace_id=$traceId error url=$url status=${status ?: "-"} error=$error")
        synchronized(lock) {
            lastResponse = JsonObject().apply {
                addProperty("type", "error")
                addProperty("url", url)
                if (status != null) addProperty("status", status) else add("status", JsonNull.INSTANCE)
                addProperty("error", error)
                add("body", body ?: JsonNull.INSTANCE)
            }
        }
    }

    fun retry() {
        synchronized(lock) { retryCount += 1 }
    }

    fun requestData(): JsonObject = synchronized(lock) {
        val first = firstRequest
        JsonObject().apply {
            addProperty("retry_count", retryCount)
            if (first.isJsonObject) {
                add("url", first.asJsonObject.get("url") ?: JsonNull.INSTANCE)
                add("body", first.asJsonObject.get("body") ?: JsonNull.INSTANCE)
            } else {
                add("url", JsonNull.INSTANCE)
                add("body", JsonNull.INSTANCE)
            }
        }
    }

    fun responseData(structuredResponse: JsonElement?): JsonObject = synchronized(lock) {
        JsonObject().apply {
            addProperty("retry_count", retryCount)
            if (structuredResponse != null) {
                add("structured_response", structuredResponse)
            } else {
                add("last_response", lastResponse)
                add("structured_response", JsonNull.INSTANCE)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ModelHttpTrace::class.java)
    }
}
