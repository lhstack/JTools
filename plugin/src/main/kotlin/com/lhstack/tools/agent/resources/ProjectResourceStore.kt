package com.lhstack.tools.agent.resources

import com.google.gson.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Only this boundary reads/writes project catalogs. No database mirror or parent lookup. */
class ProjectResourceStore(projectRoot: File) {
    private val root = projectRoot.canonicalFile
    private val directory = File(root, ".jtools")

    init {
        require(root.isDirectory) { "项目目录不存在: $root" }
    }

    fun skills() =
        com.lhstack.tools.db.service.ResourceConfigService(checked(File(directory, "skills")))

    fun read(file: String, collection: String): JsonObject {
        require(
            (file == "mcpServer.json" && collection == "mcpServers") ||
                (file == "subAgents.json" && collection == "subAgents")
        ) {
            "未知项目资源文件"
        }
        val target = checked(File(directory, file))
        if (!target.exists()) return JsonObject()
        return parse(target.readText(Charsets.UTF_8), target.path, collection)
    }

    fun update(file: String, collection: String, action: (JsonObject) -> Unit) {
        require(
            (file == "mcpServer.json" && collection == "mcpServers") ||
                (file == "subAgents.json" && collection == "subAgents")
        ) {
            "未知项目资源文件"
        }
        val target = checked(File(directory, file))
        synchronized(locks.computeIfAbsent(target.path) { Any() }) {
            val original = if (target.exists()) target.readBytes() else null
            val entries =
                if (original == null) JsonObject()
                else parse(original.toString(Charsets.UTF_8), target.path, collection)
            action(entries)
            val document =
                JsonObject().apply {
                    addProperty("version", 1)
                    add(collection, entries)
                }
            checked(directory).mkdirs()
            val temporary = Files.createTempFile(directory.toPath(), ".$file-", ".tmp")
            try {
                Files.writeString(
                    temporary,
                    GsonBuilder().setPrettyPrinting().create().toJson(document) + "\n",
                )
                val current = if (target.exists()) target.readBytes() else null
                check(
                    (original == null && current == null) ||
                        (original != null && current != null && original.contentEquals(current))
                ) {
                    "配置已被外部修改，请重新加载: $target"
                }
                Files.move(
                    temporary,
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    private fun checked(file: File): File =
        file.canonicalFile.also {
            require(
                it.toPath().startsWith(root.toPath()) &&
                    it.toPath().startsWith(directory.canonicalFile.toPath())
            ) {
                "项目资源路径越界: $file"
            }
            require(directory.canonicalFile.toPath().startsWith(root.toPath())) {
                ".jtools 不能链接到项目外部"
            }
        }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()

        private fun readUnique(reader: com.google.gson.stream.JsonReader): JsonElement =
            when (reader.peek()) {
                com.google.gson.stream.JsonToken.BEGIN_OBJECT ->
                    JsonObject().apply {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            require(!has(name)) { "重复 JSON 字段: $name" }
                            add(name, readUnique(reader))
                        }
                        reader.endObject()
                    }
                com.google.gson.stream.JsonToken.BEGIN_ARRAY ->
                    JsonArray().apply {
                        reader.beginArray()
                        while (reader.hasNext()) add(readUnique(reader))
                        reader.endArray()
                    }
                else -> JsonParser.parseReader(reader)
            }

        fun parse(text: String, location: String, collection: String): JsonObject {
            try {
                val reader = com.google.gson.stream.JsonReader(java.io.StringReader(text))
                reader.setStrictness(com.google.gson.Strictness.STRICT)
                val value = readUnique(reader)
                require(reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT) {
                    "JSON 包含尾随内容"
                }
                require(value.isJsonObject) { "必须是 JSON object" }
                val obj = value.asJsonObject
                require(obj.keySet() == setOf("version", collection)) {
                    "仅允许 version、$collection 字段"
                }
                require(
                    obj.get("version").isJsonPrimitive &&
                        obj.get("version").asJsonPrimitive.isNumber &&
                        obj.get("version").asString == "1"
                ) {
                    "version 必须为 1"
                }
                require(obj.get(collection).isJsonObject) { "$collection 必须是 object" }
                val entries = obj.getAsJsonObject(collection)
                entries.keySet().forEach {
                    require(it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*"))) { "资源 key 无效: $it" }
                }
                entries.entrySet().forEach { (key, item) ->
                    require(item.isJsonObject) { "$key 必须是 object" }
                }
                return entries
            } catch (e: Exception) {
                throw IllegalArgumentException("项目配置 $location 无效: ${e.message}", e)
            }
        }
    }
}
