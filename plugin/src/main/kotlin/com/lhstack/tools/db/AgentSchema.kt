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
        create table if not exists chat_sessions (
            id integer primary key autoincrement,
            title text,
            agent_id integer,
            created_at text not null default (CURRENT_TIMESTAMP),
            updated_at text not null default (CURRENT_TIMESTAMP)
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
        "update model_request_logs set source_type = 'agent' where source_type = '' and source_id like '%:%';"
    )
}
