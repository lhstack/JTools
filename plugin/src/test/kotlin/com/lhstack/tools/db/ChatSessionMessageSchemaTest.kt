package com.lhstack.tools.db

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

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
        assertContains(statements, "create table if not exists agent_events")
        assertContains(statements, "session_type text not null check (session_type in ('common', 'coding'))")
        assertContains(statements, "config text not null check (json_valid(config))")
        assertFalse("create table if not exists chat_sessions" in statements)
        assertFalse("create table if not exists chat_session_messages" in statements)
    }

}
