package com.lhstack.tools.db

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSessionMessageSchemaTest {
    @Test
    fun `coding session tables follow awake message schema`() {
        val statements = AgentSchema.STATEMENTS.joinToString("\n")
        assertContains(statements, "create table if not exists message_sessions")
        assertContains(statements, "create table if not exists message_events")
        assertContains(statements, "create table if not exists message_processing_tasks")
        assertContains(statements, "create table if not exists message_append_items")
        assertContains(statements, "create table if not exists message_attachments")
        assertContains(statements, "create table if not exists context_compactions")
        assertContains(statements, "session_type text not null check (session_type in ('common', 'coding'))")
        assertContains(statements, "config text not null check (json_valid(config))")
        assertFalse(statements.contains("create table if not exists chat_sessions"))
        assertFalse(statements.contains("create table if not exists chat_session_messages"))
    }

    @Test
    fun `startup does not migrate or drop legacy chat tables`() {
        val source = kotlin.io.path.Path("src/main/kotlin/com/lhstack/tools/db/AgentDatabase.kt").toFile().readText()
        assertFalse(source.contains("drop table if exists chat_session_messages"))
        assertFalse(source.contains("drop table if exists chat_sessions"))
        assertFalse(source.contains("ensureChatSessionScopeColumns"))
    }
}
