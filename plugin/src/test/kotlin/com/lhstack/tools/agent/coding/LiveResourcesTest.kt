package com.lhstack.tools.agent.coding

import com.google.gson.*
import com.lhstack.tools.agent.resources.*
import com.lhstack.tools.db.service.*
import java.nio.file.Files
import kotlin.test.*

class LiveResourcesTest {
    @Test
    fun `same cli instance sees create update delete and authorization changes immediately`() {
        val root = Files.createTempDirectory("jtools-live-resources").toFile()
        val global = root.resolve("global-skills").apply { mkdirs() }
        try {
            var allowed = true
            val resources = ScopedResources(root)
            val cli =
                ScopedCliCommands(root, null) {
                    val servers =
                        if (allowed)
                            resources.mcps("project").associate { row ->
                                "project:mcp:${row.get("id").asString}" to
                                    resources.mcp("project", row.get("id").asString)
                            }
                        else emptyMap()
                    RuntimeResourceSnapshot(
                        listOf(
                            SkillSource("global", global, emptySet()),
                            SkillSource(
                                "project",
                                root.resolve(".jtools/skills"),
                                resources.skills("project").listSkills().map { it.name }.toSet(),
                            ),
                        ),
                        emptyList(),
                        servers,
                        emptyMap(),
                    )
                }
            fun run(command: String, args: String = "{}") =
                cli.execute(command, JsonParser.parseString(args).asJsonObject)!!
            assertEquals(0, run("mcp.list").asJsonArray.size())
            run(
                "mcp.create",
                """{"scope":"project","name":"docs","transport":"stdio","command":"python3","args":[]}""",
            )
            assertEquals(
                "project",
                run("mcp.list").asJsonArray.single().asJsonObject.get("scope").asString,
            )
            assertEquals(
                "python3",
                run("mcp.get", """{"scope":"project","id":"docs"}""")
                    .asJsonObject
                    .get("command")
                    .asString,
            )
            run("mcp.update", """{"scope":"project","id":"docs","command":"node"}""")
            assertEquals(
                "node",
                run("mcp.get", """{"scope":"project","id":"docs"}""")
                    .asJsonObject
                    .get("command")
                    .asString,
            )
            allowed = false
            assertEquals(0, run("mcp.list").asJsonArray.size())
            assertFailsWith<IllegalStateException> {
                run("mcp.get", """{"scope":"project","id":"docs"}""")
            }
            allowed = true
            run("mcp.delete", """{"scope":"project","id":"docs"}""")
            assertEquals(0, run("mcp.list").asJsonArray.size())
            assertFailsWith<IllegalStateException> {
                run("mcp.get", """{"scope":"project","id":"docs"}""")
            }
            run("skill.create", """{"scope":"project","name":"review","description":"review"}""")
            assertEquals(
                "project",
                run("skill.list").asJsonArray.single().asJsonObject.get("scope").asString,
            )
            run("skill.delete", """{"scope":"project","name":"review"}""")
            assertEquals(0, run("skill.list").asJsonArray.size())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `subagent history keeps scoped identity completed runs tools and latest run count`() {
        fun event(
            id: Long,
            parent: Long?,
            type: MessageEventType,
            context: String,
            status: MessageEventStatus = MessageEventStatus.COMPLETED,
        ) =
            MessageEventRecord(
                id,
                parent,
                1,
                "turn",
                status,
                type,
                "event-$id",
                "",
                JsonParser.parseString(context).asJsonObject,
                1,
                null,
                null,
            )
        val events =
            listOf(
                event(
                    1,
                    null,
                    MessageEventType.SUBAGENT,
                    """{"agent_id":"project:agent:review","task":"task one","result":{"output":"done one"}}""",
                ),
                event(
                    2,
                    1,
                    MessageEventType.TOOL_CALL,
                    """{"name":"read_project_files","arguments":{},"call_id":"tool-2","result":"read result","extra":{"type":"subagent"}}""",
                ),
                event(
                    3,
                    1,
                    MessageEventType.MODEL_REPLY,
                    """{"response":"done one","extra":{"type":"subagent"}}""",
                ),
                event(
                    4,
                    null,
                    MessageEventType.SUBAGENT,
                    """{"agent_id":"global:agent:review","task":"foreign task","result":{"output":"foreign"}}""",
                ),
                event(
                    5,
                    null,
                    MessageEventType.SUBAGENT,
                    """{"agent_id":"project:agent:review","task":"task two","result":{"output":"done two"}}""",
                ),
                event(
                    6,
                    null,
                    MessageEventType.SUBAGENT,
                    """{"agent_id":"project:agent:review","task":"running"}""",
                    MessageEventStatus.RUNNING,
                ),
            )
        val all =
            Gson()
                .toJson(SubagentHistorySupport.select(events, "project:agent:review", "turn", null))
        assertTrue(
            all.contains("task one") &&
                all.contains("task two") &&
                all.contains("read_project_files") &&
                all.contains("read result")
        )
        assertFalse(all.contains("foreign") || all.contains("running"))
        val latest =
            Gson().toJson(SubagentHistorySupport.select(events, "project:agent:review", "turn", 1))
        assertTrue(latest.contains("task two"))
        assertFalse(latest.contains("task one"))
    }
}
