package com.lhstack.tools.llm

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 对齐 awake-claw model_provider 的 JSON 参数工具：
 * model_params 覆盖 additional_params；嵌套 object 递归合并；工具参数必须是 JSON object。
 */
object LlmJson {
    fun objectFromNamedParams(params: JsonElement?, label: String): JsonObject {
        val value = params ?: return JsonObject()
        if (value.isJsonNull) return JsonObject()
        require(value.isJsonObject) { "$label 必须是 JSON object" }
        return value.asJsonObject.deepCopy()
    }

    fun mergedModelAndAdditionalParams(
        modelParams: JsonElement?,
        additionalParams: JsonElement?,
    ): JsonObject {
        val merged = objectFromNamedParams(additionalParams, "additional_params")
        mergeParameterObjects(merged, objectFromNamedParams(modelParams, "model_params"))
        return merged
    }

    fun parseArgs(value: String): JsonElement {
        val arguments = try {
            JsonParser.parseString(value)
        } catch (error: Throwable) {
            throw IllegalStateException("模型返回的工具参数不是合法 JSON", error)
        }
        if (!arguments.isJsonObject) {
            throw IllegalStateException("模型返回的工具参数必须是 JSON object")
        }
        return arguments
    }

    fun removeNullRequestParams(params: JsonObject) {
        params.entrySet().filter { it.value.isJsonNull }.map { it.key }.forEach(params::remove)
    }

    fun imageUrl(image: Image): String = image.tryIntoUrl()

    fun imageBase64Parts(image: Image): Pair<String, String> {
        val mediaType = image.mediaType
            ?: throw IllegalStateException("Anthropic image requires media_type")
        val source = image.data
        if (source !is DocumentSourceKind.Base64) {
            throw IllegalStateException("Anthropic image requires base64 data")
        }
        return mediaType.toMimeType() to source.data
    }

    fun sourceData(source: DocumentSourceKind): String = source.data

    fun imageDetail(detail: ImageDetail): String = when (detail) {
        ImageDetail.Auto -> "auto"
        ImageDetail.Low -> "low"
        ImageDetail.High -> "high"
    }

    private fun mergeParameterObjects(target: JsonObject, overlay: JsonObject) {
        for ((key, value) in overlay.entrySet()) {
            val existing = target.get(key)
            if (existing != null && existing.isJsonObject && value.isJsonObject) {
                mergeParameterObjects(existing.asJsonObject, value.asJsonObject)
            } else {
                target.add(key, value.deepCopy())
            }
        }
    }
}
