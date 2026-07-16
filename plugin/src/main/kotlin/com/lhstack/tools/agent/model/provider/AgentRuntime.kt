package com.lhstack.tools.agent.model.provider

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.encoding.EncodingProjectManager
import java.nio.charset.Charset
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.lhstack.tools.agent.PluginFunctionToolSupport
import com.lhstack.tools.db.config.AgentCapabilityConfig
import com.lhstack.tools.db.config.AgentDistillLogKind
import com.lhstack.tools.db.config.AgentRuntimeConfig
import com.lhstack.tools.db.service.AgentRecord
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.ResourceConfigService
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.llm.Message
import com.lhstack.tools.agent.model.llm.UserContent
import com.lhstack.tools.agent.model.llm.ToolDyn
import com.lhstack.tools.agent.model.log.ModelLogContext
import com.lhstack.tools.agent.model.params.ModelResolver
import com.lhstack.tools.agent.model.params.ModelParams
import com.lhstack.tools.agent.model.tools.PluginFunctionTool
import com.lhstack.tools.agent.model.tools.ResourceKind
import com.lhstack.tools.agent.model.tools.RuntimeTools
import com.lhstack.tools.agent.model.tools.ViewResourceTool
import java.io.File

/**
 * Agent 运行时。照抄 awake-claw agent_runtime.rs 的
 * execute_agent_prompt_with_attachments_and_tool_env 主路径。
 *
 * 当前插件宿主已经具备的输入：Agent、provider/model、prompt template、workspace、
 * builtin tools。聊天历史、附件和 send_files 依赖宿主会话层，继续由调用方传入，
 * 不在这里兼容缺失数据。
 */
object AgentRuntime {

    data class ExecutionResult(
        val logId: Long,
        val value: JsonObject,
        val output: String,
        val assistantMessageAt: String,
    )

    data class Request(
        val agentId: Long,
        val prompt: String,
        val triggerType: String = "direct_run",
        val triggerId: String? = null,
        val workspace: String,
        val sessionId: Long? = null,
        val skillsRootDir: File? = null,
        val history: List<Message> = emptyList(),
        /** 附件内容块，作为多模态用户内容拼进本轮 prompt 消息。 */
        val attachments: List<UserContent> = emptyList(),
        /** 附件回显快照，写入 model_log 的 request_snapshot.attachments，供会话历史还原。 */
        val attachmentSnapshots: List<JsonObject> = emptyList(),
        val extraTools: List<ToolDyn> = emptyList(),
        val toolEnvVars: Map<String, String> = emptyMap(),
        val streamSink: ModelStreamSink = ModelStreamSink.NOOP,
        val eventSink: ToolEventSink? = null,
        val cancel: ModelCancel? = null,
        val toolCancel: ModelCancel? = null,
        val clientMessageOrder: Long? = null,
        val logSourceType: String? = null,
        val logSourceId: String? = null,
        val logMessageType: String? = null,
        val logPromptMessage: String? = null,
        val project: Project? = null,
        val requestMetadata: JsonObject? = null,
        val userMessageAt: String? = null,
    )

    /** 照抄 execute_agent_prompt：按 Agent id 执行一次直接提示。 */
    fun execute(request: Request): ExecutionResult {
        val agent = AgentService.agentById(request.agentId)
            ?: throw IllegalArgumentException("Agent `${request.agentId}` 不存在")
        return execute(agent, request)
    }

    fun execute(agent: AgentRecord, request: Request): ExecutionResult {
        require(agent.id == request.agentId) { "Agent request id does not match agent record" }
        if (!agent.enabled) throw IllegalStateException("Agent `${agent.name}` 已停用")
        require(request.prompt.isNotBlank()) { "prompt must not be empty" }

        val providerId = agent.providerId ?: throw IllegalStateException("Agent 缺少供应商")
        val modelId = agent.modelId ?: throw IllegalStateException("Agent 缺少模型")
        val provider = CatalogService.providerById(providerId)
            ?: throw IllegalStateException("供应商 `$providerId` 不存在")
        val model = CatalogService.modelById(modelId)
            ?: throw IllegalStateException("模型 `$modelId` 不存在")
        require(model.providerId == providerId) { "模型 `$modelId` 不属于供应商 `$providerId`" }

        val runtimeModel = ModelResolver.resolveFromStore(provider, model)
        val enabledTools = effectiveTools(agent.extConfig)
        val enabledSkills = effectiveSkills(agent.extConfig)
        val tools = RuntimeTools.create(
            workspace = request.workspace,
            enabledTools = enabledTools,
            enabledSkills = enabledSkills,
            skillsRootDir = request.skillsRootDir,
            toolEnvVars = request.toolEnvVars,
            cancel = request.toolCancel ?: request.cancel,
            project = request.project,
        ) + pluginFunctionTools(agent.extConfig, request) + viewResourceTools(agent.extConfig, request) + request.extraTools

        ModelParams.validateContextBudget(
            runtimeModel.params.contextWindow,
            ModelParams.runtimeOutputTokens(runtimeModel),
        )
        val preamble = buildPreamble(agent, request, enabledSkills, request.skillsRootDir)
        val promptMessage = buildPromptMessage(request.prompt, request.attachments)
        val logContext = ModelLogContext(
            sourceType = request.logSourceType ?: sourceType(request.triggerType),
            sourceId = request.logSourceId ?: sourceId(request, agent.id ?: request.agentId),
            agentId = agent.id,
            messageType = request.logMessageType ?: AgentDistillLogKind.fromTrigger(request.triggerType).asStr(),
            userMessageAt = request.userMessageAt,
            requestSnapshot = JsonObject().apply {
                add("prompt_message", JsonPrimitive(request.logPromptMessage ?: request.prompt))
                request.clientMessageOrder?.let { addProperty("client_message_order", it) }
                add("history_params", JsonObject().apply {
                    addProperty("include_history", agent.runtimeParams.includeHistory)
                    agent.runtimeParams.maxHistoryMessages?.let { addProperty("max_history_messages", it) }
                    agent.runtimeParams.toolCallRetentionRounds?.let { addProperty("tool_call_retention_rounds", it) }
                })
                request.requestMetadata?.entrySet()?.forEach { (key, value) -> add(key, value.deepCopy()) }
                add("agent_preamble", JsonPrimitive(preamble))
                add("registered_tools", JsonArray().apply {
                    tools.map { it.definition("").name }.forEach { add(it) }
                })
                add("tool_policy", JsonObject().apply {
                    addProperty("tools_include_new", agent.extConfig.tools.includeNew)
                    add("tools_enabled", JsonArray().apply { agent.extConfig.tools.enabled.forEach { add(it) } })
                    addProperty("plugin_functions_include_new", agent.extConfig.pluginFunctions.includeNew)
                    add("plugin_functions_enabled", JsonArray().apply { agent.extConfig.pluginFunctions.enabled.forEach { add(it) } })
                    addProperty("skills_include_new", agent.extConfig.skills.includeNew)
                    add("skills_enabled", JsonArray().apply { agent.extConfig.skills.enabled.forEach { add(it) } })
                })
                if (request.attachmentSnapshots.isNotEmpty()) {
                    add("attachments", JsonArray().apply { request.attachmentSnapshots.forEach { add(it) } })
                }
            },
        )

        val result = ModelRuntime.execute(
            model = runtimeModel,
            agentMaxTurns = null,
            preamble = preamble,
            promptMessage = promptMessage,
            history = prepareHistory(agent, request, runtimeModel, preamble, promptMessage),
            tools = tools,
            logContext = logContext,
            environmentId = null,
            streamed = runtimeModel.stream,
            streamSink = request.streamSink,
            eventSink = request.eventSink,
            cancel = request.cancel,
            toolCancel = request.toolCancel,
        )
        val output = result.value.get("response")?.takeIf { it.isJsonPrimitive }?.asString
            ?: result.value.toString()
        return ExecutionResult(result.modelLogId, result.value, output, result.assistantMessageAt)
    }

    private fun effectiveHistory(agent: AgentRecord, request: Request): List<Message> {
        if (request.triggerType == "chat") return request.history
        return if (agent.runtimeParams.includeHistory) request.history else emptyList()
    }

    private fun prepareHistory(
        agent: AgentRecord,
        request: Request,
        model: com.lhstack.tools.agent.model.params.ResolvedModelConfig,
        preamble: String,
        promptMessage: Message,
    ): List<Message> = HistoryTrimmer.trim(
        history = effectiveHistory(agent, request),
        preamble = preamble,
        prompt = promptMessage.textForHistory(),
        contextWindow = model.params.contextWindow,
        maxTokens = com.lhstack.tools.agent.model.params.ModelParams.runtimeOutputTokens(model),
        maxHistoryTurns = agent.runtimeParams.maxHistoryMessages,
        retentionRounds = agent.runtimeParams.toolCallRetentionRounds,
    )

    private fun Message.textForHistory(): String = when (this) {
        is Message.System -> content
        is Message.User -> content.filterIsInstance<UserContent.Text>().joinToString(" ") { it.text }
        is Message.Assistant -> content.filterIsInstance<com.lhstack.tools.agent.model.llm.AssistantContent.Text>()
            .joinToString(" ") { it.text }
    }

    /** 文本 + 附件内容块组成本轮用户消息；无附件时即纯文本消息。 */
    private fun buildPromptMessage(prompt: String, attachments: List<UserContent>): Message {
        if (attachments.isEmpty()) return Message.user(prompt)
        val contents = buildList {
            add(UserContent.text(prompt))
            addAll(attachments)
        }
        return Message.User(contents)
    }


    private fun pluginFunctionTools(config: AgentCapabilityConfig, request: Request): List<ToolDyn> =
        PluginFunctionToolSupport.enabledEntries(
            project = request.project,
            enabled = config.pluginFunctions.enabled,
            includeNew = config.pluginFunctions.includeNew,
            disabled = config.pluginFunctions.disabled,
        ).map { entry -> PluginFunctionTool(entry.toolName, entry.function) }

    private fun viewResourceTools(config: AgentCapabilityConfig, request: Request): List<ToolDyn> = buildList {
        addViewResourceTool(ResourceKind.IMAGE, config.viewResources.image, request)
        addViewResourceTool(ResourceKind.AUDIO, config.viewResources.audio, request)
        addViewResourceTool(ResourceKind.VIDEO, config.viewResources.video, request)
        addViewResourceTool(ResourceKind.FILE, config.viewResources.file, request)
    }

    private fun MutableList<ToolDyn>.addViewResourceTool(
        kind: ResourceKind,
        ref: com.lhstack.tools.db.config.AgentViewResourceRef,
        request: Request,
    ) {
        val agentId = ref.agentId ?: return
        if (!ref.enabled) return
        add(
            ViewResourceTool(
                kind = kind,
                resourceAgentId = agentId,
                workspace = request.workspace,
                skillsRootDir = request.skillsRootDir,
                cancel = request.toolCancel ?: request.cancel,
            )
        )
    }

    private fun effectiveTools(config: AgentCapabilityConfig): Set<String> = effectiveEnabledItems(
        current = RuntimeTools.REGISTERED_BUILTIN_TOOL_NAMES,
        enabled = config.tools.enabled,
        includeNew = config.tools.includeNew,
        disabled = config.tools.disabled,
    )

    private fun effectiveSkills(config: AgentCapabilityConfig): Set<String> = effectiveEnabledItems(
        current = ResourceConfigService.listSkills().map { it.name }.toSet(),
        enabled = config.skills.enabled,
        includeNew = config.skills.includeNew,
        disabled = config.skills.disabled,
    )

    private fun effectiveEnabledItems(
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

    /** JetBrains 项目文件默认编码；无 project 时退回 JVM 默认编码。 */
    private fun projectFileEncodingName(request: Request): String {
        val project = request.project ?: return Charset.defaultCharset().name()
        return runCatching { EncodingProjectManager.getInstance(project).defaultCharsetName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Charset.defaultCharset().name()
    }

    private fun buildPreamble(
        agent: AgentRecord,
        request: Request,
        enabledSkills: Set<String>?,
        skillsRootDir: File?,
    ): String = buildString {
        append("## 系统环境\n")
        append("- Agent ID：${agent.id ?: request.agentId}\n")
        append("- Agent 名称：${agent.name}\n")
        append("- 工作空间 / 默认CWD：${File(request.workspace).canonicalPath}\n")
        request.sessionId?.let { append("- 当前会话 ID：$it\n") }
        append("- CWD规则：工作空间就是默认 cwd；当工具调用或命令没有显式指定 cwd 时，cwd 等于工作空间。\n")
        append("- 技能目录：${skillsRootDir?.canonicalPath ?: "未配置"}\n")
        append("- 操作系统：${System.getProperty("os.name")} ${System.getProperty("os.version")}（${System.getProperty("os.arch")}）\n")
        append("- bash 工具系统编码：${com.lhstack.tools.agent.model.tools.SelectedShell.current().outputCharset.name()}\n")
        append("- 项目环境编码：${projectFileEncodingName(request)}\n")

        append("\n## 项目文件操作工具\n")
        append("- 项目文件名查找使用 `find_project_files`；项目文本内容搜索使用 `search_project_text`。\n")
        append("- 已知项目文件路径后，读取使用 `read_project_files`；创建或完整覆盖使用 `write_project_files`；精确修改已有文本使用 `replace_project_text`。\n")
        append("- 需要 IDE 格式化时使用 `format_project_files`；需要当前 IDE Inspection Profile 检查文件时使用 `inspect_project_files`；需要当前 IDE 构建项目时使用 `build_project`。\n")
        append("- 这些项目工具只操作当前项目根目录内的文件。多个目标通过工具的数组参数一次提交。\n")

        val template = agent.promptId?.let { CatalogService.promptTemplateById(it)?.preamble.orEmpty() }.orEmpty()
        appendSection("以下是系统提示词", template)
        appendPersonaSections(agent.extConfig)
        appendSection("以下是扩展提示信息。", agent.extraPrompt)
        if (agent.extConfig.skillPromptEnabled && !enabledSkills.isNullOrEmpty()) {
            val skills = enabledSkills.joinToString("\n") { "- $it" }
            appendSection(
                "以下是工具与技能使用说明。\n\n## 技能\n### 已启用技能\n$skills\n\n### 使用约束",
                "1. 命中技能后先确认当前上下文是否已有完整 SKILL.md。\n" +
                    "2. 未加载时调用 skills_view，加载后必须按技能指引执行。",
            )
        }
        appendSection(
            "以下是你必须严格遵守的边界约束，优先级高于以上所有内容。",
            agent.extConfig.persona.guardrails,
        )
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

    private fun sourceType(triggerType: String): String = when (triggerType) {
        "workflow" -> "workflow"
        "schedule" -> "schedule"
        "environment_distillation" -> "environment_distillation"
        "distillation" -> "agent_distillation"
        "agent_run" -> "agent_run"
        else -> "agent"
    }

    private fun sourceId(request: Request, agentId: Long): String? =
        if (request.triggerType in setOf("workflow", "schedule", "environment_distillation", "distillation", "agent_run")) {
            request.triggerId
        } else {
            "$agentId:${request.triggerId ?: "direct"}"
        }
}
