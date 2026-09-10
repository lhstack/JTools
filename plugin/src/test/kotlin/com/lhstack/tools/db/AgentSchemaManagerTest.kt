package com.lhstack.tools.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentSchemaManagerTest {

    @Test
    fun `new database creates current schema and default prompt`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.autoCommit = false
            AgentSchemaManager.apply(connection, File("agent.db"))

            assertEquals(AgentDatabaseVersion.CURRENT_SCHEMA_VERSION, schemaVersion(connection))
            assertTrue(tableExists(connection, "message_sessions"))
            assertEquals(1, countDefaultPrompts(connection))
        }
    }

    @Test
    fun `unversioned database is wiped by sql and rebuilt`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("pragma foreign_keys = on")
                statement.execute("create table providers (id integer primary key, name text)")
                statement.execute("create table models (id integer primary key, provider_id integer references providers(id))")
                statement.execute("create table agents (id integer primary key, model_id integer references models(id))")
                statement.execute("create table chat_sessions (id integer primary key, model_id integer references models(id))")
                statement.execute("insert into providers(id, name) values (1, 'p')")
                statement.execute("insert into models(id, provider_id) values (1, 1)")
                statement.execute("insert into agents(id, model_id) values (1, 1)")
                statement.execute("insert into chat_sessions(id, model_id) values (1, 1)")
                connection.commit()
            }

            AgentSchemaManager.apply(connection, File("agent.db"))

            assertEquals(AgentDatabaseVersion.CURRENT_SCHEMA_VERSION, schemaVersion(connection))
            assertFalse(tableExists(connection, "chat_sessions"))
            assertTrue(tableExists(connection, "message_sessions"))
            assertEquals(0, countRows(connection, "models"))
            assertEquals(1, countDefaultPrompts(connection))
        }
    }

    @Test
    fun `unversioned schema with parent table first still rebuilds when autoCommit is false`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("pragma foreign_keys = on")
                statement.execute("create table agents (id integer primary key, name text)")
                statement.execute(
                    "create table context_compactions (id integer primary key, agent_id integer references agents(id) on delete restrict)",
                )
                statement.execute("insert into agents(id, name) values (1, 'a')")
                statement.execute("insert into context_compactions(id, agent_id) values (1, 1)")
                connection.commit()
            }

            AgentSchemaManager.apply(connection, File("agent.db"))

            assertEquals(AgentDatabaseVersion.CURRENT_SCHEMA_VERSION, schemaVersion(connection))
            assertTrue(tableExists(connection, "message_sessions"))
            assertEquals(0, countRows(connection, "agents"))
        }
    }

    @Test
    fun `unversioned schema should be rebuilt`() {
        assertTrue(AgentDatabaseVersion.shouldRebuildSchema(0))
        assertFalse(AgentDatabaseVersion.shouldRebuildSchema(1))
        assertFailsWith<IllegalStateException> {
            AgentDatabaseVersion.shouldRebuildSchema(2)
        }
    }

    @Test
    fun `newer database schema version fails explicitly`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("pragma user_version = 2")
                connection.commit()
            }

            assertFailsWith<IllegalStateException> {
                AgentSchemaManager.apply(connection, File("agent.db"))
            }

            assertEquals(2, schemaVersion(connection))
        }
    }

    @Test
    fun `current schema version preserves existing data`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.autoCommit = false
            AgentSchemaManager.apply(connection, File("agent.db"))
            connection.createStatement().use { statement ->
                statement.execute(
                    "insert into global_config(key, value) values ('test.key', 'test.value')",
                )
                connection.commit()
            }

            AgentSchemaManager.apply(connection, File("agent.db"))

            assertEquals(AgentDatabaseVersion.CURRENT_SCHEMA_VERSION, schemaVersion(connection))
            connection.prepareStatement("select value from global_config where key = ?").use { statement ->
                statement.setString(1, "test.key")
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("test.value", rows.getString(1))
                }
            }
        }
    }


    private fun schemaVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("pragma user_version").use { rows ->
                assertTrue(rows.next())
                rows.getInt(1)
            }
        }

    private fun databaseObjectExists(connection: Connection, objectName: String, objectType: String): Boolean =
        connection.prepareStatement(
            "select count(*) from sqlite_master where type = ? and name = ?",
        ).use { statement ->
            statement.setString(1, objectType)
            statement.setString(2, objectName)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getInt(1) == 1
            }
        }

    private fun tableExists(connection: Connection, tableName: String): Boolean =
        connection.prepareStatement(
            "select count(*) from sqlite_master where type = 'table' and name = ?",
        ).use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getInt(1) == 1
            }
        }

    private fun countRows(connection: Connection, tableName: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from $tableName").use { rows ->
                assertTrue(rows.next())
                rows.getInt(1)
            }
        }

    private fun countDefaultPrompts(connection: Connection): Int =
        connection.prepareStatement(
            "select count(*) from prompt_templates where name = ? and preamble = ?",
        ).use { statement ->
            statement.setString(1, DefaultPromptTemplate.NAME)
            statement.setString(2, DefaultPromptTemplate.CONTENT)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getInt(1)
            }
        }
}
