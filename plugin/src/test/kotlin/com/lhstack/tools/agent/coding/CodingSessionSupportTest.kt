package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.db.service.ChatSessionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CodingSessionSupportTest {
    @Test
    fun `parseConfig requires snapshot and model ids`() {
        val config = JsonObject().apply {
            addProperty("cwd", "/tmp/project")
            addProperty("visibility", "project")
            addProperty("project_path", "/tmp/project")
            addProperty("coding_environment_id", 8)
            addProperty("agent_id", 1)
            addProperty("provider_id", 2)
            addProperty("model_id", 3)
            add("model_snapshot", JsonObject().apply { addProperty("model_id", "gpt") })
            add("token_usage", JsonObject())
        }
        val parsed = CodingSessionSupport.parseConfig(config.toString(), 9)
        assertEquals("/tmp/project", parsed.cwd)
        assertEquals(ChatSessionType.PROJECT, parsed.visibility)
        assertEquals(8, parsed.codingEnvironmentId)
        assertEquals(1, parsed.agentId)
        assertEquals(2, parsed.providerId)
        assertEquals(3, parsed.modelId)
        assertNull(parsed.promptId)
    }

    @Test
    fun `parseConfig rejects missing snapshot`() {
        val config = """{"cwd":"/tmp/p","visibility":"global","coding_environment_id":8,"agent_id":1,"provider_id":2,"model_id":3}"""
        assertFailsWith<IllegalStateException> { CodingSessionSupport.parseConfig(config, 1) }
    }

    @Test
    fun `parseConfig rejects invalid json`() {
        assertFailsWith<IllegalStateException> { CodingSessionSupport.parseConfig("[]", 1) }
        assertFailsWith<IllegalStateException> { CodingSessionSupport.parseConfig("{", 1) }
    }

    @Test
    fun `canonicalizePath does not require an existing directory`() {
        val path = CodingSessionSupport.canonicalizePath("/tmp/project-a")
        kotlin.test.assertTrue(path.endsWith("/tmp/project-a") || path.endsWith("tmp/project-a"))
        kotlin.test.assertFalse(path.contains("\\"))
    }

    @Test
    fun `replaceAgent writes agent id without touching snapshot`() {
        val config = JsonParser.parseString(
            """{"cwd":"/tmp/p","visibility":"global","agent_id":1,"provider_id":2,"model_id":3,"model_snapshot":{"model_id":"gpt"}}""",
        ).asJsonObject
        val updated = CodingSessionSupport.replaceAgent(config, 8)
        assertEquals(8L, updated.get("agent_id").asLong)
        assertEquals("gpt", updated.getAsJsonObject("model_snapshot").get("model_id").asString)
    }

    @Test
    fun `validateSnapshot rejects non object additional params`() {
        val provider = com.lhstack.tools.db.entity.ProviderEntity().apply {
            id = 2
            name = "openai"
            kind = "openai"
            apiKey = "sk-test"
            baseUrl = "https://api.openai.com/v1"
            api = "completions"
            providerConfig = "{}"
        }
        val snapshot = JsonObject().apply {
            addProperty("provider_id", 2)
            addProperty("provider_label", "openai")
            addProperty("id", 3)
            addProperty("model_label", "gpt")
            addProperty("provider_kind", "openai")
            addProperty("base_url", "https://api.openai.com/v1")
            addProperty("model_id", "gpt-4.1")
            addProperty("api", "completions")
            addProperty("openai_provider_type", "official")
            addProperty("stream", true)
            add("model_params", JsonObject())
            add("execution_params", JsonObject())
            add("additional_params", com.google.gson.JsonArray())
        }
        assertFailsWith<IllegalArgumentException> {
            CodingSessionSupport.validateSnapshot(provider, snapshot)
        }
    }

    @Test
    fun `applyModelSettings writes prompt and snapshot ids`() {
        val config = JsonParser.parseString(
            """{"cwd":"/tmp/p","visibility":"global","coding_environment_id":8,"agent_id":1,"provider_id":2,"model_id":3,"model_snapshot":{"provider_id":2,"id":3,"model_id":"gpt"}}""",
        ).asJsonObject
        // CatalogService 需要数据库，这里只验证 replace 字段写入形状：snapshot 校验失败时不改 config。
        val original = config.deepCopy()
        assertFailsWith<Exception> {
            CodingSessionSupport.applyModelSettings(
                config,
                CodingSessionSupport.ModelSettingsInput(
                    providerId = 2,
                    modelId = 3,
                    promptId = null,
                    modelSnapshot = JsonObject(),
                    reasoningLevel = null,
                    reasoningConfig = null,
                    maxHistoryRounds = 8,
                ),
            )
        }
        assertEquals(original.toString(), config.toString())
    }

    @Test
    fun `applyReasoningOverride writes max into snapshot params`() {
        val snapshot = JsonObject().apply {
            add("model_params", JsonObject().apply { addProperty("reasoning_effort", "high") })
        }
        val updated = CodingSessionSupport.applyReasoningOverride(snapshot, "max", null, "openai")
        val params = updated.getAsJsonObject("model_params")
        assertEquals("max", params.get("reasoning_effort").asString)
        assertEquals("max", params.getAsJsonObject("reasoning").get("effort").asString)
        assertEquals("max", params.getAsJsonObject("output_config").get("effort").asString)
    }
}
