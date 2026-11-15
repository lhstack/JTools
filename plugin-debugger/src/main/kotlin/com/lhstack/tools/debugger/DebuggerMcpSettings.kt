package com.lhstack.tools.debugger

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Application configuration, not disposable IDE system/cache data. */
internal class DebuggerMcpSettings(private val file: Path) {
    data class Saved(val enabled: Boolean, val port: Int, val activeToken: String?, val clients: Map<String, String>)

    fun read(): Saved {
        if (!Files.exists(file)) return Saved(false, 0, null, emptyMap())
        val data = JsonParser.parseString(Files.readString(file)).asJsonObject
        val saved = Saved(data.get("enabled").asBoolean, data.get("port").asInt,
            data.get("active_token").takeUnless { it.isJsonNull }?.asString,
            data.getAsJsonObject("clients").entrySet().associate { it.key to it.value.asString })
        require(saved.port in 0..65535) { "MCP 配置端口无效" }
        require(saved.clients.all { it.key.isNotBlank() && it.value.isNotBlank() }) { "MCP 客户端配置无效" }
        require(saved.activeToken == null || saved.clients.containsKey(saved.activeToken)) { "MCP 当前令牌缺少对应身份" }
        require(!saved.enabled || (saved.port > 0 && saved.activeToken != null)) { "MCP 自动启动配置缺少端口或令牌" }
        return saved
    }

    fun write(saved: Saved) {
        Files.createDirectories(file.parent)
        val attributes = if (Files.getFileStore(file.parent).supportsFileAttributeView("posix"))
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
        val temporary = Files.createTempFile(file.parent, ".jtools-debugger-mcp-", ".tmp", *attributes)
        try {
            Files.writeString(temporary, com.google.gson.GsonBuilder().serializeNulls().create().toJson(mapOf("enabled" to saved.enabled, "port" to saved.port,
                "active_token" to saved.activeToken, "clients" to saved.clients)))
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
