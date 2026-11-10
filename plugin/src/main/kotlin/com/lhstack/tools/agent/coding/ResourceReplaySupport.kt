package com.lhstack.tools.agent.coding

import com.google.gson.*
import com.lhstack.tools.agent.model.tools.NativeResourceTool
import com.lhstack.tools.llm.*

/** Restore only a resumed turn's explicit reads; ordinary history never reloads media. */
internal object ResourceReplaySupport {
    fun restore(events: List<MessageEventRecord>, tools: List<ToolDyn>): List<Message> {
        val restored = mutableListOf<UserContent>()
        events
            .filter {
                it.eventType == MessageEventType.TOOL_CALL &&
                    it.status == MessageEventStatus.COMPLETED &&
                    it.parentEventId == null
            }
            .forEach { event ->
                val context = event.context ?: return@forEach
                val raw = context.get("result") ?: return@forEach
                val result =
                    if (raw.isJsonPrimitive && raw.asJsonPrimitive.isString)
                        runCatching { JsonParser.parseString(raw.asString) }.getOrNull()
                    else raw
                val summaries =
                    result
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get("resources")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray ?: return@forEach
                if (
                    summaries.none {
                        it.isJsonObject &&
                            it.asJsonObject.get("type")?.asString == "resource_read_summary"
                    }
                )
                    return@forEach
                val name = context.get("name")?.asString ?: error("资源历史缺少工具名称")
                val tool =
                    tools.filterIsInstance<NativeResourceTool>().singleOrNull {
                        it.definition("").name == name
                    } ?: error("恢复资源时模型不再支持 $name")
                val request =
                    JsonObject().apply {
                        add(
                            "resources",
                            JsonArray().apply {
                                summaries.forEach { summary ->
                                    add(
                                        JsonObject().apply {
                                            addProperty(
                                                "path",
                                                summary.asJsonObject.get("path").asString,
                                            )
                                        }
                                    )
                                }
                            },
                        )
                    }
                val read = tool.executeBlocking(request)
                val current = read.result.asJsonObject.getAsJsonArray("resources")
                require(
                    current.size() == summaries.size() &&
                        current.zip(summaries).all { (a, b) ->
                            a.asJsonObject.get("sha256") == b.asJsonObject.get("sha256")
                        }
                ) {
                    "资源在中断后发生变化，请重新发送或明确重新读取"
                }
                restored.addAll(read.resources.flatMap { it.content })
            }
        return if (restored.isEmpty()) emptyList() else listOf(Message.User(restored))
    }
}
