package com.lhstack.tools.db.service

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lhstack.tools.db.mapper.MessageEventMapper
import org.apache.ibatis.annotations.Select
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

class ToolArgumentHistoryQueryTest {
    @Test
    fun `invalid calls are excluded before tool retention turn selection and token budgeting`() = database { db ->
        insert(db, 1, "valid", "user_message", JsonObject().apply { addProperty("estimated_tokens", 5) })
        insert(db, 2, "valid", "tool_call", toolContext("{}", 20))
        val invalid = listOf("null", "[]", "1", "false", "\"{}\"")
        invalid.forEachIndexed { index, arguments ->
            insert(db, index + 3, "invalid-$index", "tool_call", toolContext(arguments, 10000))
        }
        insert(db, 8, "raw", "tool_call", toolContext("{}", 10000).apply { addProperty("raw_arguments", "{invalid") })
        insert(db, 9, "missing", "tool_call", JsonObject().apply { addProperty("estimated_tokens", 10000) })
        assertEquals(listOf(1L, 2L), selected(db, retention = 1, rounds = 1, budget = 25))
        assertEquals(listOf(2L), selected(db, retention = 1, rounds = 1, budget = 20))
        assertEquals(emptyList(), selected(db, retention = 1, rounds = 1, budget = 19))
        assertEquals(25L, remainingTokens(db))
        db.createStatement().use { statement ->
            statement.executeQuery("select count(*) from message_events").use { rows ->
                assertTrue(rows.next()); assertEquals(9, rows.getInt(1))
            }
        }
    }

    @Test
    fun `valid object arguments retain business failures and null raw source`() = database { db ->
        insert(db, 1, "valid", "tool_call", toolContext("{}", 20).apply {
            add("raw_arguments", JsonNull.INSTANCE)
            addProperty("result", "business error")
        })
        assertEquals(listOf(1L), selected(db, retention = 1, rounds = 1, budget = 20))
        assertEquals(20L, remainingTokens(db))
    }

    private fun selected(db: Connection, retention: Int, rounds: Int, budget: Int): List<Long> {
        val parameters = mapOf("sessionId" to 1, "currentTurnId" to "current", "maxRounds" to rounds, "toolRetention" to retention, "budget" to budget)
        val names = mutableListOf<String>()
        val sql = Regex("#\\{(\\w+)}").replace(query("selectContextHistoryEvents")) {
            names += it.groupValues[1]; "?"
        }
        return db.prepareStatement(sql).use { statement ->
            names.forEachIndexed { index, name -> statement.setObject(index + 1, parameters.getValue(name)) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getLong("id")) } }
        }
    }

    private fun remainingTokens(db: Connection): Long =
        db.prepareStatement(query("selectRemainingHistoryTokens").replace("#{sessionId}", "?")).use { statement ->
            statement.setLong(1, 1)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getLong(1) }
        }

    private fun query(method: String) = MessageEventMapper::class.java.methods.single { it.name == method }
        .getAnnotation(Select::class.java).value.joinToString(" ")

    private fun toolContext(arguments: String, tokens: Int) = JsonObject().apply {
        add("arguments", JsonParser.parseString(arguments))
        addProperty("estimated_tokens", tokens)
    }

    private fun insert(db: Connection, id: Int, turn: String, type: String, context: JsonObject) {
        db.prepareStatement("insert into message_events(id,session_id,turn_id,status,event_type,event_id,context) values(?,1,?,'failed',?, ?,?)").use {
            it.setInt(1, id); it.setString(2, turn); it.setString(3, type); it.setString(4, "event-$id"); it.setString(5, context.toString())
            it.executeUpdate()
        }
    }

    private fun database(action: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use {
                it.execute("create table message_sessions(id integer primary key, config text)")
                it.execute("insert into message_sessions values(1,'{}')")
                it.execute("""create table message_events(id integer primary key,parent_event_id integer,session_id integer,turn_id text,
                    status text,event_type text,event_id text,summary text,context text,revision integer,created_at text,updated_at text)""")
            }
            action(db)
        }
    }
}
