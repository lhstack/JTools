package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliToolTest {
    private val tool = CliTool()

    @Test
    fun `root help lists explicit mcp commands`() {
        val result = tool.callJsonBlocking(input("--help")).asJsonObject
        val commands = result.getAsJsonArray("commands").map { it.asJsonObject["command"].asString }

        assertTrue("mcp.list" in commands)
        assertTrue("mcp.resources" in commands)
        assertTrue("mcp.read_resource" in commands)
        assertTrue("mcp.call" in commands)
        assertTrue("provider.list" in commands)
        assertTrue("provider.remote_models" in commands)
        assertTrue("model.create" in commands)
        assertTrue("agent.run" in commands)
        assertTrue("agent.run.get" in commands)
    }

    @Test
    fun `command help describes structured arguments`() {
        val result = tool.callJsonBlocking(input("mcp.create --help")).asJsonObject
        val arguments = result.getAsJsonObject("arguments")

        assertEquals("mcp.create", result["command"].asString)
        assertTrue(arguments["name"].asJsonObject["required"].asBoolean)
        assertEquals("string", arguments["transport"].asJsonObject["type"].asString)
        val transportDescription = arguments["transport"].asJsonObject["description"].asString
        assertEquals("stdio/sse/streamable_http", transportDescription)
    }


    @Test
    fun `agent run requires session and receiver`() {
        val result = tool.callJsonBlocking(input("agent.run --help")).asJsonObject
        val arguments = result.getAsJsonObject("arguments")

        assertTrue(arguments["session_id"].asJsonObject["required"].asBoolean)
        assertTrue(arguments["receiver"].asJsonObject["required"].asBoolean)
    }

    private fun input(command: String) = JsonObject().apply { addProperty("command", command) }
}
