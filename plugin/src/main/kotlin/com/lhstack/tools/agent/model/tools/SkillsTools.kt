package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * skills 工具。完全照抄 awake-claw tools.rs 的 SkillsListTool / SkillsViewTool 及其 helper。
 *
 * skills_list：递归扫描 skills 根目录，列出含 SKILL.md 且当前环境可用的技能，
 *   带 description / supportPlatformTypes / checkScripts / files。
 * skills_view：读取某技能下的资源文件（默认 SKILL.md），做技能名 / 路径 / 可用性 / 越权校验。
 *
 * rootDir 为 skills 根目录（可空，未配置时报错）；enabledSkills 为 null 表示不限制，
 * 非 null 时只允许集合内的技能（对齐 skill_enabled）。
 */

/** skills_list 工具。 */
class SkillsListTool(
    private val workspace: WorkspaceTools,
    private val rootDir: File?,
    private val enabledSkills: Set<String>?,
) : com.lhstack.tools.llm.ToolDyn {

    override fun definition(prompt: String) = com.lhstack.tools.llm.ToolDefinition(
        name = NAME,
        description = "列出可用技能及其可读文件；读取内容请使用 skills_view。",
        parameters = JsonParser.parseString(
            """
            {
                "type": "object",
                "properties": {
                    "max_results": {
                        "type": "integer",
                        "description": "可选。最多返回技能数，默认50，最大200。"
                    }
                },
                "required": []
            }
            """.trimIndent()
        ),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val root = SkillsSupport.configuredSkillsRoot(rootDir)
        val maxResults = SkillsSupport.clampLimit(
            args.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("max_results")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt,
            50, 200,
        )
        val skills = mutableListOf<JsonObject>()
        SkillsSupport.collectSkillSummaries(root, root, workspace, enabledSkills, maxResults, skills)
        skills.sortBy { it.get("name").asString }
        val truncated = skills.size >= maxResults

        return JsonObject().apply {
            addProperty("root_dir", root.path.replace('\\', '/'))
            add("skills", JsonArray().apply { skills.forEach { add(it) } })
            addProperty("truncated", truncated)
        }
    }

    companion object {
        const val NAME = "skills_list"
    }
}

/** skills_view 工具。 */
class SkillsViewTool(
    private val workspace: WorkspaceTools,
    private val rootDir: File?,
    private val enabledSkills: Set<String>?,
) : com.lhstack.tools.llm.ToolDyn {

    override fun definition(prompt: String) = com.lhstack.tools.llm.ToolDefinition(
        name = NAME,
        description = "读取技能文件。必须先调用 skills_list；省略 path 时读取 SKILL.md。",
        parameters = JsonParser.parseString(
            """
            {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "必填。skills_list 返回的技能名称。"
                    },
                    "path": {
                        "type": "string",
                        "description": "可选。skills_list 返回的文件路径；省略时读取 SKILL.md。"
                    },
                    "max_bytes": {
                        "type": "integer",
                        "description": "可选。最多读取字节数，默认200000，最大1000000。"
                    }
                },
                "required": ["name"]
            }
            """.trimIndent()
        ),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val obj = args.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw ToolException.invalidSkillName("")
        SkillsSupport.validateSkillName(name)
        if (!SkillsSupport.skillEnabled(enabledSkills, name)) {
            throw ToolException.skillDisabled(name)
        }
        val root = SkillsSupport.configuredSkillsRoot(rootDir)
        val skillDirRaw = root.resolve(
            WorkspaceTools.normalizeRelativeSegments(name) ?: throw ToolException.invalidSkillName(name)
        )
        val skillDir = try {
            WorkspaceTools.canonicalize(skillDirRaw)
        } catch (_: Throwable) {
            throw ToolException.skillNotFound(name)
        }
        SkillsSupport.ensurePathInsideRoot(skillDir, root)
        if (!skillDir.isDirectory || !skillDir.resolve("SKILL.md").isFile) {
            throw ToolException.skillNotFound(name)
        }
        SkillsSupport.ensureSkillAvailable(name, skillDir)

        val resource = obj.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: "SKILL.md"
        val resourcePath = SkillsSupport.joinSkillResourcePath(skillDir, resource)
        if (!resourcePath.exists()) {
            throw ToolException.resourceNotFound(resource)
        }
        if (!resourcePath.isFile) {
            throw ToolException.resourceNotFile(resource)
        }

        val maxBytes = SkillsSupport.clampLimit(
            obj.get("max_bytes")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt,
            200_000, 1_000_000,
        )
        val bytes = resourcePath.readBytes()
        val truncated = bytes.size > maxBytes
        val contentBytes = if (truncated) bytes.copyOfRange(0, maxBytes) else bytes

        return JsonObject().apply {
            addProperty("name", name)
            addProperty("path", SkillsSupport.workspaceRelativePath(workspace, resourcePath))
            addProperty("bytes_read", contentBytes.size)
            addProperty("truncated", truncated)
            addProperty("content", contentBytes.toString(Charsets.UTF_8))
        }
    }

    companion object {
        const val NAME = "skills_view"
    }
}
