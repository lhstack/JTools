package com.lhstack.tools.agent.model.tools

import com.google.gson.*
import com.lhstack.tools.agent.AgentAttachmentState
import com.lhstack.tools.agent.AgentAttachmentSupport
import com.lhstack.tools.agent.model.params.*
import com.lhstack.tools.agent.model.provider.AgentRuntime
import com.lhstack.tools.db.config.*
import com.lhstack.tools.llm.*
import com.lhstack.tools.llm.provider.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class NativeResourceToolTest {
    private fun model(modalities: List<String>, api: OpenAiApi = OpenAiApi.COMPLETIONS) =
        ResolvedModelConfig(
            1,
            "P",
            1,
            "M",
            null,
            ProviderKind.OPEN_AI,
            "k",
            "https://example.test",
            null,
            modelId = "m",
            api = api,
            openaiProviderType = OpenAiProviderType.COMPATIBLE,
            stream = false,
            anthropicVersion = null,
            params = ModelRuntimeParams(modalities = modalities),
        )

    @Test
    fun `native takes priority independently per modality in agents and rejects invalid API`() {
        val config =
            AgentCapabilityConfig(
                viewResources =
                    AgentViewResourcesConfig(
                        image = AgentViewResourceRef(true, 77),
                        audio = AgentViewResourceRef(true, 88),
                    )
            )
        val request = AgentRuntime.Request(1, "task", workspace = "/tmp")
        val tools = AgentRuntime.resourceTools(config, request, model(listOf("image")))
        assertEquals(1, tools.count { it.definition("").name == "view_image" })
        assertIs<NativeResourceTool>(tools.single { it.definition("").name == "view_image" })
        assertIs<ViewResourceTool>(tools.single { it.definition("").name == "view_audio" })
        assertEquals(
            listOf("view_image"),
            AgentRuntime.resourceTools(AgentCapabilityConfig(), request, model(listOf("image")))
                .map { it.definition("").name },
        )
        assertFailsWith<IllegalArgumentException> {
            ResourceToolPlanner.nativeTools(
                model(listOf("audio"), OpenAiApi.RESPONSES),
                "/tmp",
                null,
            )
        }
    }

    @Test
    fun `image is metadata first then structured native bytes with durable summary only`() {
        val root = Files.createTempDirectory("jtools-native").toFile()
        try {
            val image = root.resolve("test.png")
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image)
            val tool = NativeResourceTool(ResourceKind.IMAGE, root.path, null)
            val three =
                JsonObject().apply {
                    add(
                        "resources",
                        JsonArray().apply {
                            repeat(3) {
                                add(JsonObject().apply { addProperty("path", "test.png") })
                            }
                        },
                    )
                }
            assertEquals(
                3,
                tool.executeBlocking(three).result.asJsonObject.getAsJsonArray("resources").size(),
            )
            val args = JsonParser.parseString("""{"resources":[{"path":"test.png"}]}""")
            val call = ProviderToolCall("call", "call", "view_image", args)
            val runtime =
                ToolRuntime(listOf(tool.definition("")), listOf(tool), ToolHook.NOOP, null)
            val batch = runtime.executeToolCalls(listOf(call))
            assertEquals(2, batch.currentMessages.size)
            assertTrue(
                (batch.currentMessages[1] as Message.User).content.any { it is UserContent.Image }
            )
            val history = ProviderMessageHistory.toJson(batch.historyMessages).toString()
            assertTrue(history.contains("resource_read_summary"))
            assertFalse(history.contains("base64"))
            assertFalse(history.contains("image_url"))
            val draft =
                AgentAttachmentState(
                    "1",
                    "test.png",
                    image.path,
                    "image/png",
                    image.length(),
                    "image",
                )
            assertTrue(
                AgentAttachmentSupport.toUserContents(draft, setOf("image")).all {
                    it is UserContent.Text
                }
            )
            val event =
                com.lhstack.tools.agent.coding.MessageEventRecord(
                    1,
                    null,
                    1,
                    "turn",
                    com.lhstack.tools.agent.coding.MessageEventStatus.COMPLETED,
                    com.lhstack.tools.agent.coding.MessageEventType.TOOL_CALL,
                    "call",
                    "",
                    JsonObject().apply {
                        addProperty("name", "view_image")
                        addProperty(
                            "result",
                            (batch.historyMessages[0] as Message.User)
                                .content
                                .filterIsInstance<UserContent.ToolResult>()
                                .single()
                                .toolResult
                                .content
                                .filterIsInstance<ToolResultContent.Text>()
                                .single()
                                .text,
                        )
                    },
                    1,
                    null,
                    null,
                )
            assertTrue(
                com.lhstack.tools.agent.coding.ResourceReplaySupport.restore(
                        listOf(event),
                        listOf(tool),
                    )
                    .isNotEmpty()
            )
            ImageIO.write(BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", image)
            assertFailsWith<IllegalArgumentException> {
                com.lhstack.tools.agent.coding.ResourceReplaySupport.restore(
                    listOf(event),
                    listOf(tool),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `native resources accept external absolute paths file URIs and attached IDs with no workspace restriction`() {
        val root = Files.createTempDirectory("jtools-native-paths-").toFile()
        val external = Files.createTempDirectory("jtools-external-paths-").toFile()
        try {
            val image = external.resolve("图片 space.png")
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image)
            val attachment = AgentAttachmentState("external-image", image.name, image.path, "image/png", image.length(), "image")
            fun args(key: String, value: String) = JsonObject().apply {
                add("resources", JsonArray().apply { add(JsonObject().apply { addProperty(key, value) }) })
            }
            val tool = NativeResourceTool(ResourceKind.IMAGE, root.path, null, listOf(attachment))
            val unregistered = NativeResourceTool(ResourceKind.IMAGE, root.path, null)
            for (path in listOf(image.absolutePath, image.toURI().toASCIIString())) {
                val result = unregistered.executeBlocking(args("path", path))
                assertEquals(1, result.resources.size)
                assertTrue(result.resources.single().content.any { it is UserContent.Image })
                val summary = result.result.asJsonObject.getAsJsonArray("resources").single().asJsonObject
                assertEquals(image.canonicalPath, summary["path"].asString)
            }
            val byId = tool.executeBlocking(args("attachment_id", "external-image"))
            assertEquals("external-image", byId.result.asJsonObject.getAsJsonArray("resources").single().asJsonObject["attachment_id"].asString)
            assertEquals(unregistered.executeBlocking(args("path", image.path)).resources.single().identity, byId.resources.single().identity)
            assertFailsWith<IllegalStateException> { tool.executeBlocking(args("attachment_id", "other-session-id")) }
            val both = args("path", image.path)
            both.getAsJsonArray("resources")[0].asJsonObject.addProperty("attachment_id", "external-image")
            assertFailsWith<IllegalArgumentException> { tool.executeBlocking(both) }
            assertEquals(image.canonicalFile, com.lhstack.tools.agent.resources.LocalResourceReferenceSupport.resolve(image.path, root.path).file)
            val relativeEscape = root.toPath().relativize(image.toPath()).toString()
            assertEquals(byId.resources.single().identity, unregistered.executeBlocking(args("path", relativeEscape)).resources.single().identity)
            assertFailsWith<IllegalArgumentException> { unregistered.executeBlocking(args("path", external.path)) }
            assertFailsWith<IllegalArgumentException> { unregistered.executeBlocking(args("path", image.path + ".missing")) }
            assertFailsWith<IllegalArgumentException> { unregistered.executeBlocking(args("path", "https://example.test/image.png")) }
            assertFailsWith<IllegalArgumentException> { NativeResourceTool(ResourceKind.AUDIO, root.path, null).executeBlocking(args("path", image.path)) }
            val oversized = external.resolve("oversized.png")
            java.io.RandomAccessFile(oversized, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
            assertFailsWith<IllegalArgumentException> { unregistered.executeBlocking(args("path", oversized.path)) }
        } finally {
            root.deleteRecursively()
            external.deleteRecursively()
        }
    }

    @Test
    fun `all native and delegated resource tools reject four inputs before reading or model execution`() {
        for (kind in ResourceKind.entries) {
            val native = NativeResourceTool(kind, "/tmp", null)
            assertEquals(
                3,
                native
                    .definition("")
                    .parameters
                    .asJsonObject
                    .getAsJsonObject("properties")
                    .getAsJsonObject("resources")
                    .get("maxItems")
                    .asInt,
            )
            val args =
                JsonParser.parseString(
                    """{"resources":[{"path":"one"},{"path":"two"},{"path":"three"},{"path":"four"}]}"""
                )
            assertFailsWith<IllegalArgumentException> { native.executeBlocking(args) }
            val delegated = ViewResourceTool(kind, 99, "/tmp", null, null)
            assertTrue(delegated.definition("").description.contains("最多 3"))
            assertFailsWith<IllegalArgumentException> {
                delegated.callJsonBlocking(
                    JsonParser.parseString(
                        """{"path":"one","paths":["two","three","four"],"prompt":"inspect"}"""
                    )
                )
            }
        }
    }
}
