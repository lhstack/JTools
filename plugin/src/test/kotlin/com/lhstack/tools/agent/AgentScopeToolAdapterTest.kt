package com.lhstack.tools.agent

import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.tool.ToolCallParam
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentScopeToolAdapterTest {

    @Test
    fun `registry tool is wrapped as agentscope tool`() {
        val tool = AgentTool(
            name = "demo_tool",
            description = "demo",
            parametersJson = """{"type":"object","properties":{"name":{"type":"string"}}}""",
            call = { arguments -> """{"echo":$arguments}""" },
        )

        val wrapped = AgentScopeToolAdapter.wrap(tool)
        val result = wrapped.callAsync(
            ToolCallParam.builder()
                .toolUseBlock(
                    ToolUseBlock.builder()
                        .id("call-1")
                        .name("demo_tool")
                        .input(mapOf("name" to "world"))
                        .build()
                )
                .input(mapOf("name" to "world"))
                .build()
        ).block()!!

        assertEquals("demo_tool", wrapped.getName())
        assertTrue(wrapped.getParameters().containsKey("properties"))
        assertEquals("call-1", result.id)
        assertEquals("demo_tool", result.name)
        val output = result.output.first() as TextBlock
        assertTrue(output.text.contains("\"name\":\"world\""))
    }
}
