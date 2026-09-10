package com.lhstack.tools.agent.coding

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.encoding.EncodingProjectManager
import com.lhstack.tools.agent.PluginFunctionToolSupport
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.tools.PluginFunctionTool
import com.lhstack.tools.agent.model.tools.ResourceKind
import com.lhstack.tools.agent.model.tools.RuntimeTools
import com.lhstack.tools.agent.model.tools.SelectedShell
import com.lhstack.tools.agent.model.tools.ViewResourceTool
import com.lhstack.tools.db.config.AgentCapabilityConfig
import com.lhstack.tools.db.service.AgentRecord
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.CodingEnvironmentConfig
import com.lhstack.tools.db.service.CodingEnvironmentRecord
import com.lhstack.tools.db.service.CodingEnvironmentService
import com.lhstack.tools.db.service.ResourceConfigService
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn
import java.io.File
import java.nio.charset.Charset

/**
 * 编码会话运行时：按当前 Agent 能力包组装 preamble 与工具。
 * MCP 不进模型工具列表，由 cli 工具调用。会话 prompt_id 覆盖 Agent 默认提示词。
 */
object CodingRuntimeSupport {

    data class SkillSummary(
        val name: String,
        val description: String,
        val available: Boolean,
        val failReason: String?,
    )

    data class AssembledRuntime(
        val preamble: String,
        val tools: List<ToolDyn>,
        val enabledSkills: List<SkillSummary>,
        val project: Project?,
    )

    fun assemble(
        environment: CodingEnvironmentRecord,
        sessionId: Long,
        cwd: String,
        promptId: Long?,
        compactionSummary: String?,
        cancel: ModelCancel? = null,
        project: Project? = currentIdeProject(),
    ): AssembledRuntime {
        val skillsRoot = ResourceConfigService.skillsRootDir()
        val availableSkills = ResourceConfigService.listSkills()
        val enabledSkillNames = CodingEnvironmentService.enabledSkillNames(environment.config, availableSkills.map { it.name }.toSet())
        val enabledSkills = availableSkills
            .filter { it.name in enabledSkillNames }
            .map { SkillSummary(it.name, it.description?.trim().orEmpty().ifBlank { "无描述" }, it.available, it.failReason) }
        val preamble = CompactionTranscriptSupport.appendSummary(
            buildPreamble(environment, sessionId, cwd, promptId, skillsRoot, enabledSkillNames, availableSkills, project),
            compactionSummary,
        )
        val tools = RuntimeTools.create(
            workspace = cwd,
            enabledTools = CodingEnvironmentService.enabledTools(environment.config),
            enabledSkills = enabledSkillNames,
            skillsRootDir = skillsRoot,
            cancel = cancel,
            project = project,
            codingSession = true,
        ) + pluginFunctionTools(environment.config, project) + viewResourceTools(environment.config, cwd, skillsRoot, cancel)
        return AssembledRuntime(preamble, tools, enabledSkills.filter { it.available }, project)
    }

    fun runtimeSkills(environmentId: Long): List<SkillSummary> {
        val environment = CodingEnvironmentService.environmentById(environmentId) ?: return emptyList()
        val available = ResourceConfigService.listSkills()
        val enabled = CodingEnvironmentService.enabledSkillNames(environment.config, available.map { it.name }.toSet())
        return available
            .filter { it.name in enabled && it.available }
            .map { SkillSummary(it.name, it.description?.trim().orEmpty().ifBlank { "无描述" }, true, it.failReason) }
    }

    fun runtimeSkillsJson(environmentId: Long): JsonArray = JsonArray().apply {
        runtimeSkills(environmentId).forEach { skill ->
            add(JsonObject().apply {
                addProperty("name", skill.name)
                addProperty("description", skill.description)
                addProperty("available", skill.available)
            })
        }
    }

    fun projectForWorkspace(cwd: String): Project? {
        val canonical = File(cwd).canonicalFile.invariantSeparatorsPath
        return ProjectManager.getInstance().openProjects.firstOrNull { project ->
            val base = project.basePath ?: return@firstOrNull false
            File(base).canonicalFile.invariantSeparatorsPath == canonical
        }
    }

    fun currentIdeProject(): Project? {
        val opened = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        return opened.singleOrNull() ?: opened.firstOrNull()
    }

    internal fun effectiveTools(config: AgentCapabilityConfig): Set<String> = effectiveEnabledItems(
        current = RuntimeTools.REGISTERED_BUILTIN_TOOL_NAMES,
        enabled = config.tools.enabled,
        includeNew = config.tools.includeNew,
        disabled = config.tools.disabled,
    )

    internal fun effectiveSkills(
        config: AgentCapabilityConfig,
        availableSkills: List<ResourceConfigService.SkillDirectoryRecord>,
    ): Set<String> = effectiveEnabledItems(
        current = availableSkills.map { it.name }.toSet(),
        enabled = config.skills.enabled,
        includeNew = config.skills.includeNew,
        disabled = config.skills.disabled,
    )

    internal fun effectiveEnabledItems(
        current: Set<String>,
        enabled: Collection<String>,
        includeNew: Boolean,
        disabled: Collection<String>,
    ): Set<String> {
        val enabledSet = enabled.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        if (includeNew) {
            val disabledSet = disabled.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            enabledSet.addAll(current.filter { it !in disabledSet })
        }
        return enabledSet
    }

    internal fun enabledSkillSummaries(
        enabledSkills: Set<String>,
        availableSkills: List<ResourceConfigService.SkillDirectoryRecord>,
    ): List<String> = availableSkills
        .asSequence()
        .filter { it.name in enabledSkills && it.available }
        .map { skill ->
            val description = skill.description?.trim().takeUnless { it.isNullOrEmpty() } ?: "无描述"
            "- ${skill.name}: $description"
        }
        .toList()

    private fun buildPreamble(
        environment: CodingEnvironmentRecord,
        sessionId: Long,
        cwd: String,
        promptId: Long?,
        skillsRoot: File,
        enabledSkills: Set<String>,
        availableSkills: List<ResourceConfigService.SkillDirectoryRecord>,
        project: Project?,
    ): String = buildString {
        append("## 系统环境\n")
        append("- 编码环境 ID：${environment.id}\n")
        append("- 编码环境：${environment.name}\n")
        append("- 工作空间 / 默认CWD：${File(cwd).canonicalPath}\n")
        append("- 当前会话 ID：$sessionId\n")
        append("- CWD规则：工作空间就是默认 cwd；当工具调用或命令没有显式指定 cwd 时，cwd 等于工作空间。\n")
        append("- 技能目录：${skillsRoot.canonicalPath}\n")
        append("- 操作系统：${System.getProperty("os.name")} ${System.getProperty("os.version")}（${System.getProperty("os.arch")}）\n")
        append("- bash 工具系统编码：${SelectedShell.current().outputCharset.name()}\n")
        append("- 项目环境编码：${projectFileEncodingName(project)}\n")
        append("\n## 项目文件操作工具\n")
        append("- 涉及项目文件读写,查找相关的操作，你必须优先考虑使用 bash，并且项目编译你优先考虑使用build_project进行项目构建 ,其次再考虑使用 bash\n")
        append("- 项目文件/路径查找使用 `find_project_files`；类或语言插件支持的等价类型查找使用 `find_project_classes`；项目文本内容搜索使用 `search_project_text`。\n")
        append("- 已知项目文件路径后，读取使用 `read_project_files`；创建或完整覆盖使用 `write_project_files`；精确修改已有文本使用 `replace_project_text`。\n")
        append("- 需要 IDE 格式化时使用 `format_project_files`；需要当前 IDE Inspection Profile 检查文件时使用 `inspect_project_files`；需要当前 IDE 构建项目时使用 `build_project`。\n")
        append("- 这些项目工具只操作当前项目根目录内的文件。多个目标通过工具的数组参数一次提交。\n")
        appendSection("以下是编码环境系统提示词", environment.config.systemPrompt)
        val template = promptId?.let { CatalogService.promptTemplateById(it)?.preamble.orEmpty() }.orEmpty()
        appendSection("以下是会话提示词", template)
        appendEnvironmentContexts(environment.config)
        if (environment.config.skillPromptEnabled) {
            val skills = enabledSkillSummaries(enabledSkills, availableSkills)
            if (skills.isNotEmpty()) {
                appendSection(
                    "以下是当前编码环境的工具与技能使用说明。\n\n## 技能\n### 当前可用技能\n${skills.joinToString("\n")}\n\n### 使用约束",
                    "1. 下方“当前可用技能”是当前 Agent 提供给你的能力清单。判断用户意图时，直接根据这些技能名称和描述匹配。\n" +
                        "2. 如果用户意图已经命中上方清单中的技能，禁止调用 `skills_list` 再确认技能是否存在。\n" +
                        "3. 命中技能后，先判断当前对话或系统消息是否已经包含该技能的完整 `SKILL.md` 指令。\n" +
                        "4. 如果尚未包含完整指令，必须调用 `skills_view` 加载该技能；如果已经包含，禁止重复调用 `skills_view`。\n" +
                        "5. 加载或确认技能指令后，必须按照指令执行。",
                )
            }
        }
        appendSection(
            "以下是你必须严格遵守的边界约束，优先级高于以上所有内容。",
            environment.config.contexts["interaction"]?.content,
        )
    }

    private fun pluginFunctionTools(
        config: com.lhstack.tools.db.service.CodingEnvironmentConfig,
        project: Project?,
    ): List<ToolDyn> {
        val known = PluginFunctionToolSupport.entries(project)
        return known.filter { entry ->
            CodingEnvironmentService.pluginFunctionEnabled(config, entry.key, entry.functionName)
        }.map { entry -> PluginFunctionTool(entry.toolName, entry.function) }
    }

    private fun viewResourceTools(
        config: com.lhstack.tools.db.service.CodingEnvironmentConfig,
        cwd: String,
        skillsRoot: File,
        cancel: ModelCancel?,
    ) = buildList {
        addViewResourceTool(this, ResourceKind.IMAGE, config.viewResources.image, cwd, skillsRoot, cancel)
        addViewResourceTool(this, ResourceKind.AUDIO, config.viewResources.audio, cwd, skillsRoot, cancel)
        addViewResourceTool(this, ResourceKind.VIDEO, config.viewResources.video, cwd, skillsRoot, cancel)
        addViewResourceTool(this, ResourceKind.FILE, config.viewResources.file, cwd, skillsRoot, cancel)
    }

    private fun addViewResourceTool(
        tools: MutableList<ToolDyn>,
        kind: ResourceKind,
        ref: com.lhstack.tools.db.service.CodingViewResourceRef,
        cwd: String,
        skillsRoot: File,
        cancel: ModelCancel?,
    ) {
        val agentId = ref.agentId ?: return
        if (!ref.enabled) return
        tools += ViewResourceTool(
            kind = kind,
            resourceAgentId = agentId,
            workspace = cwd,
            skillsRootDir = skillsRoot,
            cancel = cancel,
        )
    }

    private fun projectFileEncodingName(project: Project?): String {
        if (project == null) return Charset.defaultCharset().name()
        return runCatching { EncodingProjectManager.getInstance(project).defaultCharsetName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Charset.defaultCharset().name()
    }

    private fun StringBuilder.appendEnvironmentContexts(config: CodingEnvironmentConfig) {
        val labels = mapOf(
            "user_profile" to "用户画像",
            "coding_style" to "编码风格",
            "workflow" to "工作流习惯",
            "interaction" to "交互偏好",
        )
        labels.forEach { (key, label) ->
            val item = config.contexts[key] ?: return@forEach
            val value = item.content.trim()
            if (value.isEmpty()) return@forEach
            append("\n[$label]\n")
            append(if (item.maxChars > 0) value.take(item.maxChars) else value)
            append('\n')
        }
    }

    private fun StringBuilder.appendPersonaSections(config: AgentCapabilityConfig) {
        appendPersona("以下是当前 Agent 的角色定义。", "角色设定", config.persona.soul, config.persona.soulMaxChars)
        appendPersona("", "能力画像", config.persona.profile, config.persona.profileMaxChars)
        appendPersona("以下是历史运行记录蒸馏得到的长期专业上下文。", "专业记忆", config.persona.memory, config.persona.memoryMaxChars)
        appendPersona("", "工作方法", config.persona.behaviorHabits, config.persona.behaviorHabitsMaxChars)
    }

    private fun StringBuilder.appendPersona(header: String, label: String, text: String, maxChars: Int) {
        val value = text.trim().takeIf { it.isNotEmpty() } ?: return
        if (header.isNotEmpty()) appendSection(header, "")
        append("\n[$label]\n")
        append(if (maxChars > 0) value.take(maxChars) else value)
        append('\n')
    }

    private fun StringBuilder.appendSection(header: String, text: String?) {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return
        append("\n\n---\n$header\n\n$value")
    }
}
