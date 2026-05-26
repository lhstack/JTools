package com.lhstack.tools.agent

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.lhstack.tools.plugins.PluginInfo
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentToolRegistryFileToolsTest {

    @Test
    fun `rust style runtime tools are available and legacy file tools are removed`() {
        val tools = registerToolSurface(fakeProject(Files.createTempDirectory("agent-tool-registry").toString()))
        val toolNames = tools.map { it.name }.toSet()

        assertTrue("bash" in toolNames)
        assertTrue("read_file" in toolNames)
        assertTrue("write_file" in toolNames)
        assertTrue("edit_file" in toolNames)
        assertTrue("glob_search" in toolNames)
        assertTrue("grep_search" in toolNames)
        assertTrue("WebFetch" in toolNames)
        assertTrue("WebSearch" in toolNames)
        assertTrue("browser_open" in toolNames)
        assertTrue("browser_read" in toolNames)
        assertTrue("browser_close" in toolNames)
        assertTrue("jtools_get_current_time" in toolNames)

        assertFalse("jtools_list_files" in toolNames)
        assertFalse("jtools_get_file_char_count" in toolNames)
        assertFalse("jtools_read_file_chunk" in toolNames)
        assertFalse("jtools_open_write_session" in toolNames)
        assertFalse("jtools_append_write_session" in toolNames)
        assertFalse("jtools_commit_write_session" in toolNames)
        assertFalse("jtools_discard_write_session" in toolNames)
        assertFalse("jtools_execute_command" in toolNames)
    }

    @Test
    fun `write and edit tools declare workspace write permission`() {
        val tools = registerJtools(fakeProject(Files.createTempDirectory("agent-tool-registry-permissions").toString()))

        assertEquals(
            AgentToolPermissionScope.WORKSPACE_WRITE,
            assertNotNull(tools.firstOrNull { it.name == "write_file" }).requiredPermission
        )
        assertEquals(
            AgentToolPermissionScope.WORKSPACE_WRITE,
            assertNotNull(tools.firstOrNull { it.name == "edit_file" }).requiredPermission
        )
        assertEquals(
            AgentToolPermissionScope.READ_ONLY,
            assertNotNull(tools.firstOrNull { it.name == "read_file" }).requiredPermission
        )
    }

    @Test
    fun `system tools declare explicit permissions by capability`() {
        val tools = registerToolSurface(fakeProject(Files.createTempDirectory("agent-tool-registry-system-permissions").toString()))
            .associateBy { it.name }

        fun assertPermission(name: String, permission: AgentToolPermissionScope) {
            assertEquals(permission, assertNotNull(tools[name], name).requiredPermission, name)
        }

        listOf(
            "read_file",
            "glob_search",
            "grep_search",
            "WebFetch",
            "WebSearch",
            "browser_open",
            "browser_read",
            "browser_click",
            "browser_type",
            "browser_scroll",
            "browser_show",
            "browser_hide",
            "browser_close",
            "jtools_list_plugins",
            "jtools_get_plugin_detail",
            "jtools_get_system_info",
            "jtools_get_current_time",
            "jtools_get_current_project",
            "jtools_mcp_list_servers",
            "jtools_mcp_query",
            "jtools_mcp_test_server",
            "jtools_skill_list",
        ).forEach { assertPermission(it, AgentToolPermissionScope.READ_ONLY) }

        listOf(
            "write_file",
            "edit_file",
            "jtools_create_directory",
            "jtools_install_plugin_from_file",
            "jtools_uninstall_plugin",
            "jtools_skill_delete",
            "jtools_skill_import_from_path",
            "jtools_mcp_update_server",
            "jtools_mcp_delete_server",
        ).forEach { assertPermission(it, AgentToolPermissionScope.WORKSPACE_WRITE) }

        listOf(
            "bash",
        ).forEach { assertPermission(it, AgentToolPermissionScope.DANGER_FULL_ACCESS) }
    }

    @Test
    fun `write file overwrites content and reports update metadata`() {
        val projectRoot = Files.createTempDirectory("agent-tool-registry-write")
        val target = projectRoot.resolve("notes.txt")
        Files.writeString(target, "alpha\nbeta\n", StandardCharsets.UTF_8)

        val result = invokeTool(
            fakeProject(projectRoot.toString()),
            "write_file",
            """{"path":"notes.txt","content":"gamma\ndelta\n"}"""
        )

        assertEquals(true, result["ok"])
        assertEquals("update", result["type"])
        assertEquals("alpha\nbeta\n", result["originalFile"])
        assertEquals("gamma\ndelta\n", projectRoot.resolve("notes.txt").readText())
    }

    @Test
    fun `read file defaults to a bounded first page when limit is omitted`() {
        val projectRoot = Files.createTempDirectory("agent-tool-registry-read-page")
        Files.writeString(
            projectRoot.resolve("large.txt"),
            (1..250).joinToString("\n") { "line-$it" },
            StandardCharsets.UTF_8
        )

        val result = invokeTool(
            fakeProject(projectRoot.toString()),
            "read_file",
            """{"path":"large.txt"}"""
        )

        val file = result["file"] as Map<*, *>
        val contentLines = (file["content"] as String).split('\n')
        assertEquals(200, contentLines.size)
        assertEquals("line-1", contentLines.first())
        assertEquals("line-200", contentLines.last())
        assertEquals(0, (file["appliedOffset"] as Number).toInt())
        assertEquals(200, (file["appliedLimit"] as Number).toInt())
        assertEquals(true, file["hasMore"])
    }

    @Test
    fun `read file caps oversized limits`() {
        val projectRoot = Files.createTempDirectory("agent-tool-registry-read-cap")
        Files.writeString(
            projectRoot.resolve("large.txt"),
            (1..1200).joinToString("\n") { "line-$it" },
            StandardCharsets.UTF_8
        )

        val result = invokeTool(
            fakeProject(projectRoot.toString()),
            "read_file",
            """{"path":"large.txt","limit":5000}"""
        )

        val file = result["file"] as Map<*, *>
        assertEquals(1000, (file["content"] as String).split('\n').size)
        assertEquals(1000, (file["appliedLimit"] as Number).toInt())
        assertEquals(true, file["hasMore"])
    }

    @Test
    fun `glob search reads the live filesystem on each call`() {
        val projectRoot = Files.createTempDirectory("agent-tool-registry-glob-live")
        Files.createDirectories(projectRoot.resolve("src"))

        val emptyResult = invokeTool(
            fakeProject(projectRoot.toString()),
            "glob_search",
            """{"pattern":"src/*.kt"}"""
        )
        assertEquals(0, (emptyResult["numFiles"] as Number).toInt())

        val created = projectRoot.resolve("src/NewFile.kt")
        Files.writeString(created, "class NewFile\n", StandardCharsets.UTF_8)

        val result = invokeTool(
            fakeProject(projectRoot.toString()),
            "glob_search",
            """{"pattern":"src/*.kt"}"""
        )

        assertEquals(1, (result["numFiles"] as Number).toInt())
        assertEquals(listOf(created.toFile().absolutePath), result["filenames"])
    }

    @Test
    fun `glob search double star patterns include root files`() {
        val projectRoot = Files.createTempDirectory("agent-tool-registry-glob-root-files")
        val rootFile = projectRoot.resolve("test.py")
        val nestedFile = projectRoot.resolve("src/app.py")
        Files.createDirectories(nestedFile.parent)
        Files.writeString(rootFile, "print('root')\n", StandardCharsets.UTF_8)
        Files.writeString(nestedFile, "print('nested')\n", StandardCharsets.UTF_8)

        val exactResult = invokeTool(
            fakeProject(projectRoot.toString()),
            "glob_search",
            """{"pattern":"**/test.py"}"""
        )
        assertEquals(listOf(rootFile.toFile().absolutePath), exactResult["filenames"])

        val wildcardResult = invokeTool(
            fakeProject(projectRoot.toString()),
            "glob_search",
            """{"pattern":"**/*.py"}"""
        )
        assertEquals(
            listOf(rootFile.toFile().absolutePath, nestedFile.toFile().absolutePath).sorted(),
            wildcardResult["filenames"]
        )
    }

    @Test
    fun `mcp management tools are consolidated`() {
        val tools = registerToolSurface(fakeProject(Files.createTempDirectory("agent-tool-registry-mcp").toString()))
        val toolNames = tools.map { it.name }.toSet()

        assertTrue("jtools_mcp_update_server" in toolNames)
        assertTrue("jtools_mcp_delete_server" in toolNames)
        assertTrue("jtools_mcp_test_server" in toolNames)
        assertTrue("jtools_mcp_query" in toolNames)

        assertFalse("jtools_mcp_add_server" in toolNames)
        assertFalse("jtools_mcp_set_enabled" in toolNames)
        assertFalse("jtools_mcp_list_resources" in toolNames)
        assertFalse("jtools_mcp_read_resource" in toolNames)
        assertFalse("jtools_mcp_list_prompts" in toolNames)
        assertFalse("jtools_mcp_get_prompt" in toolNames)
    }

    @Test
    fun `registry trims redundant skill and general management tools`() {
        val tools = registerToolSurface(fakeProject(Files.createTempDirectory("agent-tool-registry-trimmed").toString()))
        val toolNames = tools.map { it.name }.toSet()

        assertTrue("jtools_skill_list" in toolNames)
        assertTrue("jtools_skill_delete" in toolNames)
        assertFalse("jtools_skill_list_resources" in toolNames)
        assertFalse("jtools_skill_read_resource" in toolNames)
        assertFalse("jtools_skill_update" in toolNames)
        assertFalse("jtools_skill_add" in toolNames)
        assertFalse("jtools_skill_set_enabled" in toolNames)

        assertFalse("jtools_list_plugins_detail" in toolNames)
        assertFalse("jtools_install_plugins_from_files" in toolNames)
        assertFalse("jtools_install_plugin_from_url" in toolNames)
        assertFalse("jtools_get_env_vars" in toolNames)
        assertFalse("jtools_list_projects" in toolNames)
        assertFalse("jtools_get_project_info" in toolNames)
        assertFalse("jtools_read_directory" in toolNames)
    }

    @Test
    fun `get time returns requested timezone and formatted current time`() {
        val result = invokeTool(
            fakeProject(Files.createTempDirectory("agent-tool-registry-time").toString()),
            "jtools_get_current_time",
            """{"timezone":"Asia/Shanghai"}"""
        )

        assertEquals(true, result["ok"])
        assertEquals("Asia/Shanghai", result["queryTimeZone"])
        assertNotNull(result["systemTimeZone"])
        assertTrue((result["time"] as String).matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }

    @Test
    fun `get time rejects invalid timezone`() {
        val result = invokeTool(
            fakeProject(Files.createTempDirectory("agent-tool-registry-time-invalid").toString()),
            "jtools_get_current_time",
            """{"timezone":"Invalid/Zone"}"""
        )

        assertEquals(false, result["ok"])
        assertTrue((result["error"] as String).contains("无效时区"))
    }

    private fun registerJtools(project: Project): List<AgentTool> {
        val registry = AgentToolRegistry::class.java.getDeclaredConstructor(
            List::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java,
            Map::class.java
        ).apply {
            isAccessible = true
        }.newInstance(
            emptyList<AgentTool>(),
            emptyMap<String, AgentTool>(),
            emptyMap<String, List<AgentTool>>(),
            emptyMap<String, PluginInfo>(),
            emptyMap<String, List<PluginInfo>>()
        ) as AgentToolRegistry

        val tools = mutableListOf<AgentTool>()
        AgentToolRegistry.Companion::class.java.getDeclaredMethod(
            "registerJtoolsFunctions",
            Project::class.java,
            AgentToolRegistry::class.java,
            PluginInfo::class.java,
            Function1::class.java
        ).apply {
            isAccessible = true
        }.invoke(
            AgentToolRegistry.Companion,
            project,
            registry,
            PluginInfo("system", "<internal>", "system", "test", 0L, "system"),
            { tool: AgentTool -> tools += tool }
        )
        return tools
    }

    private fun registerToolSurface(project: Project): List<AgentTool> {
        val tools = registerJtools(project).toMutableList()
        val pluginInfo = PluginInfo("system", "<internal>", "system", "test", 0L, "system")
        registerCompanionTools(
            "registerSkillManagementTools",
            arrayOf(Project::class.java, PluginInfo::class.java, Function1::class.java),
            project,
            pluginInfo,
            tools
        )
        registerCompanionTools(
            "registerMcpManagementTools",
            arrayOf(Project::class.java, PluginInfo::class.java, Function1::class.java),
            project,
            pluginInfo,
            tools
        )
        return tools
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeTool(project: Project, name: String, args: String): Map<String, Any?> {
        val tool = registerJtools(project).first { it.name == name }
        val payload = JsonParser.parseString(tool.call(args)).asJsonObject
        return payload.entrySet().associate { entry ->
            entry.key to when {
                entry.value.isJsonNull -> null
                entry.value.isJsonPrimitive -> {
                    val primitive = entry.value.asJsonPrimitive
                    when {
                        primitive.isBoolean -> primitive.asBoolean
                        primitive.isNumber -> primitive.asNumber
                        else -> primitive.asString
                    }
                }
                else -> gsonToAny(entry.value)
            }
        }
    }

    private fun gsonToAny(element: com.google.gson.JsonElement): Any? {
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> primitive.asNumber
                    else -> primitive.asString
                }
            }
            element.isJsonArray -> element.asJsonArray.map(::gsonToAny)
            element.isJsonObject -> element.asJsonObject.entrySet().associate { it.key to gsonToAny(it.value) }
            else -> null
        }
    }

    private fun registerCompanionTools(
        methodName: String,
        parameterTypes: Array<Class<*>>,
        project: Project,
        pluginInfo: PluginInfo,
        tools: MutableList<AgentTool>,
    ) {
        AgentToolRegistry.Companion::class.java.getDeclaredMethod(methodName, *parameterTypes).apply {
            isAccessible = true
        }.invoke(
            AgentToolRegistry.Companion,
            project,
            pluginInfo,
            { tool: AgentTool -> tools += tool }
        )
    }

    private fun fakeProject(basePath: String): Project {
        lateinit var proxy: Project
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getBasePath" -> basePath
                "isDisposed" -> false
                "getName" -> "test"
                "toString" -> "FakeProject($basePath)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }
        proxy = Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
            handler
        ) as Project
        return proxy
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }

}
