package com.lhstack.tools.agent.coding

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.PluginFunctionToolSupport
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.log.ModelLogContext
import com.lhstack.tools.agent.model.log.ModelRequestException
import com.lhstack.tools.agent.model.params.ModelResolver
import com.lhstack.tools.agent.model.provider.AgentRuntime
import com.lhstack.tools.agent.model.tools.PluginFunctionTool
import com.lhstack.tools.agent.model.tools.RuntimeTools
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService
import com.lhstack.tools.db.service.ChatSessionService
import com.lhstack.tools.db.service.CodingEnvironmentConfig
import com.lhstack.tools.db.service.CodingEnvironmentService
import com.lhstack.tools.db.service.CodingSubagentEntry
import com.lhstack.tools.db.service.ResourceConfigService
import com.lhstack.tools.llm.Message
import com.lhstack.tools.llm.ToolDefinition
import com.lhstack.tools.llm.ToolDyn
import com.lhstack.tools.llm.Usage
import com.lhstack.tools.llm.provider.ModelRuntime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object CodingSubagentTools {
    fun create(
        environment: CodingEnvironmentConfig,
        sessionId: Long,
        turnId: String,
        cwd: String,
        toolCancelSlot: AtomicReference<ModelCancel>?,
        broadcaster: (JsonObject) -> Unit,
        project: com.intellij.openapi.project.Project?,
    ): List<ToolDyn> {
        if (!environment.subagents.enabled || environment.subagents.agents.isEmpty()) return emptyList()
        val entries = environment.subagents.agents.filter { (it.agentId ?: 0) > 0 }
        if (entries.isEmpty()) return emptyList()
        return listOf(
            SubagentsListTool(entries),
            SubagentRunTool(
                entries = entries,
                sessionId = sessionId,
                turnId = turnId,
                cwd = cwd,
                toolCancelSlot = toolCancelSlot,
                broadcaster = broadcaster,
                project = project,
            ),
        )
    }
}

private class SubagentsListTool(
    private val entries: List<CodingSubagentEntry>,
) : ToolDyn {
    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = "subagents_list",
        description = "列出当前编码环境允许调用的 SubAgent。",
        parameters = JsonParser.parseString("""{"type":"object","properties":{},"required":[]}"""),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val agents = JsonArray()
        entries.forEach { entry ->
            val agentId = entry.agentId ?: return@forEach
            val agent = AgentService.agentById(agentId) ?: return@forEach
            agents.add(JsonObject().apply {
                addProperty("id", agentId)
                addProperty("name", agent.name)
                addProperty("description", agent.description ?: "")
                addProperty("access_mode", entry.accessMode)
            })
        }
        return JsonObject().apply { add("agents", agents) }
    }
}

private class SubagentRunTool(
    private val entries: List<CodingSubagentEntry>,
    private val sessionId: Long,
    private val turnId: String,
    private val cwd: String,
    private val toolCancelSlot: AtomicReference<ModelCancel>?,
    private val broadcaster: (JsonObject) -> Unit,
    private val project: com.intellij.openapi.project.Project?,
) : ToolDyn {
    override val executionTimeoutSeconds: Long = 1800L

    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = "subagent_run",
        description = "运行当前编码环境允许的 SubAgent 执行隔离子任务。SubAgent 继承当前工作区，不能再调用其他 SubAgent。",
        parameters = JsonParser.parseString(
            """
            {
              "type": "object",
              "properties": {
                "agent_id": {"type": "integer", "description": "允许的 SubAgent id"},
                "title": {"type": "string", "description": "本次运行的短标题，显示在时间线中"},
                "task": {"type": "string", "description": "交给 SubAgent 的完整任务"},
                "context": {"type": "string", "description": "可选补充上下文"}
              },
              "required": ["agent_id", "title", "task"]
            }
            """.trimIndent(),
        ),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val obj = args.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("SubAgent 参数必须是 JSON object")
        val agentId = obj.get("agent_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            ?: throw IllegalArgumentException("SubAgent agent_id 无效")
        val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val task = obj.get("task")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val context = obj.get("context")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        require(title.isNotEmpty()) { "SubAgent title 不能为空" }
        require(task.isNotEmpty()) { "SubAgent task 不能为空" }
        val entry = entries.firstOrNull { it.agentId == agentId }
            ?: throw IllegalStateException("当前编码环境未授权该 SubAgent")
        return runSubagent(entry, agentId, title, task, context)
    }

    private fun runSubagent(
        entry: CodingSubagentEntry,
        agentId: Long,
        title: String,
        task: String,
        context: String?,
    ): JsonElement {
        val agent = AgentService.agentById(agentId) ?: throw IllegalStateException("Agent `$agentId` 不存在")
        val providerId = agent.providerId ?: throw IllegalStateException("SubAgent 缺少供应商")
        val modelId = agent.modelId ?: throw IllegalStateException("SubAgent 缺少模型")
        val provider = CatalogService.providerById(providerId)
            ?: throw IllegalStateException("供应商 `$providerId` 不存在")
        val modelEntity = CatalogService.modelById(modelId)
            ?: throw IllegalStateException("模型 `$modelId` 不存在")
        val model = ModelResolver.resolveFromStore(provider, modelEntity)
        val skillsRoot = ResourceConfigService.skillsRootDir()
        val availableSkills = ResourceConfigService.listSkills()
        val enabledSkills = AgentRuntime.enabledSkills(agent.extConfig, availableSkills)
        val enabledTools = restrictedTools(AgentRuntime.enabledTools(agent.extConfig), entry)
        val cancel = ModelCancel(toolCancelSlot ?: AtomicReference())
        val session = ChatSessionService.sessionById(sessionId)
        val sessionName = session?.title?.takeIf { it.isNotBlank() } ?: "coding-$sessionId"
        val sessionKey = sessionId.toString()
        val tools = RuntimeTools.create(
            workspace = cwd,
            enabledTools = enabledTools,
            enabledSkills = enabledSkills,
            skillsRootDir = skillsRoot,
            cancel = cancel,
            project = project,
            codingSession = true,
        ) + PluginFunctionToolSupport.enabledEntries(
            enabled = agent.extConfig.pluginFunctions.enabled,
            includeNew = agent.extConfig.pluginFunctions.includeNew,
            disabled = agent.extConfig.pluginFunctions.disabled,
            project = project,
            sessionName = sessionName,
            sessionId = sessionKey,
        ).map { entry ->
            PluginFunctionTool(
                toolName = entry.toolName,
                function = entry.function,
                sessionName = sessionName,
                sessionId = sessionKey,
                provider = provider.name,
                model = modelEntity.displayName?.takeIf { it.isNotBlank() } ?: modelEntity.modelId,
            )
        }
        val preamble = buildString {
            append("## Coding SubAgent\n")
            append("- Agent：${agent.name}\n")
            append("- 工作空间 / 默认 CWD：$cwd\n")
            append("- 访问模式：${entry.accessMode}\n")
            append("- 你是主 Coding Agent 调用的一次性子任务执行者，不加载其他项目历史，不能调用其他 SubAgent。\n\n")
            val extra = agent.extraPrompt?.trim().orEmpty()
            if (extra.isNotEmpty()) append(extra).append("\n")
        }
        val prompt = if (context.isNullOrBlank()) "任务：$task" else "任务：$task\n\n补充上下文：$context"
        val runId = "coding_subagent_${UUID.randomUUID().toString().replace("-", "")}"
        val recorder = UnifiedMessageEventRecorder.startSubagentEvent(
            sessionId = sessionId,
            turnId = turnId,
            runId = runId,
            agentId = agentId,
            agentName = agent.name,
            title = title,
            task = task,
            broadcaster = broadcaster,
        )
        val subagentModelCancel = ModelCancel()
        val subagentToolSlot = AtomicReference<ModelCancel>()
        val cancelRequested = AtomicBoolean(false)
        val parentCancel = toolCancelSlot?.get()
        val interruptId = parentCancel?.registerInterrupt {
            cancelRequested.set(true)
            subagentToolSlot.get()?.cancel()
            subagentModelCancel.cancel()
        }
        return try {
            val result = ModelRuntime.execute(
                model = model,
                agentMaxTurns = null,
                preamble = preamble,
                promptMessage = Message.user(prompt),
                history = emptyList(),
                tools = tools,
                logContext = ModelLogContext(
                    sourceType = "coding_subagent",
                    sourceId = "$turnId:$agentId",
                    agentId = agentId,
                    messageType = "coding_subagent",
                    requestSnapshot = JsonObject().apply {
                        addProperty("parent_turn_id", turnId)
                        addProperty("cwd", cwd)
                        addProperty("access_mode", entry.accessMode)
                    },
                ),
                environmentId = null,
                streamed = model.stream,
                eventSink = recorder.streamSink(turnId),
                cancel = subagentModelCancel,
                toolCancel = ModelCancel(subagentToolSlot),
                toolCancelSlot = subagentToolSlot,
            )
            val payload = subagentResult(runId, agentId, agent.name, title, task, "completed", result.modelLogId, result.value, null)
            recorder.recordTurnUsage(turnId, result.value.get("usage") ?: JsonObject())
            recorder.completeTurn(turnId)
            recorder.finishSubagentEvent(turnId, MessageEventStatus.COMPLETED, payload)
            payload
        } catch (error: ModelRequestException) {
            val cancelled = cancelRequested.get() || subagentModelCancel.isCancelled()
            val status = if (cancelled) "cancelled" else "failed"
            val eventStatus = if (cancelled) MessageEventStatus.CANCELLED else MessageEventStatus.FAILED
            if (cancelled) recorder.recordCancellation(turnId)
            else recorder.recordFailure(turnId, error.message ?: error.toString())
            val payload = subagentResult(
                runId, agentId, agent.name, title, task, status, error.logId,
                error.partialResponse ?: JsonObject(),
                if (cancelled) null else (error.message ?: error.toString()),
            )
            recorder.finishSubagentEvent(turnId, eventStatus, payload, if (cancelled) null else error.message)
            if (cancelled) payload else throw error
        } finally {
            interruptId?.let { parentCancel?.clearInterrupt(it) }
        }
    }

    private fun restrictedTools(agentTools: Set<String>, entry: CodingSubagentEntry): Set<String> {
        val enabled = agentTools.toMutableSet()
        when (entry.accessMode) {
            "read_only" -> {
                enabled.removeAll(setOf("write_project_files", "replace_project_text", "bash", "build_project", "format_project_files"))
            }
            "workspace_write" -> {
                if (!entry.bashEnabled) enabled.remove("bash")
            }
            else -> throw IllegalStateException("SubAgent access_mode 无效")
        }
        return enabled
    }

    private fun subagentResult(
        runId: String,
        agentId: Long,
        agentName: String,
        title: String,
        task: String,
        status: String,
        modelLogId: Long?,
        response: JsonObject,
        error: String?,
    ): JsonObject {
        val timeline = response.get("event_timeline")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        response.get("usage")?.takeIf { it.isJsonObject && it.asJsonObject.size() > 0 }?.let { usage ->
            timeline.add(JsonObject().apply {
                addProperty("type", "token_usage")
                add("usage", Usage.withoutAttribution(usage))
                addProperty("occurred_at", java.time.Instant.now().toString())
            })
        }
        return JsonObject().apply {
            add("agent", JsonObject().apply {
                addProperty("id", agentId)
                addProperty("name", agentName)
            })
            addProperty("run_id", runId)
            addProperty("title", title)
            addProperty("task", task)
            addProperty("status", status)
            if (modelLogId != null) addProperty("model_log_id", modelLogId) else add("model_log_id", com.google.gson.JsonNull.INSTANCE)
            add("output", response.get("response") ?: com.google.gson.JsonNull.INSTANCE)
            add("timeline", timeline)
            if (error != null) addProperty("error", error) else add("error", com.google.gson.JsonNull.INSTANCE)
        }
    }
}
