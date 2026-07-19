package com.lhstack.tools.db.config

/**
 * Agent 配置数据类。完全照抄 awake-claw repository/agent.rs 的
 * AgentRuntimeConfig / AgentCapabilityConfig / AgentPersonaConfig /
 * AgentDistillConfig 等结构。
 *
 * 这些结构以 JSON 文本存在 agents 表的 runtime_params / ext_config /
 * distill_config 列中，由 Gson 序列化。Gson 字段名默认按属性名，awake 侧
 * 用 snake_case，因此这里显式用 @SerializedName 对齐 JSON key。
 *
 */

import com.google.gson.annotations.SerializedName

/** Agent 自身运行配置。模型相关参数不在 Agent 存快照，执行时实时读取所选模型配置。 */
data class AgentRuntimeConfig(
    @SerializedName("include_history")
    val includeHistory: Boolean = false,
    @SerializedName("max_history_messages")
    val maxHistoryMessages: Int? = null,
    @SerializedName("tool_call_retention_rounds")
    val toolCallRetentionRounds: Int? = null,
)

/** 照抄 AgentCapabilityConfig。 */
data class AgentCapabilityConfig(
    @SerializedName("tools")
    val tools: AgentEnabledItemsConfig = AgentEnabledItemsConfig(),
    @SerializedName("plugin_functions")
    val pluginFunctions: AgentEnabledItemsConfig = AgentEnabledItemsConfig(),
    @SerializedName("skills")
    val skills: AgentEnabledItemsConfig = AgentEnabledItemsConfig(),
    @SerializedName("skill_prompt_enabled")
    val skillPromptEnabled: Boolean = false,
    @SerializedName("view_resources")
    val viewResources: AgentViewResourcesConfig = AgentViewResourcesConfig(),
    @SerializedName("persona")
    val persona: AgentPersonaConfig = AgentPersonaConfig(),
)

/** 照抄 AgentEnabledItemsConfig。 */
data class AgentEnabledItemsConfig(
    @SerializedName("enabled")
    val enabled: List<String> = emptyList(),
    @SerializedName("include_new")
    val includeNew: Boolean = false,
    @SerializedName("disabled")
    val disabled: List<String> = emptyList(),
)

/** 照抄 AgentViewResourcesConfig。 */
data class AgentViewResourcesConfig(
    @SerializedName("image")
    val image: AgentViewResourceRef = AgentViewResourceRef(),
    @SerializedName("audio")
    val audio: AgentViewResourceRef = AgentViewResourceRef(),
    @SerializedName("video")
    val video: AgentViewResourceRef = AgentViewResourceRef(),
    @SerializedName("file")
    val file: AgentViewResourceRef = AgentViewResourceRef(),
)

/** 照抄 AgentViewResourceRef。 */
data class AgentViewResourceRef(
    @SerializedName("enabled")
    val enabled: Boolean = false,
    @SerializedName("agent_id")
    val agentId: Long? = null,
)

/** 照抄 AgentPersonaConfig。默认最大字符数 512。 */
data class AgentPersonaConfig(
    @SerializedName("memory")
    val memory: String = "",
    @SerializedName("memory_max_chars")
    val memoryMaxChars: Int = DEFAULT_PERSONA_MAX_CHARS,
    @SerializedName("behavior_habits")
    val behaviorHabits: String = "",
    @SerializedName("behavior_habits_max_chars")
    val behaviorHabitsMaxChars: Int = DEFAULT_PERSONA_MAX_CHARS,
    @SerializedName("soul")
    val soul: String = "",
    @SerializedName("soul_max_chars")
    val soulMaxChars: Int = DEFAULT_PERSONA_MAX_CHARS,
    @SerializedName("profile")
    val profile: String = "",
    @SerializedName("profile_max_chars")
    val profileMaxChars: Int = DEFAULT_PERSONA_MAX_CHARS,
    @SerializedName("guardrails")
    val guardrails: String = "",
    @SerializedName("guardrails_max_chars")
    val guardrailsMaxChars: Int = DEFAULT_PERSONA_MAX_CHARS,
) {
    companion object {
        const val DEFAULT_PERSONA_MAX_CHARS = 512
    }
}

/** 照抄 AgentDistillConfig。默认 min_messages 10，message_types 为默认蒸馏类型集合。 */
data class AgentDistillConfig(
    @SerializedName("enabled")
    val enabled: Boolean = false,
    @SerializedName("agent_id")
    val agentId: Long? = null,
    @SerializedName("min_messages")
    val minMessages: Int = DEFAULT_MIN_MESSAGES,
    @SerializedName("message_types")
    val messageTypes: List<String> = AgentDistillLogKind.defaultDistillKinds(),
    @SerializedName("extra_prompt")
    val extraPrompt: String? = null,
    @SerializedName("last_distilled_model_log_id")
    val lastDistilledModelLogId: Long? = null,
) {
    /** 照抄 effective_message_types：空时回默认，非空时映射规范化。 */
    fun effectiveMessageTypes(): List<String> {
        if (messageTypes.isEmpty()) {
            return AgentDistillLogKind.defaultDistillKinds()
        }
        return messageTypes.mapNotNull { AgentDistillLogKind.fromStoredStr(it) }
            .map { it.asStr() }
    }

    /** 照抄 canonicalized。 */
    fun canonicalized(): AgentDistillConfig = copy(messageTypes = effectiveMessageTypes())

    /** 照抄 validate_message_types。 */
    fun validateMessageTypes() {
        for (value in messageTypes) {
            if (AgentDistillLogKind.fromStr(value) == null) {
                throw IllegalArgumentException(
                    "Agent 蒸馏消息类型 `$value` 无效，可用值：${AgentDistillLogKind.allowedValues().joinToString(", ")}"
                )
            }
        }
    }

    companion object {
        const val DEFAULT_MIN_MESSAGES = 10
    }
}

/** 照抄 AgentDistillLogKind。 */
enum class AgentDistillLogKind {
    DIRECT_RUN,
    CHAT_TURN,
    WORKFLOW_NODE,
    SCHEDULED_RUN,
    MULTIMODAL_ANALYSIS,
    AGENT_DISTILLATION,
    ENVIRONMENT_DISTILLATION;

    fun asStr(): String = when (this) {
        DIRECT_RUN -> "direct_run"
        CHAT_TURN -> "chat_turn"
        WORKFLOW_NODE -> "workflow_node"
        SCHEDULED_RUN -> "scheduled_run"
        MULTIMODAL_ANALYSIS -> "multimodal_analysis"
        AGENT_DISTILLATION -> "agent_distillation"
        ENVIRONMENT_DISTILLATION -> "environment_distillation"
    }

    companion object {
        /** 照抄 from_str。 */
        fun fromStr(value: String): AgentDistillLogKind? = when (value.trim()) {
            "direct_run" -> DIRECT_RUN
            "chat_turn" -> CHAT_TURN
            "workflow_node" -> WORKFLOW_NODE
            "scheduled_run" -> SCHEDULED_RUN
            "multimodal_analysis" -> MULTIMODAL_ANALYSIS
            "agent_distillation" -> AGENT_DISTILLATION
            "environment_distillation" -> ENVIRONMENT_DISTILLATION
            else -> null
        }

        /** 照抄 from_stored_str：兼容旧值。 */
        fun fromStoredStr(value: String): AgentDistillLogKind? = fromStr(value) ?: when (value.trim()) {
            "manual" -> DIRECT_RUN
            "chat" -> CHAT_TURN
            "workflow" -> WORKFLOW_NODE
            "scheduled_task" -> SCHEDULED_RUN
            "distillation_record" -> AGENT_DISTILLATION
            else -> null
        }

        /** 照抄 from_trigger。 */
        fun fromTrigger(triggerType: String): AgentDistillLogKind = when (triggerType) {
            "chat" -> CHAT_TURN
            "workflow" -> WORKFLOW_NODE
            "schedule" -> SCHEDULED_RUN
            "view_image", "view_audio", "view_video", "view_files", "view_resources" -> MULTIMODAL_ANALYSIS
            "distillation" -> AGENT_DISTILLATION
            "environment_distillation" -> ENVIRONMENT_DISTILLATION
            else -> DIRECT_RUN
        }

        /** 照抄 default_distill_kinds。 */
        fun defaultDistillKinds(): List<String> = listOf(
            DIRECT_RUN, CHAT_TURN, WORKFLOW_NODE, SCHEDULED_RUN, MULTIMODAL_ANALYSIS,
        ).map { it.asStr() }

        /** 照抄 allowed_values。 */
        fun allowedValues(): List<String> = listOf(
            "direct_run", "chat_turn", "workflow_node", "scheduled_run",
            "multimodal_analysis", "agent_distillation", "environment_distillation",
        )
    }
}
