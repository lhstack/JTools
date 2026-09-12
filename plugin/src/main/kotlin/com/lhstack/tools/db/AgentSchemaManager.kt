package com.lhstack.tools.db

import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement

/** 在 MyBatis 初始化前负责应用并记录 SQLite schema 版本。 */
internal object AgentSchemaManager {

    fun apply(connection: Connection, databaseFile: File) {
        try {
            val schemaVersion = readSchemaVersion(connection)
            if (AgentDatabaseVersion.shouldRebuildSchema(schemaVersion)) {
                wipeUserSchema(connection)
            }
            applyCurrentSchema(connection, databaseFile)
            if (schemaVersion >= AgentDatabaseVersion.REBUILD_FROM_VERSION &&
                schemaVersion < AgentDatabaseVersion.CURRENT_SCHEMA_VERSION
            ) {
                MessageEventTokenSupport.recalculateExistingEvents(
                    connection,
                    MessageEventTokenSupport.configuredRatio(connection),
                )
            }
            writeSchemaVersion(connection, AgentDatabaseVersion.CURRENT_SCHEMA_VERSION)
            connection.commit()
        } catch (e: Throwable) {
            rollback(connection, e)
        }
    }

    private fun applyCurrentSchema(connection: Connection, databaseFile: File) {
        val initializeDefaultPrompt = !tableExists(connection, "prompt_templates")
        executeSchemaStatements(connection)
        if (initializeDefaultPrompt) DefaultPromptTemplate.insert(connection)
        verifyDefaultPromptInitialization(connection, databaseFile, initializeDefaultPrompt)
    }

    /**
     * SQLite 没有 DROP DATABASE。已打开的连接不能删文件，否则 WAL/句柄会占着。
     * 旧库重建时关掉外键，删除全部用户表和视图，再走当前 schema。
     *
     * `PRAGMA foreign_keys` 在事务内是空操作。Hikari 连接默认 autoCommit=false，
     * 必须先提交并临时打开 autoCommit，外键开关才会生效。
     */
    private fun wipeUserSchema(connection: Connection) {
        val previousAutoCommit = connection.autoCommit
        var currentSql = "begin wipeUserSchema"
        try {
            if (!previousAutoCommit) {
                currentSql = "commit before disabling foreign keys"
                connection.commit()
                currentSql = "set autoCommit = true"
                connection.autoCommit = true
            }
            connection.createStatement().use { statement ->
                currentSql = "pragma foreign_keys = off"
                statement.execute(currentSql)
                listSqliteObjectNames(connection, "view").forEach { name ->
                    currentSql = "drop view if exists ${quoteIdent(name)}"
                    statement.execute(currentSql)
                }
                listSqliteObjectNames(connection, "table").forEach { name ->
                    currentSql = "drop table if exists ${quoteIdent(name)}"
                    statement.execute(currentSql)
                }
            }
        } catch (e: Throwable) {
            throw IllegalStateException(
                "Failed to wipe Agent database schema while executing `$currentSql`: ${e.message}",
                e,
            )
        } finally {
            if (!previousAutoCommit && !connection.autoCommit) {
                // already restored or closed
            } else if (!previousAutoCommit) {
                connection.autoCommit = false
            }
        }
    }

    private fun listSqliteObjectNames(connection: Connection, type: String): List<String> {
        connection.prepareStatement(
            "select name from sqlite_master where type = ? and name not like 'sqlite_%' order by name",
        ).use { statement ->
            statement.setString(1, type)
            statement.executeQuery().use { rows ->
                val names = mutableListOf<String>()
                while (rows.next()) {
                    names.add(rows.getString(1))
                }
                return names
            }
        }
    }

    private fun quoteIdent(name: String): String {
        val quote = Char(34).toString()
        return quote + name.replace(quote, quote + quote) + quote
    }

    private fun executeSchemaStatements(connection: Connection) {
        connection.createStatement().use { statement ->
            AgentSchema.STATEMENTS.forEach { sql ->
                executeSql(statement, sql)
            }
        }
    }

    private fun executeSql(statement: Statement, sql: String) {
        try {
            statement.execute(sql)
        } catch (e: Throwable) {
            throw IllegalStateException("Failed to execute Agent schema SQL `$sql`: ${e.message}", e)
        }
    }

    private fun readSchemaVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("pragma user_version").use { rows ->
                check(rows.next()) { "SQLite schema version query did not return a result" }
                rows.getInt(1)
            }
        }

    private fun writeSchemaVersion(connection: Connection, version: Int) {
        require(version >= 0) { "SQLite schema version must be non-negative" }
        connection.createStatement().use { statement ->
            statement.execute("pragma user_version = $version")
        }
    }

    private fun tableExists(connection: Connection, tableName: String): Boolean =
        connection.prepareStatement(
            "select count(*) from sqlite_master where type = 'table' and name = ?",
        ).use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "SQLite schema query did not return a result" }
                rows.getInt(1) == 1
            }
        }

    private fun verifyDefaultPromptInitialization(
        connection: Connection,
        databaseFile: File,
        initializationRequired: Boolean,
    ) {
        if (!initializationRequired) return
        connection.prepareStatement(
            "select count(*) from prompt_templates where name = ? and preamble = ?",
        ).use { statement ->
            statement.setString(1, DefaultPromptTemplate.NAME)
            statement.setString(2, DefaultPromptTemplate.CONTENT)
            statement.executeQuery().use { rows ->
                check(rows.next() && rows.getInt(1) == 1) {
                    "New Agent database `${databaseFile.absolutePath}` does not contain the default prompt template"
                }
            }
        }
    }

    private fun rollback(connection: Connection, failure: Throwable): Nothing {
        try {
            if (!connection.autoCommit) {
                connection.rollback()
            }
        } catch (rollbackFailure: SQLException) {
            failure.addSuppressed(rollbackFailure)
        }
        throw failure
    }
}
