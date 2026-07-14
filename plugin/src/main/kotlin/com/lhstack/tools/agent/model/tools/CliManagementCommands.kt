package com.lhstack.tools.agent.model.tools

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.lhstack.tools.agent.AgentRunReceiver
import com.lhstack.tools.agent.AwakeRemoteModelService
import com.lhstack.tools.agent.AgentRunService
import com.lhstack.tools.agent.AgentRunStartRequest
import com.lhstack.tools.db.config.AgentCapabilityConfig
import com.lhstack.tools.db.config.AgentDistillConfig
import com.lhstack.tools.db.config.AgentRuntimeConfig
import com.lhstack.tools.db.entity.ModelEntity
import com.lhstack.tools.db.entity.ProviderEntity
import com.lhstack.tools.db.service.AgentRecord
import com.lhstack.tools.db.service.AgentService
import com.lhstack.tools.db.service.CatalogService

internal object CliManagementCommands {
    private val gson = Gson()
    private val stringListType = TypeToken.getParameterized(List::class.java, String::class.java).type

    fun execute(command: String, args: JsonObject): JsonElement? = when (command) {
        "provider.list" -> json(CatalogService.listProviders().map(::providerMap))
        "provider.get" -> json(providerMap(requireProvider(args.long("id"))))
        "provider.remote_models" -> json(
            AwakeRemoteModelService.listModels(requireProvider(args.long("id"))).map { modelId ->
                mapOf("id" to modelId, "display_name" to modelId)
            }
        )
        "provider.create" -> json(providerMap(CatalogService.saveProvider(provider(args))))
        "provider.update" -> json(providerMap(CatalogService.saveProvider(updateProvider(args))))
        "provider.delete" -> deleted { CatalogService.deleteProvider(args.long("id")) }
        "model.list" -> json(CatalogService.listModelsByProvider(args.long("provider_id")).map(::modelMap))
        "model.get" -> json(modelMap(requireModel(args.long("id"))))
        "model.create" -> json(modelMap(CatalogService.saveModel(model(args))))
        "model.update" -> json(modelMap(CatalogService.saveModel(updateModel(args))))
        "model.delete" -> deleted { CatalogService.deleteModel(args.long("id")) }
        "agent.list" -> json(AgentService.listAgents().map(::agentMap))
        "agent.get" -> json(agentMap(requireAgent(args.long("id"))))
        "agent.create" -> {
            val value = agent(args)
            validateAgentCatalog(value)
            json(agentMap(requireAgent(AgentService.upsertAgent(value))))
        }
        "agent.update" -> {
            val value = updateAgent(args)
            validateAgentCatalog(value)
            json(agentMap(requireAgent(AgentService.upsertAgent(value))))
        }
        "agent.delete" -> deleted { AgentService.deleteAgent(args.long("id")) }
        "agent.run" -> {
            val snapshot = AgentRunService.start(AgentRunStartRequest(
                agentId = args.long("agent_id"),
                prompt = args.string("prompt"),
                projectPath = args.string("project"),
                sessionId = args.long("session_id"),
                receiver = AgentRunReceiver.from(args.string("receiver")),
            ))
            JsonObject().apply {
                addProperty("run_id", snapshot.runId)
                addProperty("status", snapshot.status)
                addProperty("session_id", snapshot.sessionId)
                addProperty("receiver", snapshot.receiver)
            }
        }
        "agent.run.get" -> json(AgentRunService.get(args.string("run_id")))
        else -> null
    }

    private fun provider(args: JsonObject) = ProviderEntity().apply {
        name = args.string("name")
        kind = args.string("kind")
        apiKey = args.optionalString("api_key")
        baseUrl = args.optionalString("base_url")
        api = args.optionalString("api")
        anthropicVersion = args.optionalString("anthropic_version")
        providerConfig = args.jsonObjectOrEmpty("provider_config").toString()
        enabled = if (args.boolean("enabled", true)) 1 else 0
        validateProvider(this)
    }

    private fun updateProvider(args: JsonObject): ProviderEntity = requireProvider(args.long("id")).also { value ->
        args.optionalString("name")?.let { value.name = it }
        args.optionalString("kind")?.let { value.kind = it }
        if (args.has("api_key")) value.apiKey = args.nullableString("api_key")
        if (args.has("base_url")) value.baseUrl = args.nullableString("base_url")
        if (args.has("api")) value.api = args.nullableString("api")
        if (args.has("anthropic_version")) value.anthropicVersion = args.nullableString("anthropic_version")
        args.get("provider_config")?.let { value.providerConfig = it.requireObject("provider_config").toString() }
        if (args.has("enabled")) value.enabled = if (args.boolean("enabled", true)) 1 else 0
        validateProvider(value)
    }

    private fun validateProvider(value: ProviderEntity) {
        require(value.name.isNotBlank()) { "供应商名称不能为空" }
        require(value.kind in setOf("open_ai", "openai", "open_ai_compatible", "anthropic")) { "供应商 kind 无效" }
    }

    private fun model(args: JsonObject) = ModelEntity().apply {
        providerId = args.long("provider_id")
        requireProvider(providerId)
        alias = args.string("alias")
        modelId = args.string("model_id")
        displayName = args.optionalString("display_name")
        api = args.optionalString("api")
        contextWindow = args.optionalLong("context_window")
        modalities = args.jsonArrayOrDefault("modalities", "[\"text\"]")
        modelParams = args.jsonTextOrNull("model_params")
        executionParams = args.jsonTextOrNull("execution_params")
        additionalParams = args.jsonTextOrNull("additional_params")
        enabled = if (args.boolean("enabled", true)) 1 else 0
        validateModel(this)
    }

    private fun updateModel(args: JsonObject): ModelEntity = requireModel(args.long("id")).also { value ->
        args.optionalLong("provider_id")?.let { requireProvider(it); value.providerId = it }
        args.optionalString("alias")?.let { value.alias = it }
        args.optionalString("model_id")?.let { value.modelId = it }
        if (args.has("display_name")) value.displayName = args.nullableString("display_name")
        if (args.has("api")) value.api = args.nullableString("api")
        if (args.has("context_window")) value.contextWindow = args.optionalLong("context_window")
        args.get("modalities")?.let { value.modalities = it.requireArray("modalities").toString() }
        if (args.has("model_params")) value.modelParams = args.jsonTextOrNull("model_params")
        if (args.has("execution_params")) value.executionParams = args.jsonTextOrNull("execution_params")
        if (args.has("additional_params")) value.additionalParams = args.jsonTextOrNull("additional_params")
        if (args.has("enabled")) value.enabled = if (args.boolean("enabled", true)) 1 else 0
        validateModel(value)
    }

    private fun validateModel(value: ModelEntity) {
        require(value.alias.isNotBlank()) { "模型别名不能为空" }
        require(value.modelId.isNotBlank()) { "模型 ID 不能为空" }
    }

    private fun agent(args: JsonObject): AgentRecord = AgentRecord(
        id = null,
        name = args.string("name"),
        description = args.optionalString("description"),
        enabled = args.boolean("enabled", true),
        providerId = args.optionalLong("provider_id"),
        modelId = args.optionalLong("model_id"),
        promptId = args.optionalLong("prompt_id"),
        extraPrompt = args.optionalString("extra_prompt"),
        runtimeParams = args.config("runtime_params", AgentRuntimeConfig::class.java, AgentRuntimeConfig()),
        extConfig = args.config("ext_config", AgentCapabilityConfig::class.java, AgentCapabilityConfig()),
        distillConfig = args.config("distill_config", AgentDistillConfig::class.java, AgentDistillConfig()),
        outputMode = args.optionalString("output_mode") ?: "text",
        maxRuntimeSecs = args.optionalLong("max_runtime_secs"),
        tags = args.stringList("tags"),
        createdAt = null,
        updatedAt = null,
    )

    private fun updateAgent(args: JsonObject): AgentRecord {
        val value = requireAgent(args.long("id"))
        return value.copy(
            name = args.optionalString("name") ?: value.name,
            description = if (args.has("description")) args.nullableString("description") else value.description,
            enabled = if (args.has("enabled")) args.boolean("enabled", true) else value.enabled,
            providerId = if (args.has("provider_id")) args.optionalLong("provider_id") else value.providerId,
            modelId = if (args.has("model_id")) args.optionalLong("model_id") else value.modelId,
            promptId = if (args.has("prompt_id")) args.optionalLong("prompt_id") else value.promptId,
            extraPrompt = if (args.has("extra_prompt")) args.nullableString("extra_prompt") else value.extraPrompt,
            runtimeParams = if (args.has("runtime_params")) args.config("runtime_params", AgentRuntimeConfig::class.java, value.runtimeParams) else value.runtimeParams,
            extConfig = if (args.has("ext_config")) args.config("ext_config", AgentCapabilityConfig::class.java, value.extConfig) else value.extConfig,
            distillConfig = if (args.has("distill_config")) args.config("distill_config", AgentDistillConfig::class.java, value.distillConfig) else value.distillConfig,
            outputMode = args.optionalString("output_mode") ?: value.outputMode,
            maxRuntimeSecs = if (args.has("max_runtime_secs")) args.optionalLong("max_runtime_secs") else value.maxRuntimeSecs,
            tags = if (args.has("tags")) args.stringList("tags") else value.tags,
        )
    }

    private fun validateAgentCatalog(value: AgentRecord) {
        val providerId = value.providerId ?: return
        requireProvider(providerId)
        value.modelId?.let { modelId ->
            val model = requireModel(modelId)
            require(model.providerId == providerId) { "模型 `$modelId` 不属于供应商 `$providerId`" }
        }
    }

    private fun providerMap(v: ProviderEntity) = linkedMapOf(
        "id" to v.id, "name" to v.name, "kind" to v.kind, "api_key" to v.apiKey,
        "base_url" to v.baseUrl, "api" to v.api, "anthropic_version" to v.anthropicVersion,
        "provider_config" to JsonParser.parseString(v.providerConfig), "enabled" to (v.enabled != 0),
    )

    private fun modelMap(v: ModelEntity) = linkedMapOf(
        "id" to v.id, "provider_id" to v.providerId, "alias" to v.alias, "model_id" to v.modelId,
        "display_name" to v.displayName, "api" to v.api, "context_window" to v.contextWindow,
        "modalities" to JsonParser.parseString(v.modalities), "model_params" to parseNullable(v.modelParams),
        "execution_params" to parseNullable(v.executionParams), "additional_params" to parseNullable(v.additionalParams),
        "enabled" to (v.enabled != 0),
    )

    private fun agentMap(v: AgentRecord) = linkedMapOf(
        "id" to v.id, "name" to v.name, "description" to v.description, "enabled" to v.enabled,
        "provider_id" to v.providerId, "model_id" to v.modelId, "prompt_id" to v.promptId,
        "extra_prompt" to v.extraPrompt, "runtime_params" to v.runtimeParams, "ext_config" to v.extConfig,
        "distill_config" to v.distillConfig, "output_mode" to v.outputMode,
        "max_runtime_secs" to v.maxRuntimeSecs, "tags" to v.tags,
    )

    private fun requireProvider(id: Long) = CatalogService.providerById(id)
        ?: throw IllegalArgumentException("供应商 `$id` 不存在")
    private fun requireModel(id: Long) = CatalogService.modelById(id)
        ?: throw IllegalArgumentException("模型 `$id` 不存在")
    private fun requireAgent(id: Long) = AgentService.agentById(id)
        ?: throw IllegalArgumentException("Agent `$id` 不存在")

    private fun deleted(action: () -> Unit): JsonObject = JsonObject().apply { action(); addProperty("deleted", true) }
    private fun json(value: Any?): JsonElement = gson.toJsonTree(value)
    private fun parseNullable(value: String?): JsonElement = value?.let { JsonParser.parseString(it) } ?: JsonNull.INSTANCE

    private fun JsonObject.string(name: String) = optionalString(name)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("缺少参数 `$name`")
    private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asString
    private fun JsonObject.nullableString(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asString
    private fun JsonObject.long(name: String) = optionalLong(name) ?: throw IllegalArgumentException("缺少参数 `$name`")
    private fun JsonObject.optionalLong(name: String): Long? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asLong
    private fun JsonObject.boolean(name: String, default: Boolean) = get(name)?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: default
    private fun JsonObject.jsonObjectOrEmpty(name: String) = get(name)?.requireObject(name) ?: JsonObject()
    private fun JsonObject.jsonArrayOrDefault(name: String, default: String) = get(name)?.requireArray(name)?.toString() ?: default
    private fun JsonObject.jsonTextOrNull(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.toString()
    private fun JsonElement.requireObject(name: String) = takeIf(JsonElement::isJsonObject)?.asJsonObject ?: throw IllegalArgumentException("$name 必须是 object")
    private fun JsonElement.requireArray(name: String) = takeIf(JsonElement::isJsonArray)?.asJsonArray ?: throw IllegalArgumentException("$name 必须是 array")
    private fun <T> JsonObject.config(name: String, type: Class<T>, default: T): T = get(name)?.let { gson.fromJson(it, type) } ?: default
    private fun JsonObject.stringList(name: String): List<String> = get(name)?.let { gson.fromJson<List<String>>(it, stringListType) } ?: emptyList()
}
