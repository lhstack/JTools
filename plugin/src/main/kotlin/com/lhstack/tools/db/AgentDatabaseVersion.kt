package com.lhstack.tools.db

/** Agent 数据库的 SQLite schema 版本策略。 */
internal object AgentDatabaseVersion {
    const val CURRENT_SCHEMA_VERSION = 1
    const val REBUILD_FROM_VERSION = 1

    fun shouldRebuildSchema(schemaVersion: Int): Boolean {
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw IllegalStateException(
                "Unsupported Agent database schema version $schemaVersion; " +
                    "current version is $CURRENT_SCHEMA_VERSION",
            )
        }
        return schemaVersion < REBUILD_FROM_VERSION
    }
}
