package com.lhstack.tools.agent

import io.agentscope.core.memory.InMemoryMemory
import io.agentscope.core.memory.LongTermMemory
import io.agentscope.core.memory.LongTermMemoryMode
import io.agentscope.core.memory.Memory
import io.agentscope.core.memory.autocontext.AutoContextConfig
import io.agentscope.core.memory.autocontext.AutoContextMemory
import io.agentscope.core.plan.PlanNotebook
import io.agentscope.core.plan.storage.InMemoryPlanStorage
import io.agentscope.core.state.SessionKey
import io.agentscope.core.state.SimpleSessionKey
import io.agentscope.core.state.StatePersistence
import java.util.UUID

data class AgentRuntimeFeatures(
    val memory: Memory,
    val planNotebook: PlanNotebook?,
    val longTermMemory: LongTermMemory?,
    val longTermMemoryMode: LongTermMemoryMode?,
    val statePersistence: StatePersistence,
    val sessionKey: SessionKey,
)

object AgentRuntimeFeaturesFactory {

    fun create(session: AgentSessionState, modelSpec: AgentScopeModelSpec): AgentRuntimeFeatures {
        val runtime = session.runtime.normalize()
        if (session.id.isBlank()) {
            session.id = UUID.randomUUID().toString()
        }
        val sessionKey = SimpleSessionKey.of(session.id)
        val planNotebook = if (runtime.planModeEnabled) {
            PlanNotebook.builder()
                .storage(InMemoryPlanStorage())
                .keyPrefix(sessionKey.toIdentifier())
                .build()
        } else {
            null
        }
        val memory = when (AgentShortTermMemoryType.fromId(runtime.shortTermMemoryType)) {
            AgentShortTermMemoryType.IN_MEMORY -> InMemoryMemory()
            AgentShortTermMemoryType.AUTO_CONTEXT -> AutoContextMemory(
                AutoContextConfig.builder().build(),
                modelSpec.model,
            ).apply {
                if (planNotebook != null) {
                    attachPlanNote(planNotebook)
                }
            }
        }
        return AgentRuntimeFeatures(
            memory = memory,
            planNotebook = planNotebook,
            longTermMemory = null,
            longTermMemoryMode = AgentLongTermMemoryModeType.fromId(runtime.longTermMemoryMode).sdkMode,
            statePersistence = StatePersistence.builder()
                .memoryManaged(runtime.stateMemoryManaged)
                .toolkitManaged(runtime.stateToolkitManaged)
                .planNotebookManaged(runtime.statePlanNotebookManaged)
                .statefulToolsManaged(runtime.statefulToolsManaged)
                .build(),
            sessionKey = sessionKey,
        )
    }

    private fun AgentSessionRuntimeState.normalize(): AgentSessionRuntimeState {
        shortTermMemoryType = AgentShortTermMemoryType.fromId(shortTermMemoryType).id
        longTermMemoryMode = AgentLongTermMemoryModeType.fromId(longTermMemoryMode).id
        return this
    }
}
