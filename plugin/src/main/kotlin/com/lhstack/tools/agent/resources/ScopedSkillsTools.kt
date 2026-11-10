package com.lhstack.tools.agent.resources

import com.google.gson.*
import com.lhstack.tools.agent.model.tools.*
import com.lhstack.tools.llm.*
import java.io.File

/** Runtime source catalog shared by listing, reading and prompt assembly. */
data class SkillSource(val scope: String, val root: File, val enabled: Set<String>)

class ScopedSkillsTool(
    private val view: Boolean,
    private val workspace: WorkspaceTools,
    private val sources: List<SkillSource>,
    private val sourceLookup: (() -> List<SkillSource>)? = null,
) : ToolDyn {
    override fun definition(prompt: String) =
        ToolDefinition(
            name = if (view) "skills_view" else "skills_list",
            description =
                if (view) "读取当前有效技能。scope 必须为 global/project，name 为清单中的名称。"
                else "列出当前环境及项目可用技能，结果携带 scope。可选 scope 过滤。",
            parameters =
                JsonParser.parseString(
                    if (view)
                        """{"type":"object","properties":{"scope":{"type":"string","enum":["global","project"]},"name":{"type":"string"},"path":{"type":"string"},"max_bytes":{"type":"integer"}},"required":["scope","name"]}"""
                    else
                        """{"type":"object","properties":{"scope":{"type":"string","enum":["global","project"]},"max_results":{"type":"integer"}}}"""
                ),
        )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        require(args.isJsonObject) { "技能参数必须是 object" }
        val obj = args.asJsonObject
        val sources = sourceLookup?.invoke() ?: sources
        val scope = obj.get("scope")?.asString
        require(scope == null || scope in setOf("global", "project")) { "scope 无效" }
        if (view) {
            require(scope != null) { "缺少 scope" }
            val source = sources.singleOrNull { it.scope == scope } ?: error("当前 scope 没有授权技能")
            return SkillsViewTool(workspace, source.root, source.enabled)
                .callJsonBlocking(args)
                .asJsonObject
                .apply { addProperty("scope", scope) }
        }
        val result = JsonArray()
        var truncated = false
        val limit = SkillsSupport.clampLimit(obj.get("max_results")?.asInt, 50, 200)
        sources
            .filter { scope == null || it.scope == scope }
            .forEach { source ->
                if (source.root.isDirectory) {
                    val page =
                        SkillsListTool(workspace, source.root, source.enabled)
                            .callJsonBlocking(args)
                            .asJsonObject
                    page.getAsJsonArray("skills").forEach { item ->
                        if (result.size() < limit)
                            result.add(
                                item.asJsonObject.apply { addProperty("scope", source.scope) }
                            )
                        else truncated = true
                    }
                    truncated = truncated || page.get("truncated").asBoolean
                }
            }
        return JsonObject().apply {
            add("skills", result)
            addProperty("truncated", truncated)
        }
    }
}
