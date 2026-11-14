package com.lhstack.tools.db.service

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingEnvironmentToolSelectionTest {
    private val gson = Gson()

    @Test
    fun `silently removes both enabled and disabled missing tools`() {
        val config = CodingEnvironmentService.parseConfig(
            """{"tools":{"build_project":true,"removed_tool":false}}"""
        )

        assertTrue(config.tools.isEmpty())
    }

    @Test
    fun `preserves registered tool switches and include new setting`() {
        val tools = CodingEnvironmentService.toolNames
            .mapIndexed { index, name -> name to (index % 2 == 0) }
            .toMap()
        assertTrue(tools.isNotEmpty())

        listOf(true, false).forEach { includeNew ->
            val original = CodingEnvironmentConfig(
                tools = tools + ("build_project" to true),
                toolsIncludeNew = includeNew,
            )
            val config = CodingEnvironmentService.parseConfig(gson.toJson(original))

            assertEquals(tools, config.tools)
            assertEquals(includeNew, config.toolsIncludeNew)
            assertEquals(tools.filterValues { it }.keys, CodingEnvironmentService.enabledTools(config))
        }
    }

    @Test
    fun `removing old tools does not explicitly select new tools`() {
        val config = CodingEnvironmentService.parseConfig(
            """{"tools":{"build_project":true},"tools_include_new":false}"""
        )

        assertTrue(config.tools.isEmpty())
        assertFalse(config.toolsIncludeNew)
        assertTrue(CodingEnvironmentService.enabledTools(config).isEmpty())
    }

    @Test
    fun `serialized normalized config does not restore removed tools`() {
        val retainedTool = CodingEnvironmentService.toolNames.first()
        val original = CodingEnvironmentConfig(
            systemPrompt = "保留系统提示词",
            tools = mapOf(retainedTool to false, "build_project" to true),
            toolsIncludeNew = false,
            pluginFunctions = mapOf("plugin:example:run" to true),
        )
        val config = CodingEnvironmentService.parseConfig(gson.toJson(original))
        val saved = gson.toJsonTree(config).asJsonObject

        assertEquals(mapOf(retainedTool to false), config.tools)
        assertFalse(saved.getAsJsonObject("tools").has("build_project"))
        assertEquals(original.systemPrompt, config.systemPrompt)
        assertEquals(original.pluginFunctions, config.pluginFunctions)
        assertEquals(config, CodingEnvironmentService.parseConfig(saved.toString()))
    }

    @Test
    fun `still rejects invalid tool flag types including missing tools`() {
        listOf(CodingEnvironmentService.toolNames.first(), "build_project").forEach { name ->
            val error = assertFailsWith<IllegalArgumentException> {
                CodingEnvironmentService.parseConfig("""{"tools":{"$name":"true"}}""")
            }

            assertEquals("资源选择 $name 必须是 boolean", error.message)
        }
    }

    @Test
    fun `still rejects invalid include new type with missing tools`() {
        val error = assertFailsWith<IllegalArgumentException> {
            CodingEnvironmentService.parseConfig(
                """{"tools":{"build_project":true},"tools_include_new":"false"}"""
            )
        }

        assertEquals("tools_include_new 必须为 boolean", error.message)
    }
}
