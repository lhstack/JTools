package com.lhstack.tools.agent.model.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.agent.model.llm.ToolDefinition
import com.lhstack.tools.agent.model.llm.ToolDyn
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * get_time 工具。完全照抄 awake-claw tools.rs 的 GetTimeTool。
 *
 * 给定 IANA 时区返回该时区当前时间；省略时区则返回系统本地时间。
 * 输出 timezone / datetime(RFC3339,秒精度) / unix_timestamp，与 awake 一致。
 */
class GetTimeTool : ToolDyn {

    override fun definition(prompt: String): ToolDefinition = ToolDefinition(
        name = NAME,
        description = "Get the current time for an IANA timezone. If timezone is omitted, returns the current system local time.",
        parameters = JsonParser.parseString(
            """
            {
                "type": "object",
                "properties": {
                    "timezone": {
                        "type": "string",
                        "description": "Optional IANA timezone, for example Asia/Shanghai, America/New_York, UTC."
                    }
                },
                "required": []
            }
            """.trimIndent()
        ),
    )

    override fun callJsonBlocking(args: JsonElement): JsonElement {
        val timezone = args.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("timezone")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf { it.isNotBlank() }

        val output = JsonObject()
        if (timezone != null) {
            val zone = try {
                ZoneId.of(timezone)
            } catch (_: Throwable) {
                throw ToolException.invalidTimezone(timezone)
            }
            val now = ZonedDateTime.now(zone)
            output.addProperty("timezone", timezone)
            output.addProperty("datetime", now.format(RFC3339_SECONDS))
            output.addProperty("unix_timestamp", now.toEpochSecond())
        } else {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            output.addProperty("timezone", "system_local")
            output.addProperty("datetime", now.format(RFC3339_SECONDS))
            output.addProperty("unix_timestamp", now.toEpochSecond())
        }
        return output
    }

    companion object {
        const val NAME = "get_time"

        /** RFC3339 秒精度，含时区偏移，对齐 awake 的 to_rfc3339_opts(Secs)。 */
        private val RFC3339_SECONDS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}
