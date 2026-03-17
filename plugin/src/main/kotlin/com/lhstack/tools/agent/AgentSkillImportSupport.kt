package com.lhstack.tools.agent

import io.agentscope.core.skill.util.MarkdownSkillParser
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object AgentSkillImportSupport {
    private const val skillFileName = "SKILL.md"

    fun importFromPath(path: String, existingSkills: List<AgentSkillState>): AgentSkillImportResult {
        val normalized = path.trim()
        require(normalized.isNotBlank()) { "path 不能为空" }
        val target = Paths.get(normalized)
        require(Files.exists(target)) { "路径不存在: $normalized" }
        val skillFiles = when {
            Files.isRegularFile(target) -> {
                require(target.fileName.toString().equals(skillFileName, ignoreCase = true)) { "文件必须是 SKILL.md" }
                listOf(target)
            }
            target.isDirectory() -> Files.walk(target).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().equals(skillFileName, ignoreCase = true) }
                    .sorted()
                    .toList()
            }
            else -> emptyList()
        }
        if (skillFiles.isEmpty()) {
            return AgentSkillImportResult(emptyList(), warnings = listOf("未在所选路径中发现 SKILL.md"))
        }
        val existingNames = existingSkills.map { it.name.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        val imported = mutableListOf<AgentSkillState>()
        val skipped = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        skillFiles.forEach { skillFile ->
            val root = skillFile.parent ?: return@forEach
            runCatching { loadFromSkillDirectory(root) }
                .onSuccess { skill ->
                    if (!existingNames.add(skill.name)) {
                        skipped.add("${skill.name}: 同名技能已存在，已跳过")
                    } else {
                        imported.add(skill)
                    }
                }
                .onFailure { error ->
                    warnings.add("${root.fileName}: ${error.message ?: "导入失败"}")
                }
        }
        return AgentSkillImportResult(imported, skipped, warnings)
    }

    internal fun loadFromSkillDirectory(skillRoot: Path): AgentSkillState {
        val skillFile = skillRoot.resolve(skillFileName)
        val markdown = Files.readString(skillFile, StandardCharsets.UTF_8)
        val parsed = MarkdownSkillParser.parse(markdown)
        val metadata = parsed.metadata
        val name = metadata["name"]?.trim().orEmpty()
        val description = metadata["description"]?.trim().orEmpty()
        require(name.isNotBlank()) { "SKILL.md 缺少 name" }
        require(description.isNotBlank()) { "SKILL.md 缺少 description" }
        val resources = Files.walk(skillRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it != skillFile }
                .sorted()
                .toList()
                .mapNotNull { file ->
                    val relativePath = skillRoot.relativize(file).toString().replace('\\', '/')
                    val content = tryReadText(file) ?: return@mapNotNull null
                    AgentSkillResourceState().apply {
                        path = relativePath
                        this.content = content
                    }
                }
                .toMutableList()
        }
        return AgentSkillSupport.normalizeSkill(AgentSkillState().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            this.description = description
            this.skillContent = parsed.content
            this.sourceType = AgentSkillSourceType.LOCAL_IMPORT.id
            this.sourcePath = skillRoot.toAbsolutePath().normalize().toString()
            this.resources = resources
        })!!
    }

    private fun tryReadText(file: Path): String? {
        return try {
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (_: MalformedInputException) {
            null
        }
    }
}
