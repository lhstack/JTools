package com.lhstack.tools.db.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.db.AgentDatabase
import com.lhstack.tools.db.entity.McpServerEntity
import com.lhstack.tools.db.mapper.McpServerMapper

object McpService {
    fun list(): List<McpServerEntity> = AgentDatabase.execute { session ->
        session.getMapper(McpServerMapper::class.java).selectList(null)
            .sortedBy { it.id }
    }

    fun get(id: Long): McpServerEntity? = AgentDatabase.execute { session ->
        session.getMapper(McpServerMapper::class.java).selectById(id)
    }

    fun save(server: McpServerEntity): McpServerEntity = AgentDatabase.execute { session ->
        val mapper = session.getMapper(McpServerMapper::class.java)
        if (server.id == null) mapper.insert(server) else {
            require(mapper.selectById(server.id) != null) { "MCP 服务 `${server.id}` 不存在" }
            mapper.updateById(server)
        }
        server
    }

    fun delete(id: Long) = AgentDatabase.execute { session ->
        val affected = session.getMapper(McpServerMapper::class.java).deleteById(id)
        require(affected > 0) { "MCP 服务 `$id` 不存在" }
        Unit
    }

    fun stringList(json: String): List<String> {
        val value = JsonParser.parseString(json)
        require(value.isJsonArray) { "MCP args 必须是 JSON array" }
        return value.asJsonArray.map { it.asString }
    }

    fun stringMap(json: String, field: String): Map<String, String> {
        val value = JsonParser.parseString(json)
        require(value.isJsonObject) { "MCP $field 必须是 JSON object" }
        return value.asJsonObject.entrySet().associate { (key, item) -> key to item.asString }
    }

    fun map(server: McpServerEntity): JsonObject = JsonObject().apply {
        server.id?.let { addProperty("id", it) }
        addProperty("name", server.name)
        addProperty("enabled", server.enabled != 0)
        addProperty("transport", server.transport)
        server.command?.let { addProperty("command", it) }
        add("args", JsonParser.parseString(server.args))
        add("env", JsonParser.parseString(server.env))
        server.url?.let { addProperty("url", it) }
        add("headers", JsonParser.parseString(server.headers))
    }
}
