package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.File

/**
 * skills 工具 helper。完全照抄 awake-claw tools.rs 里 skills 相关的自由函数：
 * configured_skills_root / skill_enabled / validate_skill_name / normalize_skill_relative_path /
 * join_skill_resource_path / ensure_skill_available / collect_skill_summaries /
 * ensure_path_inside_root / list_skill_files_relative / workspace_relative_path / clamp_limit。
 *
 * 可用性检测委托 SkillMetadataSupport（照抄 skill_metadata.rs）。
 */
object SkillsSupport {

    /** 照抄 configured_skills_root：未配置或非目录报错。 */
    fun configuredSkillsRoot(rootDir: File?): File {
        val root = rootDir ?: throw ToolException.skillsNotConfigured()
        if (!root.isDirectory) {
            throw ToolException.rootNotDirectory(root.path)
        }
        return root
    }

    /** 照抄 skill_enabled：null 表示不限制。 */
    fun skillEnabled(enabledSkills: Set<String>?, name: String): Boolean =
        enabledSkills?.contains(name) ?: true

    /** 照抄 validate_skill_name。 */
    fun validateSkillName(name: String) {
        if (WorkspaceTools.normalizeRelativeSegments(name) == null) {
            throw ToolException.invalidSkillName(name)
        }
    }

    /** 照抄 clamp_limit：默认值 + [1, max] 夹取。 */
    fun clampLimit(value: Int?, default: Int, max: Int): Int =
        (value ?: default).coerceIn(1, max)

    /** 照抄 join_skill_resource_path：拒绝越权，返回 canonical 路径。 */
    fun joinSkillResourcePath(root: File, path: String): File {
        if (path.isBlank()) {
            throw ToolException.invalidResourcePath(path)
        }
        val normalized = WorkspaceTools.normalizeRelativeSegments(path)
            ?: throw ToolException.invalidResourcePath(path)
        val joined = root.resolve(normalized)
        val canonical = if (joined.exists()) WorkspaceTools.canonicalize(joined) else joined
        if (canonical.exists()) {
            ensurePathInsideRoot(canonical, root)
        }
        return canonical
    }

    /** 照抄 ensure_path_inside_root。 */
    fun ensurePathInsideRoot(path: File, root: File) {
        val canonicalRoot = WorkspaceTools.canonicalize(root)
        if (!path.toPath().startsWith(canonicalRoot.toPath())) {
            throw ToolException.invalidResourcePath(path.path)
        }
    }

    /** 照抄 ensure_skill_available：读 SKILL.md 元数据做可用性检测。 */
    fun ensureSkillAvailable(name: String, skillDir: File) {
        val content = skillDir.resolve("SKILL.md").readText(Charsets.UTF_8)
        val metadata = SkillMetadataSupport.skillMetadata(content)
        val availability = SkillMetadataSupport.skillAvailability(skillDir, metadata)
        if (availability.available) {
            return
        }
        throw ToolException.skillUnavailable(name, availability.failReason ?: "可用状态检测失败")
    }

    /** 照抄 workspace_relative_path。 */
    fun workspaceRelativePath(workspace: WorkspaceTools, path: File): String =
        workspace.displayPath(path)

    /**
     * 照抄 collect_skill_summaries：递归扫描含 SKILL.md 的技能目录，
     * 过滤禁用/不可用技能，收集到 max_results。
     */
    fun collectSkillSummaries(
        root: File,
        current: File,
        workspace: WorkspaceTools,
        enabledSkills: Set<String>?,
        maxResults: Int,
        skills: MutableList<JsonObject>,
    ) {
        if (skills.size >= maxResults) {
            return
        }
        val entries = current.listFiles() ?: return
        for (path in entries) {
            if (!path.isDirectory) {
                continue
            }
            if (path.resolve("SKILL.md").isFile) {
                val name = relativeSkillName(root, path)
                if (skillEnabled(enabledSkills, name)) {
                    val content = path.resolve("SKILL.md").readText(Charsets.UTF_8)
                    val metadata = SkillMetadataSupport.skillMetadata(content)
                    val availability = SkillMetadataSupport.skillAvailability(path, metadata)
                    if (availability.available) {
                        skills.add(JsonObject().apply {
                            addProperty("name", name)
                            addProperty("path", workspaceRelativePath(workspace, path))
                            addProperty("has_skill_md", true)
                            add("description", metadata.description?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                            add("support_platform_types", JsonArray().apply {
                                metadata.supportPlatformTypes.forEach { add(it) }
                            })
                            add("check_scripts", JsonArray().apply {
                                metadata.checkScripts.forEach { add(it) }
                            })
                            add("files", JsonArray().apply {
                                listSkillFilesRelative(path, path).forEach { add(it) }
                            })
                        })
                    }
                }
            }
            if (skills.size >= maxResults) {
                break
            }
            collectSkillSummaries(root, path, workspace, enabledSkills, maxResults, skills)
        }
    }

    /** 照抄技能名推导：相对 root 的路径段以 / 连接。 */
    private fun relativeSkillName(root: File, path: File): String {
        val relative = root.toPath().relativize(path.toPath())
        return relative.mapNotNull { it.toString().takeIf { name -> name.isNotEmpty() } }
            .joinToString("/")
    }

    /** 照抄 list_skill_files_relative：递归列出非隐藏文件的相对路径，排序。 */
    fun listSkillFilesRelative(root: File, current: File): List<String> {
        val files = mutableListOf<String>()
        val entries = current.listFiles() ?: return files
        for (path in entries) {
            val fileName = path.name
            if (fileName.startsWith(".")) {
                continue
            }
            val relative = root.toPath().relativize(path.toPath()).toString().replace('\\', '/')
            if (relative.isEmpty()) {
                continue
            }
            if (path.isDirectory) {
                files.addAll(listSkillFilesRelative(root, path))
            } else {
                files.add(relative)
            }
        }
        files.sort()
        return files
    }
}
