package com.lhstack.tools.agent.coding

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactionTranscriptSupportTest {
    @Test
    fun `transcript uses readable dialogue instead of raw json`() {
        val user = event(MessageEventType.USER_MESSAGE, JsonObject().apply { addProperty("content", "请继续改这个文件") })
        val tool = event(MessageEventType.TOOL_CALL, JsonObject().apply {
            addProperty("name", "read")
            add("arguments", JsonObject().apply {
                addProperty("path", "src/Main.kt")
                addProperty("offset", 1)
                addProperty("limit", 80)
            })
            addProperty("result", "Use offset=81")
        })
        val reply = event(MessageEventType.MODEL_REPLY, JsonObject().apply { addProperty("response", "已读取文件") })
        assertEquals("用户：请继续改这个文件", CompactionTranscriptSupport.transcriptLine(user))
        assertEquals(
            """工具 read({"path":"src/Main.kt","offset":1,"limit":80}) -> Use offset=81""",
            CompactionTranscriptSupport.transcriptLine(tool),
        )
        assertEquals("助手：已读取文件", CompactionTranscriptSupport.transcriptLine(reply))
        val prompt = CompactionTranscriptSupport.prompt(listOf(user, tool, reply))
        assertTrue(prompt.contains("<conversation>"))
        assertTrue(prompt.contains("<read-files>"))
        assertTrue(prompt.contains("src/Main.kt  offset=1 limit=80 truncated=yes next_offset=81"))
        assertTrue(!prompt.contains("\"event_type\""))
    }

    @Test
    fun `retry failed and compaction events are skipped`() {
        assertNull(CompactionTranscriptSupport.transcriptLine(event(MessageEventType.MODEL_RETRY, JsonObject().apply { addProperty("content", "retry") })))
        assertNull(CompactionTranscriptSupport.transcriptLine(event(MessageEventType.TASK_FAILED, JsonObject().apply { addProperty("content", "failed") })))
        assertNull(CompactionTranscriptSupport.transcriptLine(event(MessageEventType.COMPACTION_NOTICE, JsonObject().apply { addProperty("summary", "旧摘要") })))
    }

    @Test
    fun `persist summary appends checkpoint once`() {
        val tool = event(MessageEventType.TOOL_CALL, JsonObject().apply {
            addProperty("name", "write")
            add("arguments", JsonObject().apply { addProperty("path", "src/Main.kt") })
        })
        val summary = CompactionTranscriptSupport.persistSummary(
            "已完成改文件\n\n<modified-files>\nold\n</modified-files>",
            listOf(tool),
        )
        assertTrue(summary.contains("已完成改文件"))
        assertTrue(summary.contains("<modified-files>\nsrc/Main.kt\n</modified-files>"))
        assertEquals(1, "<modified-files>".toRegex().findAll(summary).count())
    }

    @Test
    fun `append summary adds session memory`() {
        val next = CompactionTranscriptSupport.appendSummary("系统提示", "压缩后的记忆")
        assertTrue(next.startsWith("系统提示"))
        assertTrue(next.contains("压缩后的记忆"))
    }

    private fun event(type: MessageEventType, context: JsonObject) = MessageEventRecord(
        id = 1,
        parentEventId = null,
        sessionId = 1,
        turnId = "turn-1",
        status = MessageEventStatus.COMPLETED,
        eventType = type,
        eventId = "1",
        summary = "s",
        context = context,
        revision = 1,
        createdAt = null,
        updatedAt = null,
    )
}
