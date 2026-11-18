package com.lhstack.tools.llm.openai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** 只检查已知会按严格模式编译的函数，第三方兼容端点的非严格 Schema 不做泛化限制。 */
internal object OpenAiToolSchemaSupport {
    fun validate(tools: JsonArray?, responses: Boolean, strictByDefault: Boolean = false) {
        tools?.forEachIndexed { index, entry ->
            val tool = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            if (tool.get("type")?.asString != "function") return@forEachIndexed
            val function = if (responses) tool else tool.getAsJsonObject("function") ?: return@forEachIndexed
            if (!(function.get("strict")?.takeUnless { it.isJsonNull }?.asBoolean ?: strictByDefault)) return@forEachIndexed
            val path = firstOneOf(function.get("parameters"), "tools[$index]${if (responses) "" else ".function"}.parameters")
            require(path == null) { "OpenAI 严格工具 `${function.get("name")?.asString}` 不支持 oneOf：$path，请修改该工具的参数定义。" }
        }
    }

    /** 兼容端点若在响应中拒绝 Schema，把数组索引映射回当前请求中的工具。 */
    fun rejectedTool(body: String, tools: JsonArray?): String? {
        val error = runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonObject("error") }.getOrNull() ?: return null
        if (error.get("code")?.asString != "invalid_json_schema") return null
        val param = error.get("param")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val index = Regex("^tools\\[(\\d+)](?:\\.|$)").find(param)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val tool = tools?.takeIf { index in 0 until it.size() }?.get(index)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val function = tool.getAsJsonObject("function") ?: tool
        val name = function.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val path = firstOneOf(function.get("parameters"), param)
        return "工具 `$name` 的 Schema 被供应商拒绝：${path ?: param}"
    }

    private fun firstOneOf(schema: JsonElement?, path: String): String? {
        val obj = schema?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (obj.has("oneOf")) return "$path.oneOf"
        for (key in listOf("properties", "patternProperties", "\$defs", "definitions", "dependentSchemas", "dependencies")) {
            val children = obj.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            for ((name, child) in children.entrySet()) firstOneOf(child, "$path.$key[$name]")?.let { return it }
        }
        for (key in listOf("items", "prefixItems", "allOf", "anyOf", "not", "if", "then", "else", "contains", "additionalProperties", "additionalItems", "propertyNames", "unevaluatedProperties", "unevaluatedItems", "contentSchema")) {
            val child = obj.get(key) ?: continue
            if (child.isJsonArray) {
                child.asJsonArray.forEachIndexed { index, item -> firstOneOf(item, "$path.$key[$index]")?.let { return it } }
            } else firstOneOf(child, "$path.$key")?.let { return it }
        }
        return null
    }
}
