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
) : com.lhstack.tools.agent.model.llm.ToolDyn {

    override fun definition(prompt: String) = com.lhstack.tools.agent.model.llm.ToolDefinition(
        name = NAME,
        description = "List available skills from the configured skills directory. Each entry includes a `files` " +
            "field listing readable files under that skill. Use skills_view with `path` to read any listed file.",
        parameters = JsonParser.parseString(
            """
            {
                "type": "object",
                "properties": {
                    "max_results": {
                        "type": "integer",
                        "description": "Optional max skills to list. Default 50, hard limit 200."
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
) : com.lhstack.tools.agent.model.llm.ToolDyn {

    override fun definition(prompt: String) = com.lhstack.tools.agent.model.llm.ToolDefinition(
        name = NAME,
        description = "Read skill resource files. This is the ONLY tool for loading any content under a skill " +
            "directory. Always call skills_list first to discover available skills and their `files` array, then " +
            "call this tool with `name` and optional `path` to read a specific file. If path is omitted, reads the " +
            "skill's SKILL.md.",
        parameters = JsonParser.parseString(
            """
            {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Skill name from skills_list."
                    },
                    "path": {
                        "type": "string",
                        "description": "File path from the skill's `files` array (from skills_list). Omit to read SKILL.md."
                    },
                    "max_bytes": {
                        "type": "integer",
                        "description": "Optional max bytes to read. Default 200000, hard limit 1000000."
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
