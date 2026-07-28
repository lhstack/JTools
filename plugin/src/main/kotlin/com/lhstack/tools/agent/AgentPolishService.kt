package com.lhstack.tools.agent

import com.intellij.openapi.project.Project
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.provider.AgentRuntime
import com.lhstack.tools.agent.model.tools.PolishResultTool
import com.lhstack.tools.concurrent.AgentExecutors
import com.lhstack.tools.db.service.AgentRecord
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.ResourceConfigService
import com.lhstack.tools.db.service.SettingService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

/**
 * 输入框润色任务。沿用远端模型拉取的「后台任务 + taskId + 轮询」范式：
 * start 提交任务返回 taskId，poll 查询状态，cancel 中断。
 *
 * 润色不是会话消息，因此不复用 AgentRunService（它会把结果投递成聊天消息或
 * 加入队列）；这里直接调用 AgentRuntime，并且不传 sessionId，避免污染会话历史。
 */
object AgentPolishService {

    /** 润色 Agent 配置键；值为空表示未配置，此时不暴露润色入口。 */
    const val SETTING_KEY = "chat.polish.agent_id"

    private val tasks = ConcurrentHashMap<String, PolishTask>()

    private class PolishTask(val cancel: ModelCancel) {
        /** 提交前为 null；此时任务已登记但尚未开始，对外表现为运行中。 */
        @Volatile
        var future: Future<List<String>>? = null
    }

    /** 已配置且可用的润色 Agent；未配置、已删除或已停用时返回 null。 */
    fun configuredAgent(): AgentRecord? {
        val agentId = SettingService.setting(SETTING_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toLongOrNull()
            ?: return null
        return AgentService.agentById(agentId)?.takeIf { it.enabled }
    }

    fun start(project: Project, text: String): String {
        val content = text.trim()
        require(content.isNotEmpty()) { "润色内容不能为空" }
        val agent = configuredAgent() ?: throw IllegalStateException("未配置可用的润色 Agent")
        val agentId = agent.id ?: throw IllegalStateException("润色 Agent 缺少 ID")
        val workspace = project.basePath ?: throw IllegalStateException("当前项目没有项目路径")

        val taskId = UUID.randomUUID().toString()
        val task = PolishTask(ModelCancel())
        // 先登记再提交：否则任务可能在登记前就结束，poll 拿不到任务。
        tasks[taskId] = task
        task.future = AgentExecutors.shared.submit<List<String>> {
            execute(project, agent, agentId, workspace, content, task.cancel)
        }
        return taskId
    }

    fun poll(taskId: String): Map<String, Any?> {
        val task = tasks[taskId] ?: throw IllegalArgumentException("润色任务不存在或已结束")
        val future = task.future ?: return mapOf("status" to "running")
        if (!future.isDone) return mapOf("status" to "running")
        tasks.remove(taskId, task)
        return try {
            mapOf("status" to "completed", "results" to future.get())
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            if (task.cancel.isCancelled()) {
                mapOf("status" to "cancelled")
            } else {
                mapOf("status" to "failed", "error" to (cause.message ?: cause.toString()))
            }
        }
    }

    /**
     * 取消润色任务。取消动作本身在调用线程执行，调用方需保证不在 EDT 上调用，
     * 避免 HTTP 关闭阻塞界面。
     */
    fun cancel(taskId: String) {
        val task = tasks.remove(taskId) ?: return
        task.cancel.cancel()
        task.future?.cancel(true)
    }

    private fun execute(
        project: Project,
        agent: AgentRecord,
        agentId: Long,
        workspace: String,
        content: String,
        cancel: ModelCancel,
    ): List<String> {
        val collector = PolishResultTool()
        AgentRuntime.execute(
            agent,
            AgentRuntime.Request(
                agentId = agentId,
                prompt = buildPrompt(content),
                triggerType = TRIGGER_TYPE,
                workspace = workspace,
                skillsRootDir = ResourceConfigService.skillsRootDir(),
                extraTools = listOf(collector),
                cancel = cancel,
                toolCancel = cancel,
                project = project,
                logMessageType = TRIGGER_TYPE,
                logPromptMessage = content,
            ),
        )
        // 模型未按约定调用工具时直接失败：不解析回答文本凑结果，避免伪造成功。
        return collector.submittedResults()
            ?: throw IllegalStateException(
                "润色 Agent 未调用 `${PolishResultTool.NAME}` 提交结果，请检查该 Agent 的模型是否支持工具调用"
            )
    }

    /**
     * 润色指令。刻意不指定目标风格：润色风格由该 Agent 自身的角色设定与工作方法决定，
     * 此处若写“风格各异”之类要求会与人格配置冲突，导致人格被压过。
     */
    private fun buildPrompt(content: String): String = buildString {
        append("请润色下面 <content> 标签中的文本。\n")
        append("要求：保持原意和原语言不变，只改善表达；")
        append("给出 ${PolishResultTool.MIN_RESULTS} 到 ${PolishResultTool.MAX_RESULTS} 个候选版本，")
        append("各版本在同一风格下于用词和句式上有所区别；")
        append("每个版本都能直接替换原文。\n")
        append("必须调用 `${PolishResultTool.NAME}` 工具提交结果，不要把结果写在回答正文里。\n\n")
        append("<content>\n")
        append(content)
        append("\n</content>")
    }

    private const val TRIGGER_TYPE = "chat_polish"
}
