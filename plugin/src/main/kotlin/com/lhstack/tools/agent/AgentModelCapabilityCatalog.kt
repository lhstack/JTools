package com.lhstack.tools.agent

data class AgentCapabilityOption(
    val id: String,
    val label: String,
    val description: String,
)

object AgentModelCapabilityCatalog {
    private val multimodal = listOf(
        AgentCapabilityOption("text", "文本", "支持文本输入。"),
        AgentCapabilityOption("image", "图片", "支持图片输入，并在聊天框显示图片附件入口。"),
        AgentCapabilityOption("audio", "音频", "支持音频输入。"),
        AgentCapabilityOption("video", "视频", "支持视频输入。"),
        AgentCapabilityOption("file", "文件", "支持普通文件附件输入。"),
    )

    private val execution = listOf(
        AgentCapabilityOption("tool_calling", "工具调用", "支持模型主动调用工具。"),
        AgentCapabilityOption("streaming", "流式", "支持流式输出。"),
    )

    private val allIds = (multimodal + execution).map { it.id }.toSet()
    private val multimodalIds = multimodal.map { it.id }.toSet()

    fun multimodalOptions(): List<AgentCapabilityOption> = multimodal

    fun executionOptions(): List<AgentCapabilityOption> = execution

    fun normalize(values: Iterable<String>): List<String> {
        return values.map { it.trim() }
            .filter { it.isNotBlank() && it in allIds }
            .distinct()
    }

    fun isMultimodal(id: String): Boolean = id in multimodalIds
}
