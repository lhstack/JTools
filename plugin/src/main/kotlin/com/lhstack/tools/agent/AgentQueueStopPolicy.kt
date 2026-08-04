package com.lhstack.tools.agent

/** 决定停止按钮作用层级：工具执行优先于整个模型会话。 */
internal object AgentQueueStopPolicy {
    fun target(hasRunningTools: Boolean): Target =
        if (hasRunningTools) Target.ACTIVE_TOOLS else Target.CONVERSATION

    enum class Target {
        ACTIVE_TOOLS,
        CONVERSATION,
    }
}
