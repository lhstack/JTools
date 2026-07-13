package com.lhstack.tools.agent.model.http

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.lhstack.tools.agent.model.llm.DocumentSourceKind
import com.lhstack.tools.agent.model.llm.Image
import com.lhstack.tools.agent.model.llm.ImageDetail

/**
 * 模型客户端公共 helper。完全照抄 awake-claw src/service/model_provider.rs 里
 * 被 openai / anthropic 复用的 pub(super) fn。
 *
 * serde_json::Value -> Gson JsonElement。这些函数只做数据搬运和结构转换，
 * 不承载任何模型业务判断。
 */
object ModelHttpSupport {

    /** 照抄 join_url：base 去尾部 '/' 后直接拼 path。 */
    fun joinUrl(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + path

    /** 照抄 parse_args：能解析成 JSON 就用，否则原样当字符串。 */
    fun parseArgs(value: String): JsonElement =
        try {
            JsonParser.parseString(value)
        } catch (_: Throwable) {
            JsonPrimitive(value)
        }

    /** 照抄 object_from_named_params：null 视为空对象，非对象报错。 */
    fun objectFromNamedParams(params: JsonElement?, label: String): JsonObject {
        val value = params ?: return JsonObject()
        if (value.isJsonNull) return JsonObject()
        if (!value.isJsonObject) {
            throw IllegalArgumentException("$label 必须是 JSON object")
        }
        return value.asJsonObject.deepCopy()
    }

    /** 照抄 object_from_additional_params。 */
    fun objectFromAdditionalParams(params: JsonElement?): JsonObject =
        objectFromNamedParams(params, "additional_params")

    /** 照抄 merged_model_and_additional_params：model_params 打底，additional 覆盖。 */
    fun mergedModelAndAdditionalParams(
        modelParams: JsonElement?,
        additionalParams: JsonElement?,
    ): JsonObject {
        val map = objectFromNamedParams(modelParams, "model_params")
        val additional = objectFromAdditionalParams(additionalParams)
        for ((key, value) in additional.entrySet()) {
            map.add(key, value)
        }
        return map
    }

    /** 照抄 serialized_request_body + remove_null_object_fields：递归剔除 null 字段。 */
    fun serializedRequestBody(body: JsonElement): JsonElement {
        val value = body.deepCopy()
        removeNullObjectFields(value)
        return value
    }

    private fun removeNullObjectFields(value: JsonElement) {
        when {
            value.isJsonObject -> {
                val obj = value.asJsonObject
                val nullKeys = obj.entrySet().filter { it.value.isJsonNull }.map { it.key }
                nullKeys.forEach { obj.remove(it) }
                obj.entrySet().forEach { removeNullObjectFields(it.value) }
            }

            value.isJsonArray -> {
                value.asJsonArray.forEach { removeNullObjectFields(it) }
            }
        }
    }

    /** 照抄 image_url。 */
    fun imageUrl(image: Image): String = image.tryIntoUrl()

    /** 照抄 image_base64_parts：Anthropic 图片必须是 base64 且带 media_type。 */
    fun imageBase64Parts(image: Image): Pair<String, String> {
        val mediaType = image.mediaType
            ?: throw IllegalStateException("Anthropic image requires media_type")
        val source = image.data
        if (source !is DocumentSourceKind.Base64) {
            throw IllegalStateException("Anthropic image requires base64 data")
        }
        return mediaType.toMimeType() to source.data
    }

    /** 照抄 source_data：四种来源都取内部字符串。 */
    fun sourceData(source: DocumentSourceKind): String = source.data

    /** 照抄 image_detail。 */
    fun imageDetail(detail: ImageDetail): String = when (detail) {
        ImageDetail.Auto -> "auto"
        ImageDetail.Low -> "low"
        ImageDetail.High -> "high"
    }

    /** 照抄 take_optional：从 map 移除并按目标类型反序列化。这里返回原始 JsonElement，由调用方解释。 */
    fun takeOptional(params: JsonObject, key: String): JsonElement? {
        if (!params.has(key)) return null
        val value = params.get(key)
        params.remove(key)
        if (value.isJsonNull) return null
        return value
    }
}
