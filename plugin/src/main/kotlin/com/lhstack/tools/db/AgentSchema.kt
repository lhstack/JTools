package com.lhstack.tools.db

/**
 * Agent 数据库 schema。表结构照搬 awake-claw（Rust 侧 repository/schema.rs），
 * 仅将 awake_current_timestamp() 替换为 SQLite 内置 CURRENT_TIMESTAMP，
 * 时间字段的写入仍由 MyBatis-Plus 的 MetaObjectHandler 兜底填充。
 */
object AgentSchema {

    val STATEMENTS: List<String> = listOf(
        "pragma foreign_keys = on;",
        """
        create table if not exists global_config (
            key text primary key,
            value text not null
        );
        """.trimIndent(),
        """
        create table if not exists mcp_servers (
            id integer primary key autoincrement,
            name text not null,
            enabled integer not null default 1,
            transport text not null,
            command text,
            args text not null default '[]',
            env text not null default '{}',
            url text,
            headers text not null default '{}'
        );
        """.trimIndent(),
        """
        create table if not exists providers (
            id integer primary key autoincrement,
            name text not null,
            kind text not null,
            api_key text,
            base_url text,
            api text,
            anthropic_version text,
            provider_config text not null default '{}',
            created_at text not null default (CURRENT_TIMESTAMP),
            updated_at text not null default (CURRENT_TIMESTAMP),
            enabled integer not null default 1
        );
        """.trimIndent(),
        """
        create table if not exists models (
            id integer primary key autoincrement,
            provider_id integer not null references providers(id) on delete cascade,
            alias text not null,
            model_id text not null,
            display_name text,
            api text,
            context_window integer,
            modalities text not null default '[]',
            model_params text,
            execution_params text,
            additional_params text,
            enabled integer not null default 1,
            created_at text not null default (CURRENT_TIMESTAMP),
            updated_at text not null default (CURRENT_TIMESTAMP),
            unique (provider_id, alias)
        );
        """.trimIndent(),
        """
        create table if not exists prompt_templates (
            id integer primary key autoincrement,
            name text not null,
            preamble text not null,
            created_at text not null default (CURRENT_TIMESTAMP),
            updated_at text not null default (CURRENT_TIMESTAMP)
        );
        """.trimIndent(),
        """
        create table if not exists agents (
            id integer primary key autoincrement,
            name text not null unique,
            description text,
            enabled integer not null default 1,
            provider_id integer,
            model_id integer,
            prompt_id integer,
            extra_prompt text,
            runtime_params text not null default '{}',
            ext_config text not null default '{}',
            distill_config text not null default '{}',
            output_mode text not null default 'text',
            max_runtime_secs integer,
            tags text not null default '[]',
            created_at text not null default (CURRENT_TIMESTAMP),
            updated_at text not null default (CURRENT_TIMESTAMP)
        );
        """.trimIndent(),
        """
        create table if not exists message_sessions (
            id integer primary key autoincrement,
            session_type text not null check (session_type in ('common', 'coding')),
            title text not null,
            config text not null check (json_valid(config)),
            created_at text not null default (CURRENT_TIMESTAMP),
            updated_at text not null default (CURRENT_TIMESTAMP)
        );
        """.trimIndent(),
        """
        create table if not exists message_events (
            id integer primary key autoincrement,
            parent_event_id integer references message_events(id) on delete restrict,
            session_id integer not null references message_sessions(id) on delete cascade,
            turn_id text not null,
            status text not null check (status in ('running', 'completed', 'failed', 'cancelled')),
            event_type text not null,
            event_id text not null,
            summary text not null,
            context text not null check (json_valid(context)),
            revision integer not null default 1,
            created_at text not null default (CURRENT_TIMESTAMP),
            updated_at text not null default (CURRENT_TIMESTAMP)
        );
        """.trimIndent(),
        "create unique index if not exists idx_message_events_identity on message_events(session_id, turn_id, ifnull(parent_event_id, 0), event_type, event_id);",
        "create index if not exists idx_message_events_session_id_id on message_events(session_id, id);",
        "create index if not exists idx_message_events_session_turn_id on message_events(session_id, turn_id, id);",
        """
        create table if not exists message_processing_tasks (
            id integer primary key autoincrement,
            session_id integer not null references message_sessions(id) on delete cascade,
            turn_id text not null,
            status text not null check (status in ('pending', 'processing', 'completed', 'failed', 'cancelled')),
            execution_config text not null check (json_valid(execution_config)),
            error text,
            created_at text not null default (CURRENT_TIMESTAMP),
            claimed_at text,
            completed_at text,
            unique(session_id, turn_id)
        );
        """.trimIndent(),
        "create index if not exists idx_message_processing_tasks_status on message_processing_tasks(status, id);",
        """
        create table if not exists context_compactions (
            id integer primary key autoincrement,
            session_type text not null check (session_type in ('common', 'coding')),
            session_id integer not null references message_sessions(id) on delete cascade,
            agent_id integer not null references agents(id) on delete restrict,
            status text not null check (status in ('running', 'completed', 'failed')),
            estimated_tokens_before integer not null check (estimated_tokens_before >= 0),
            estimated_tokens_after integer,
            summary text not null default '',
            model_log_id integer references model_request_logs(id) on delete set null,
            error text,
            created_at text not null default (CURRENT_TIMESTAMP),
            completed_at text
        );
        """.trimIndent(),
        "create index if not exists idx_context_compactions_session on context_compactions(session_type, session_id, id);",
        "create unique index if not exists idx_context_compactions_running on context_compactions(session_id) where status = 'running';",
        """
        create table if not exists message_append_items (
            id integer primary key autoincrement,
            session_id integer not null,
            turn_id text not null,
            message_event_id integer references message_events(id) on delete set null,
            sequence integer not null,
            content text not null,
            attachments text not null check (json_valid(attachments)),
            status text not null check (status in ('pending', 'claimed', 'delivered', 'cancelled')),
            created_at text not null default (CURRENT_TIMESTAMP),
            claimed_at text,
            delivered_at text,
            unique(session_id, turn_id, sequence)
        );
        """.trimIndent(),
        "create index if not exists idx_message_append_items_turn_status on message_append_items(session_id, turn_id, status, id);",
        """
        create table if not exists message_attachments (
            id integer primary key autoincrement,
            session_id integer not null references message_sessions(id) on delete cascade,
            file_name text not null,
            content_type text not null,
            size integer not null,
            path text not null,
            kind text not null,
            text_preview text,
            metadata text not null check (json_valid(metadata)),
            created_at text not null default (CURRENT_TIMESTAMP)
        );
        """.trimIndent(),
        """
        create table if not exists model_request_logs (
            id integer primary key autoincrement,
            source_type text not null,
            source_id text,
            agent_id integer,
            message_type text,
            provider_id integer,
            provider_name text,
            model_id integer,
            model_name text,
            status text not null,
            request_data text not null default '{}',
            response_data text not null default '{}',
            error_data text,
            started_at text,
            finished_at text,
            created_at text not null default (CURRENT_TIMESTAMP)
        );
        """.trimIndent(),
        "create index if not exists idx_model_request_logs_created_at on model_request_logs(created_at);",
        "create index if not exists idx_model_request_logs_source_type on model_request_logs(source_type);",
        "create index if not exists idx_model_request_logs_source_id on model_request_logs(source_id);",
        "create index if not exists idx_model_request_logs_agent_id on model_request_logs(agent_id);",
        "create index if not exists idx_model_request_logs_status on model_request_logs(status);",
        """
        create table if not exists coding_environments (
            id integer primary key autoincrement,
            name text not null unique,
            enabled integer not null default 1,
            config text not null,
            created_at text not null default (CURRENT_TIMESTAMP),
            updated_at text not null default (CURRENT_TIMESTAMP)
        );
        """.trimIndent(),
    )
}
