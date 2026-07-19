package com.lhstack.tools.agent.model.tools

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertNotNull

class McpSdkTest {
    @Test
    fun `MCP mapper and schema validator are constructed explicitly`() {
        val mapper = JsonMapper.builder().build()

        assertNotNull(JacksonMcpJsonMapper(mapper))
        assertNotNull(DefaultJsonSchemaValidator(mapper))
    }
}
