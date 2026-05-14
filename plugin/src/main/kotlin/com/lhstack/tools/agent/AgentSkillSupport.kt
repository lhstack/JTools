package com.lhstack.tools.agent

import io.agentscope.core.skill.SkillBox
import io.agentscope.core.skill.util.SkillFileSystemHelper
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.coding.ShellCommandTool
import io.agentscope.core.tool.coding.UnixCommandValidator
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function
import java.util.UUID
import kotlin.io.path.isDirectory

object AgentSkillSupport {
    private const val skillFileName = "SKILL.md"

    fun normalizeSkills(skills: List<AgentSkillState>): MutableList<AgentSkillState> {
        return skills.mapNotNull { normalize(it) }
            .distinctBy { it.id }
            .toMutableList()
    }

    fun resolve(
        allSkills: List<AgentSkillState>,
        selectedIds: Collection<String>,
        toolkit: Toolkit,
    ): AgentResolvedSkills {
        val selectedIdSet = selectedIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (selectedIdSet.isEmpty()) {
            return AgentResolvedSkills(emptyList(), null)
        }
        val warnings = mutableListOf<String>()
        val selected = allSkills.mapNotNull { normalize(it) }
            .filter { state ->
                if (state.id in selectedIdSet) {
                    true
                } else {
                    false
                }
            }
        selectedIdSet.minus(selected.map { it.id }.toSet()).forEach { missingId ->
            warnings.add("技能不存在或已被删除: $missingId")
        }
        if (selected.isEmpty()) {
            return AgentResolvedSkills(emptyList(), null, warnings)
        }
        val skillBox = SkillBox(toolkit)
        selected.forEach { state ->
            runCatching { state.toSdkSkill() }
                .onSuccess { skill ->
                    skillBox.registration()
                        .skill(skill)
                        .apply()
                }
                .onFailure { error ->
                    warnings.add("${state.name.ifBlank { state.id }} 无法加载: ${error.message ?: "未知错误"}")
                }
        }
        enableCodeExecution(skillBox)
        return AgentResolvedSkills(
            selectedSkills = selected,
            skillBox = skillBox.takeIf { selected.isNotEmpty() },
            warnings = warnings
        )
    }

    fun importFromDirectory(directory: Path): AgentSkillImportResult {
        require(Files.exists(directory)) { "目录不存在: $directory" }
        require(directory.isDirectory()) { "导入路径不是目录: $directory" }
        return AgentSkillImportSupport.importFromPath(directory.toString(), emptyList())
    }

    fun createManualSkill(): AgentSkillState {
        return normalize(AgentSkillState().apply {
            id = UUID.randomUUID().toString()
            name = "新技能"
            description = "请填写技能说明"
            skillContent = "请填写技能内容"
            sourceType = AgentSkillSourceType.MANUAL.id
        })!!
    }

    fun cloneSkill(source: AgentSkillState): AgentSkillState {
        return normalize(AgentSkillState().apply {
            id = UUID.randomUUID().toString()
            name = "${source.name} Copy"
            description = source.description
            skillContent = source.skillContent
            sourceType = AgentSkillSourceType.MANUAL.id
            resources = source.resources.map {
                AgentSkillResourceState().apply {
                    path = it.path
                    content = it.content
                }
            }.toMutableList()
        })!!
    }

    internal fun normalizeSkill(state: AgentSkillState?): AgentSkillState? {
        state ?: return null
        if (state.id.isBlank()) {
            state.id = UUID.randomUUID().toString()
        }
        state.sourceType = AgentSkillSourceType.fromId(state.sourceType).id
        state.name = state.name.trim()
        state.description = state.description.trim()
        state.skillContent = state.skillContent.replace("\r\n", "\n")
        state.sourcePath = state.sourcePath.trim()
        state.resources = state.resources.mapNotNull { resource ->
            val path = resource.path.trim().replace('\\', '/')
            if (path.isBlank()) {
                return@mapNotNull null
            }
            AgentSkillResourceState().apply {
                this.path = path
                this.content = resource.content.replace("\r\n", "\n")
            }
        }.distinctBy { it.path }.toMutableList()
        return state
    }

    private fun normalize(state: AgentSkillState?): AgentSkillState? = normalizeSkill(state)

    private fun enableCodeExecution(skillBox: SkillBox) {
        val workDir = Files.createTempDirectory("jtools-agent-skill-code-").toAbsolutePath().normalize()
        val uploadDir = workDir.resolve("skills").normalize()
        SkillFileSystemHelper.registerTempDirectoryCleanup(workDir)
        val shellTool = ShellCommandTool(
            setOf("bash", "sh", "python", "python3", "node","java","javac","go", "bun", "ls", "pwd")
        )
        skillBox.codeExecution()
            .workDir(workDir.toString())
            .uploadDir(uploadDir.toString())
            .withShell(shellTool)
            .includeFolders(setOf("scripts/","data/"))
            .includeExtensions(setOf(".py", ".js",".sh",".json",".txt",".java",".go"))
            .withWrite()
            .withRead()
            .enable()
        skillBox.isAutoUploadSkill = false
        skillBox.uploadSkillFiles()
    }
}
