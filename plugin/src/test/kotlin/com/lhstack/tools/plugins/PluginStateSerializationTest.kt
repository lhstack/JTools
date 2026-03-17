package com.lhstack.tools.plugins

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.lhstack.tools.agent.AgentAttachmentState
import com.lhstack.tools.agent.AgentModelSettings
import com.lhstack.tools.agent.AgentRenderState
import com.lhstack.tools.agent.AgentSkillResourceState
import com.lhstack.tools.agent.AgentSkillState
import com.lhstack.tools.agent.AgentSessionRuntimeState
import com.lhstack.tools.agent.AgentSessionState
import com.lhstack.tools.agent.AgentProviderState
import com.lhstack.tools.agent.McpServerState
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PluginStateSerializationTest {

    @Test
    fun `plugin state uses stable xml tags for persisted agent beans`() {
        val state = PluginState.State().apply {
            agentProviders.add(
                AgentProviderState().apply {
                    name = "Provider"
                    modelSettings.add(AgentModelSettings().apply { model = "test-model" })
                }
            )
            agentSessions.add(
                AgentSessionState().apply {
                    id = "session-1"
                    title = "Session"
                    enabledSkillIds.add("skill-1")
                    renders.add(
                        AgentRenderState().apply {
                            role = "用户"
                            attachments.add(AgentAttachmentState(name = "demo.txt"))
                        }
                    )
                    runtime = AgentSessionRuntimeState().apply {
                        planModeEnabled = true
                    }
                }
            )
            agentSkills.add(
                AgentSkillState().apply {
                    id = "skill-1"
                    name = "代码审查"
                    description = "review"
                    skillContent = "review only"
                    resources.add(AgentSkillResourceState().apply {
                        path = "references/api.md"
                        content = "api"
                    })
                }
            )
            agentMcpServers.add(McpServerState().apply { name = "mcp" })
        }

        val xml = JDOMUtil.writeElement(XmlSerializer.serialize(state))

        assertTrue(xml.contains("<agent-provider"))
        assertTrue(xml.contains("<model-settings"))
        assertTrue(xml.contains("<agent-session"))
        assertTrue(xml.contains("<render-item"))
        assertTrue(xml.contains("<attachment"))
        assertTrue(xml.contains("<agent-skill"))
        assertTrue(xml.contains("<skill-resource"))
        assertTrue(xml.contains("<session-runtime"))
        assertTrue(xml.contains("<mcp-server"))
    }
}
