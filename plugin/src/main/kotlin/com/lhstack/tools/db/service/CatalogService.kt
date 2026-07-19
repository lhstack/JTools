package com.lhstack.tools.db.service

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.ModelEntity
import com.lhstack.tools.db.entity.PromptTemplateEntity
import com.lhstack.tools.db.entity.ProviderEntity
import com.lhstack.tools.db.mapper.ModelMapper
import com.lhstack.tools.db.mapper.PromptTemplateMapper
import com.lhstack.tools.db.mapper.ProviderMapper
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.params.ModelParams

/**
 * 供应商 / 模型 / 提示词的持久化服务，对齐 awake-claw repository/catalog.rs。
 * 所有操作都在 AgentDatabase.execute 的事务边界内完成。
 */
data class ProviderModels(
    val provider: ProviderEntity,
    val models: List<ModelEntity>,
)

object CatalogService {

    // -------- providers --------

    fun listProviders(): List<ProviderEntity> = AgentDatabase.execute { session ->
        val mapper = session.getMapper(ProviderMapper::class.java)
        mapper.selectList(QueryWrapper<ProviderEntity>().orderByAsc("name", "id"))
    }

    fun providerById(id: Long): ProviderEntity? = AgentDatabase.execute { session ->
        session.getMapper(ProviderMapper::class.java).selectById(id)
    }

    fun saveProvider(provider: ProviderEntity): ProviderEntity = AgentDatabase.execute { session ->
        val mapper = session.getMapper(ProviderMapper::class.java)
        if (provider.id == null) {
            mapper.insert(provider)
        } else {
            mapper.updateById(provider)
        }
        provider
    }

    fun deleteProvider(id: Long) = AgentDatabase.execute { session ->
        // models 表通过外键 on delete cascade 关联，SQLite 需显式删子表以防外键未开启。
        session.getMapper(ModelMapper::class.java)
            .delete(QueryWrapper<ModelEntity>().eq("provider_id", id))
        session.getMapper(ProviderMapper::class.java).deleteById(id)
        Unit
    }

    // -------- models --------

    fun listModelsByProvider(providerId: Long): List<ModelEntity> = AgentDatabase.execute { session ->
        session.getMapper(ModelMapper::class.java)
            .selectList(QueryWrapper<ModelEntity>().eq("provider_id", providerId).orderByAsc("alias", "id"))
    }

    fun modelById(id: Long): ModelEntity? = AgentDatabase.execute { session ->
        session.getMapper(ModelMapper::class.java).selectById(id)
    }

    fun saveModel(model: ModelEntity): ModelEntity = AgentDatabase.execute { session ->
        require(session.getMapper(ProviderMapper::class.java).selectById(model.providerId) != null) {
            "供应商 `${model.providerId}` 不存在"
        }
        val modelParams = model.modelParams?.takeIf { it.isNotBlank() }?.let(JsonParser::parseString)
        ModelParams.validateContextBudget(
            model.contextWindow,
            ModelParams.configuredOutputTokens(modelParams),
        )
        val mapper = session.getMapper(ModelMapper::class.java)
        if (model.id == null) {
            mapper.insert(model)
        } else {
            mapper.updateById(model)
        }
        model
    }

    fun deleteModel(id: Long) = AgentDatabase.execute { session ->
        session.getMapper(ModelMapper::class.java).deleteById(id)
        Unit
    }

    /** 组合查询：所有供应商及其模型，对齐 awake ProviderModelsRecord 列表。 */
    fun listProvidersWithModels(): List<ProviderModels> = AgentDatabase.execute { session ->
        val providerMapper = session.getMapper(ProviderMapper::class.java)
        val modelMapper = session.getMapper(ModelMapper::class.java)
        val providers = providerMapper.selectList(
            QueryWrapper<ProviderEntity>().orderByAsc("name", "id")
        )
        providers.map { provider ->
            val models = modelMapper.selectList(
                QueryWrapper<ModelEntity>().eq("provider_id", provider.id).orderByAsc("alias", "id")
            )
            ProviderModels(provider, models)
        }
    }

    /** 运行时用：按 provider+model 取一对，校验模型确实属于该供应商。 */
    fun modelPair(providerId: Long, modelId: Long): Pair<ProviderEntity, ModelEntity>? =
        AgentDatabase.execute { session ->
            val provider = session.getMapper(ProviderMapper::class.java).selectById(providerId)
            val model = session.getMapper(ModelMapper::class.java).selectById(modelId)
            if (provider == null || model == null || model.providerId != provider.id) {
                null
            } else {
                provider to model
            }
        }

    // -------- prompt templates --------

    fun listPromptTemplates(): List<PromptTemplateEntity> = AgentDatabase.execute { session ->
        session.getMapper(PromptTemplateMapper::class.java)
            .selectList(QueryWrapper<PromptTemplateEntity>().orderByDesc("created_at").orderByAsc("name"))
    }

    fun promptTemplateById(id: Long): PromptTemplateEntity? = AgentDatabase.execute { session ->
        session.getMapper(PromptTemplateMapper::class.java).selectById(id)
    }

    fun savePromptTemplate(template: PromptTemplateEntity): PromptTemplateEntity = AgentDatabase.execute { session ->
        val mapper = session.getMapper(PromptTemplateMapper::class.java)
        if (template.id == null) {
            mapper.insert(template)
        } else {
            mapper.updateById(template)
        }
        template
    }

    fun deletePromptTemplate(id: Long) = AgentDatabase.execute { session ->
        session.getMapper(PromptTemplateMapper::class.java).deleteById(id)
        Unit
    }
}
