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
            val outside = Files.createTempFile("jtools-unauthorized", ".png").toFile()
            try {
                assertFailsWith<IllegalArgumentException> {
                    tool.executeBlocking(
                        JsonObject().apply {
                            add(
                                "resources",
                                JsonArray().apply {
                                    add(JsonObject().apply { addProperty("path", outside.path) })
                                },
                            )
                        }
                    )
                }
            } finally {
                outside.delete()
            }
        } finally {
            root.deleteRecursively()
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
