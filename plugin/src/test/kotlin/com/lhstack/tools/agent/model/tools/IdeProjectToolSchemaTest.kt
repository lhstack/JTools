package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.lhstack.tools.llm.ToolDefinition
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeProjectToolSchemaTest {
    @Test
    fun `every project tool schema explicitly declares required parameters`() {
        definitions().forEach { definition ->
            assertObjectContract(definition.name, definition.parameters.asJsonObject)
        }
    }

    @Test
    fun `project tool required parameters match the runtime contract`() {
        val requiredByTool = definitions().associate { definition ->
            definition.name to definition.parameters.asJsonObject.requiredNames()
        }

        assertEquals(setOf("files"), requiredByTool.getValue(ReadProjectFilesTool.NAME))
        assertEquals(setOf("files"), requiredByTool.getValue(WriteProjectFilesTool.NAME))
        assertEquals(setOf("edits"), requiredByTool.getValue(ReplaceProjectTextTool.NAME))
        assertEquals(setOf("queries"), requiredByTool.getValue(FindProjectFilesTool.NAME))
        assertEquals(setOf("queries"), requiredByTool.getValue(FindProjectClassesTool.NAME))
        assertEquals(false, definitions().first { it.name == FindProjectFilesTool.NAME }.parameters.asJsonObject
            .getAsJsonObject("properties").getAsJsonObject("include_global").get("default").asBoolean)
        assertEquals(false, definitions().first { it.name == FindProjectClassesTool.NAME }.parameters.asJsonObject
            .getAsJsonObject("properties").getAsJsonObject("include_global").get("default").asBoolean)
        assertEquals(setOf("text"), requiredByTool.getValue(SearchProjectTextTool.NAME))
        assertEquals(setOf("files"), requiredByTool.getValue(FormatProjectFilesTool.NAME))
        assertEquals(emptySet(), requiredByTool.getValue(BuildProjectTool.NAME))
        assertEquals(setOf("paths"), requiredByTool.getValue(InspectProjectFilesTool.NAME))
    }

    private fun assertObjectContract(toolName: String, schema: JsonObject) {
        if (schema.get("type")?.asString != "object") return
        assertTrue(schema.has("required"), "$toolName object schema must declare required")
        val required = schema.requiredNames()
        schema.getAsJsonObject("properties")?.entrySet()?.forEach { (name, property) ->
            val description = property.asJsonObject.get("description")?.asString.orEmpty()
            val expectedPrefix = if (name in required) "必填。" else "可选。"
            assertTrue(
                description.startsWith(expectedPrefix),
                "$toolName.$name description must start with $expectedPrefix",
            )
            inspectNestedObjects(toolName, property)
        }
    }

    private fun inspectNestedObjects(toolName: String, element: JsonElement) {
        if (element.isJsonObject) {
            val value = element.asJsonObject
            assertObjectContract(toolName, value)
            value.entrySet().forEach { (_, child) -> inspectNestedObjects(toolName, child) }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { child -> inspectNestedObjects(toolName, child) }
        }
    }

    private fun JsonObject.requiredNames(): Set<String> =
        getAsJsonArray("required")?.map { it.asString }?.toSet().orEmpty()

    private fun definitions(): List<ToolDefinition> {
        val project = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as Project
        val support = IdeProjectSupport(WorkspaceTools(System.getProperty("java.io.tmpdir")), project)
        return listOf(
            ReadProjectFilesTool(support),
            WriteProjectFilesTool(support),
            ReplaceProjectTextTool(support),
            FindProjectFilesTool(support),
            FindProjectClassesTool(support),
            SearchProjectTextTool(support),
            FormatProjectFilesTool(support),
            BuildProjectTool(support, null),
            InspectProjectFilesTool(support),
        ).map { tool -> tool.definition("") }
    }
}
