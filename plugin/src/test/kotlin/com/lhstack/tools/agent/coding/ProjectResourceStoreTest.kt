package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import com.lhstack.tools.agent.resources.ProjectResourceStore
import com.lhstack.tools.agent.resources.ResourceSelection
import com.lhstack.tools.db.service.ResourceConfigService
import java.nio.file.Files
import kotlin.test.*

class ProjectResourceStoreTest {
    @Test
    fun `project catalog writes stay in bound project and preserve other entries`() {
        val a = Files.createTempDirectory("jtools-a").toFile()
        val b = Files.createTempDirectory("jtools-b").toFile()
        try {
            val store = ProjectResourceStore(a)
            store.update("mcpServer.json", "mcpServers") {
                it.add("a", JsonObject().apply { addProperty("transport", "stdio") })
            }
            store.update("mcpServer.json", "mcpServers") { it.add("b", JsonObject()) }
            assertEquals(setOf("a", "b"), store.read("mcpServer.json", "mcpServers").keySet())
            assertTrue(
                ProjectResourceStore(b).read("mcpServer.json", "mcpServers").entrySet().isEmpty()
            )
            assertFalse(b.resolve(".jtools").exists())
        } finally {
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }

    @Test
    fun `malformed duplicate and unknown project fields fail explicitly`() {
        for (text in
            listOf(
                "broken",
                "[]",
                "{\"version\":1,\"mcpServers\":{},\"extra\":true}",
                "{\"version\":1,\"mcpServers\":{\"a\":{},\"a\":{}}}",
                "{\"version\":1,\"mcpServers\":{\"../bad\":{}}}",
            )) {
            assertFailsWith<IllegalArgumentException> {
                ProjectResourceStore.parse(text, "test", "mcpServers")
            }
        }
    }

    @Test
    fun `project root cannot escape via symlink and missing files are not created on read`() {
        val a = Files.createTempDirectory("jtools-a").toFile()
        val b = Files.createTempDirectory("jtools-b").toFile()
        try {
            assertTrue(
                ProjectResourceStore(a).read("mcpServer.json", "mcpServers").entrySet().isEmpty()
            )
            assertFalse(a.resolve(".jtools").exists())
            Files.createSymbolicLink(a.resolve(".jtools").toPath(), b.toPath())
            assertFailsWith<IllegalArgumentException> { ProjectResourceStore(a).skills() }
        } finally {
            a.resolve(".jtools").delete()
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }

    @Test
    fun `skill stores with same name remain independent and reject traversal`() {
        val a = Files.createTempDirectory("jtools-skill-a").toFile()
        val b = Files.createTempDirectory("jtools-skill-b").toFile()
        try {
            val first = ResourceConfigService(a)
            val second = ResourceConfigService(b)
            first.createSkill("same", "first")
            second.createSkill("same", "second")
            first.writeSkillFile("same", "SKILL.md", "first changed")
            assertTrue(second.readSkillFile("same", null).content.contains("second"))
            assertFailsWith<IllegalArgumentException> {
                first.writeSkillFile("same", "../../outside", "bad")
            }
            first.deleteSkill("same")
            assertTrue(second.listSkills().isNotEmpty())
        } finally {
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }

    @Test
    fun `explicit exclusions win while inherited new resources follow policy`() {
        val overrides = ResourceSelection.fromLists(listOf("keep"), listOf("off"))
        assertEquals(
            setOf("keep", "new"),
            ResourceSelection.enabled(listOf("keep", "off", "new"), overrides, true),
        )
        assertEquals(
            setOf("keep"),
            ResourceSelection.enabled(listOf("keep", "off", "new"), overrides, false),
        )
    }

    @Test
    fun `scoped CLI mutates only project files and requires explicit scope`() {
        val root = Files.createTempDirectory("jtools-cli").toFile()
        try {
            val commands =
                com.lhstack.tools.agent.resources.ScopedCliCommands(root, null) {
                    com.lhstack.tools.agent.resources.RuntimeResourceSnapshot(
                        emptyList(),
                        emptyList(),
                        emptyMap(),
                        emptyMap(),
                    )
                }
            fun args(text: String) = com.google.gson.JsonParser.parseString(text).asJsonObject
            assertEquals(0, commands.execute("mcp.list", JsonObject())!!.asJsonArray.size())
            assertFailsWith<IllegalArgumentException> { commands.execute("mcp.get", JsonObject()) }
            commands.execute(
                "mcp.create",
                args(
                    """{"scope":"project","name":"docs","transport":"stdio","command":"python3","args":[],"env":{}}"""
                ),
            )
            assertTrue(root.resolve(".jtools/mcpServer.json").isFile)
            assertFailsWith<IllegalStateException> {
                commands.execute("mcp.get", args("""{"scope":"project","id":"docs"}"""))
            }
            // Runtime authorizations apply to reading and calling, even after creating a new
            // definition.
            assertFailsWith<IllegalArgumentException> {
                commands.execute(
                    "mcp.call",
                    args("""{"scope":"project","id":"docs","tool":"test"}"""),
                )
            }
            val management = com.lhstack.tools.agent.resources.ScopedCliCommands(root, null, null)
            assertEquals(
                "docs",
                management
                    .execute("mcp.get", args("""{"scope":"project","id":"docs"}"""))!!
                    .asJsonObject
                    .get("name")
                    .asString,
            )
            management.execute(
                "skill.create",
                args("""{"scope":"project","name":"docs","description":"project skill"}"""),
            )
            assertTrue(root.resolve(".jtools/skills/docs/SKILL.md").isFile)
            management.execute(
                "mcp.update",
                args("""{"scope":"project","id":"docs","args":["new"]}"""),
            )
            assertEquals(
                "new",
                management
                    .execute("mcp.get", args("""{"scope":"project","id":"docs"}"""))!!
                    .asJsonObject
                    .getAsJsonArray("args")[0]
                    .asString,
            )
            management.execute("mcp.delete", args("""{"scope":"project","id":"docs"}"""))
            assertTrue(
                ProjectResourceStore(root).read("mcpServer.json", "mcpServers").entrySet().isEmpty()
            )
            assertFailsWith<IllegalArgumentException> {
                management.execute(
                    "mcp.create",
                    args(
                        """{"scope":"project","name":"bad","transport":"stdio","command":"x","enabled":false}"""
                    ),
                )
            }
            assertEquals(
                setOf("mcpServer.json", "skills"),
                root.resolve(".jtools").list()!!.toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scoped skill runtime returns both same-name sources and reads only requested scope`() {
        val root = Files.createTempDirectory("jtools-skill-runtime").toFile()
        try {
            val global = ResourceConfigService(root.resolve("global"))
            val local = ResourceConfigService(root.resolve("project"))
            global.createSkill("same", "global description")
            local.createSkill("same", "project description")
            val sources =
                listOf(
                    com.lhstack.tools.agent.resources.SkillSource(
                        "global",
                        global.skillsRootDir(),
                        setOf("same"),
                    ),
                    com.lhstack.tools.agent.resources.SkillSource(
                        "project",
                        local.skillsRootDir(),
                        setOf("same"),
                    ),
                )
            val workspace = com.lhstack.tools.agent.model.tools.WorkspaceTools(root.path)
            val list =
                com.lhstack.tools.agent.resources
                    .ScopedSkillsTool(false, workspace, sources)
                    .callJsonBlocking(JsonObject())
                    .asJsonObject
                    .getAsJsonArray("skills")
            assertEquals(2, list.size())
            assertEquals(
                setOf("global", "project"),
                list.map { it.asJsonObject.get("scope").asString }.toSet(),
            )
            val read = com.lhstack.tools.agent.resources.ScopedSkillsTool(true, workspace, sources)
            val result =
                read
                    .callJsonBlocking(
                        com.google.gson.JsonParser.parseString(
                            """{"scope":"project","name":"same"}"""
                        )
                    )
                    .asJsonObject
            assertTrue(result.get("content").asString.contains("project description"))
            assertFailsWith<IllegalArgumentException> {
                read.callJsonBlocking(com.google.gson.JsonParser.parseString("""{"name":"same"}"""))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `environment parser preserves inherited tool selection and include-new false`() {
        val entity =
            com.lhstack.tools.db.entity.CodingEnvironmentEntity().apply {
                id = 1
                name = "test"
                enabled = 0
                config =
                    """{"tools":{"bash":false},"tools_include_new":false,"resources":{"mcp":{"12":true},"mcp_include_new":false,"skills":{"keep":true},"skills_include_new":false}}"""
            }
        val record =
            com.lhstack.tools.db.service.CodingEnvironmentService.toRecordForContext(entity)
        assertFalse(record.enabled)
        assertFalse(record.config.toolsIncludeNew)
        assertEquals(mapOf("bash" to false), record.config.tools)
        assertTrue(
            com.lhstack.tools.db.service.CodingEnvironmentService.enabledTools(record.config)
                .isEmpty()
        )
        assertEquals(mapOf("12" to true), record.config.resources.mcp)
        assertFalse(record.config.resources.mcpIncludeNew)
        assertEquals(
            setOf("keep"),
            com.lhstack.tools.db.service.CodingEnvironmentService.enabledSkillNames(
                record.config,
                setOf("keep", "new"),
            ),
        )
    }

    @Test
    fun `project subagent has independent definition and source without database identity`() {
        val model =
            com.lhstack.tools.db.entity.ModelEntity().apply {
                id = 7
                providerId = 3
                alias = "test"
                enabled = 1
            }
        val definition =
            com.google.gson.JsonParser.parseString(
                    """{"name":"Reviewer","model":{"provider_id":3,"model_id":7},"system_prompt":"Read only","tools":["read_project_files"],"skills":[],"access_mode":"read_only","bash_enabled":false}"""
                )
                .asJsonObject
        val parsed =
            com.lhstack.tools.agent.resources.ProjectSubagent.parse(definition, "reviewer") {
                assertEquals(7L, it.asJsonObject.get("model_id").asLong)
                model
            }
        assertNull(parsed.agent.id)
        assertTrue(parsed.agent.enabled)
        assertEquals(7L, parsed.agent.modelId)
        val complete =
            definition.deepCopy().apply {
                add(
                    "runtime_params",
                    com.google.gson.JsonParser.parseString(
                        """{"include_history":true,"max_history_messages":3,"tool_call_retention_rounds":2}"""
                    ),
                )
                add(
                    "persona",
                    com.google.gson.JsonParser.parseString(
                        """{"soul":"Reviewer role","soul_max_chars":500}"""
                    ),
                )
                add(
                    "view_resources",
                    com.google.gson.JsonParser.parseString(
                        """{"image":{"enabled":false,"agent_id":null}}"""
                    ),
                )
                addProperty("max_runtime_secs", 3000)
            }
        val completeParsed =
            com.lhstack.tools.agent.resources.ProjectSubagent.parse(complete, "reviewer") { model }
        assertTrue(completeParsed.agent.runtimeParams.includeHistory)
        assertEquals(3, completeParsed.agent.runtimeParams.maxHistoryMessages)
        assertEquals(2, completeParsed.agent.runtimeParams.toolCallRetentionRounds)
        assertEquals("Reviewer role", completeParsed.agent.extConfig.persona.soul)
        assertEquals(3000L, completeParsed.agent.maxRuntimeSecs)
        assertTrue(
            com.lhstack.tools.agent.model.provider.AgentRuntime.personaPrompt(
                    completeParsed.agent.extConfig
                )
                .contains("Reviewer role")
        )
        assertFailsWith<IllegalArgumentException> {
            com.lhstack.tools.agent.resources.ProjectSubagent.parse(
                definition.deepCopy().apply { addProperty("model", "test") },
                "reviewer",
            ) {
                model
            }
        }
        val snapshot =
            com.lhstack.tools.agent.resources.RuntimeResourceSnapshot(
                emptyList(),
                emptyList(),
                emptyMap(),
                mapOf("reviewer" to parsed),
            )
        val tools =
            CodingSubagentTools.create(
                com.lhstack.tools.db.service.CodingEnvironmentConfig(),
                snapshot,
                1,
                "test-turn",
                System.getProperty("java.io.tmpdir"),
                null,
                {},
                null,
            )
        val listing =
            tools
                .first()
                .callJsonBlocking(com.google.gson.JsonParser.parseString("""{"scope":"project"}"""))
                .asJsonObject
                .getAsJsonArray("agents")
        assertEquals("project", listing[0].asJsonObject.get("scope").asString)
        assertEquals("reviewer", listing[0].asJsonObject.get("id").asString)
        assertEquals(
            0,
            tools
                .first()
                .callJsonBlocking(com.google.gson.JsonParser.parseString("""{"scope":"global"}"""))
                .asJsonObject
                .getAsJsonArray("agents")
                .size(),
        )
        val invalid = definition.deepCopy().apply { addProperty("enabled", false) }
        assertFailsWith<IllegalArgumentException> {
            com.lhstack.tools.agent.resources.ProjectSubagent.parse(invalid, "reviewer") { model }
        }
        val elevated = definition.deepCopy().apply { addProperty("bash_enabled", true) }
        assertFailsWith<IllegalArgumentException> {
            com.lhstack.tools.agent.resources.ProjectSubagent.parse(elevated, "reviewer") { model }
        }
    }

    @Test
    fun `project stdio MCP actually runs in bound project directory`() {
        val executable = java.io.File("/usr/bin/python3")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            executable.canExecute(),
            "requires system python3",
        )
        val root = Files.createTempDirectory("jtools-mcp-cwd").toFile()
        try {
            val script = root.resolve("server.py")
            script.writeText(
                """
                import sys, json, os
                for line in sys.stdin:
                    request=json.loads(line)
                    if 'id' not in request: continue
                    method=request['method']
                    if method=='initialize': result={'protocolVersion':request['params']['protocolVersion'],'capabilities':{'tools':{}},'serverInfo':{'name':'cwd-test','version':'1'}}
                    elif method=='tools/list': result={'tools':[{'name':'pwd','description':'Current working directory','inputSchema':{'type':'object','properties':{}}}]}
                    elif method=='tools/call': result={'content':[{'type':'text','text':os.getcwd()}],'isError':False}
                    else: result={}
                    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
                """
                    .trimIndent()
            )
            val server =
                com.lhstack.tools.db.entity.McpServerEntity().apply {
                    name = "cwd-test"
                    transport = "stdio"
                    command = executable.path
                    args = com.google.gson.Gson().toJson(listOf(script.path))
                }
            val result =
                com.lhstack.tools.agent.model.tools
                    .McpRuntime(server, 10, null, root.path)
                    .callTool("pwd", JsonObject())
            assertTrue(result.toString().contains(root.canonicalPath))
        } finally {
            root.deleteRecursively()
        }
    }
}
