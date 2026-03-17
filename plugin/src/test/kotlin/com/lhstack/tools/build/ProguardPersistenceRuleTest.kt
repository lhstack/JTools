package com.lhstack.tools.build

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue

class ProguardPersistenceRuleTest {

    @Test
    fun `persistent state classes are excluded from proguard renaming`() {
        val buildFile = Paths.get("build.gradle.kts")
        val script = Files.readString(buildFile)

        assertTrue(script.contains("-keep class com.lhstack.tools.plugins.PluginState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.actions.DeveloperState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.plugins.CefPluginCacheState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.agent.AgentProviderState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.agent.AgentModelSettings { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.agent.AgentSessionState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.agent.AgentRenderState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.agent.AgentSessionRuntimeState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.agent.AgentAttachmentState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.agent.AgentSkillState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.agent.AgentSkillResourceState { *; }"))
        assertTrue(script.contains("-keep class com.lhstack.tools.agent.McpServerState { *; }"))
    }
}
