package com.lhstack.tools.agent

import io.agentscope.core.skill.SkillBox
import io.agentscope.core.skill.util.SkillFileSystemHelper
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.coding.ShellCommandTool
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory

object AgentSkillSupport {
    private val codeExecutionIncludeFolders = setOf("scripts/", "data/","assets/")
    private val codeExecutionIncludeExtensions = setOf(
        ".py",
        ".js",
        ".mjs",
        ".cjs",
        ".sh",
        ".json",
        ".txt",
        ".go",
        ".bat",
        ".cmd",
        ".ps1"
    )
    private val codeExecutionAllowedCommands = setOf(
        "bash",
        "sh",
        "python",
        "python3",
        "node",
        "nodejs",
        "go",
        "bun",
        "ls",
        "cat",
        "cmd",
        "powershell",
        "pwsh",
        "dir",
        "type"
    )
    private val codeExecutionInstruction = """
        
        ## Code Execution
        
        <code_execution>
        You have access to these code-execution tools:
        - execute_shell_command: run one shell command in the temporary skill workspace.
        - read_file: read files from the temporary skill workspace.
        - write_file: write files into the temporary skill workspace.
        
        Skills root directory: %s
        Each skill is uploaded under a subdirectory named by its <skill-id>.
        Uploaded skill resources are limited to these folders and file extensions:
        - folders: scripts/, data/
        - extensions: .py, .js, .mjs, .cjs, .sh, .json, .txt, .go, .bat, .cmd, .ps1
        
        Available shell command whitelist:
        - Unix/macOS/Linux: bash, sh, python, python3, node, nodejs, go, bun, ls, cat
        - Windows: cmd, powershell, pwsh, python, python3, node, nodejs, go, bun, dir, type
        
        Workflow:
        1. After loading a skill, inspect its uploaded directory before choosing a file.
        2. Prefer existing scripts over rewriting their logic inline.
        3. Use read_file for structured or text inputs when you need their content before execution.
        4. Execute with absolute paths under %s/<skill-id>/... whenever possible.
        5. If a command fails, diagnose from stdout/stderr and retry with an OS-appropriate command.
        
        Command selection by file type:
        - .py: use python3 on Unix/macOS/Linux, or python on Windows when python3 is unavailable.
          Example: python3 "%s/<skill-id>/scripts/task.py"
        - .js, .mjs, .cjs: use node or nodejs.
          Example: node "%s/<skill-id>/scripts/task.js"
        - .sh: use bash first, or sh if bash is unavailable.
          Example: bash "%s/<skill-id>/scripts/task.sh"
        - .go: use go run for source files.
          Example: go run "%s/<skill-id>/scripts/task.go"
        - .json, .txt: use read_file for content; use cat on Unix/macOS/Linux or type on Windows only for quick inspection.
          Unix example: cat "%s/<skill-id>/data/input.json"
          Windows cmd example: type "%s\<skill-id>\data\input.json"
        - .bat, .cmd: on Windows use cmd /c.
          Example: cmd /c "%s\<skill-id>\scripts\task.cmd"
        - .ps1: on Windows use powershell or pwsh with -NoProfile and -ExecutionPolicy Bypass.
          Example: powershell -NoProfile -ExecutionPolicy Bypass -File "%s\<skill-id>\scripts\task.ps1"
        
        Directory inspection examples:
        - Unix/macOS/Linux: ls "%s/<skill-id>/scripts/"
        - Windows cmd: dir "%s\<skill-id>\scripts"
        - Windows PowerShell: powershell -NoProfile -Command "Get-ChildItem -LiteralPath '%s\<skill-id>\scripts'"
        
        Rules:
        - Run the command yourself when a script can answer the user's request.
        - Quote absolute paths in shell commands so paths containing spaces still work.
        - Use a single command per execute_shell_command call; command separators such as &, |, ;, and newlines may be rejected.
        - Do not assume Unix-only commands on Windows. Use cmd, powershell, or pwsh examples for Windows paths and scripts.
        - Do not recreate uploaded data/assets when they already exist under the skill directory.
        </code_execution>
    """.trimIndent()

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
        val shellTool = ShellCommandTool(codeExecutionAllowedCommands)
        skillBox.codeExecution()
            .workDir(workDir.toString())
            .uploadDir(uploadDir.toString())
            .codeExecutionInstruction(codeExecutionInstruction)
            .withShell(shellTool)
            .includeFolders(codeExecutionIncludeFolders)
            .includeExtensions(codeExecutionIncludeExtensions)
            .withWrite()
            .withRead()
            .enable()
        skillBox.isAutoUploadSkill = false
        skillBox.uploadSkillFiles()
    }
}
