package com.lhstack.tools.agent

import io.agentscope.core.memory.autocontext.AutoContextMemory
import io.agentscope.core.skill.SkillBox
import io.agentscope.core.tool.Toolkit
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentRuntimeFeaturesTest {

    @Test
    fun `session defaults to chat mode`() {
        val session = AgentSessionState()

        assertEquals(AgentConversationMode.CHAT.id, session.conversationMode)
    }

    @Test
    fun `plan enabled session builds react agent with plan notebook`() {
        val provider = sampleProvider()
        val session = AgentSessionState().apply {
            runtime.planModeEnabled = true
        }

        val runtime = AgentScopeRuntime().create(provider, session)

        assertNotNull(runtime.features.planNotebook)
        assertTrue(runtime.features.memory is AutoContextMemory)
        assertTrue(runtime.features.statePersistence.planNotebookManaged())
    }

    @Test
    fun `session persistence flags map to sdk state persistence`() {
        val features = AgentRuntimeFeaturesFactory.create(
            session = AgentSessionState().apply {
                runtime.stateMemoryManaged = false
                runtime.stateToolkitManaged = true
                runtime.statePlanNotebookManaged = false
                runtime.statefulToolsManaged = false
            },
            modelSpec = AgentScopeModelFactory().create(sampleProvider()),
        )

        assertFalse(features.statePersistence.memoryManaged())
        assertTrue(features.statePersistence.toolkitManaged())
        assertFalse(features.statePersistence.planNotebookManaged())
        assertFalse(features.statePersistence.statefulToolsManaged())
    }

    @Test
    fun `runtime binds toolkit into skill box when both are configured`() {
        val provider = sampleProvider()
        val toolkit = Toolkit()
        val skillBox = SkillBox("", "")

        AgentScopeRuntime().create(
            provider = provider,
            config = AgentScopeRuntimeConfig(
                toolkit = toolkit,
                skillBox = skillBox,
            )
        )

        val toolkitField = SkillBox::class.java.getDeclaredField("toolkit").apply {
            isAccessible = true
        }
        assertTrue(toolkitField.get(skillBox) === toolkit)
    }

    private fun sampleProvider(): AgentProviderState {
        return AgentProviderState().apply {
            providerType = AgentProviderCatalog.TYPE_OPENAI_COMPATIBLE
            vendorTemplate = AgentProviderCatalog.TEMPLATE_OPENAI
            activeModel = "gpt-4o-mini"
            apiKey = "test-key"
            baseUrl = AgentProviderSupport.defaultBaseUrl(providerType, vendorTemplate)
            AgentProviderSupport.normalizeProvider(this)
        }
    }
}
