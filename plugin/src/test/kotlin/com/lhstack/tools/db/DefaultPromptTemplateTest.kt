package com.lhstack.tools.db

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultPromptTemplateTest {
    @Test
    fun `default prompt is inserted as one complete template`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    create table prompt_templates (
                        id integer primary key autoincrement,
                        name text not null,
                        preamble text not null,
                        created_at text not null default (CURRENT_TIMESTAMP),
                        updated_at text not null default (CURRENT_TIMESTAMP)
                    )
                    """.trimIndent(),
                )
            }

            DefaultPromptTemplate.insert(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("select name, preamble from prompt_templates").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(DefaultPromptTemplate.NAME, rows.getString("name"))
                    assertEquals(DefaultPromptTemplate.CONTENT, rows.getString("preamble"))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `default prompt has the confirmed name and engineering rules`() {
        assertEquals("工程任务执行规范（默认）", DefaultPromptTemplate.NAME)
        assertTrue(DefaultPromptTemplate.CONTENT.contains("### 1.1 总原则"))
        assertTrue(DefaultPromptTemplate.CONTENT.contains("### 1.2 分层责任"))
        assertTrue(DefaultPromptTemplate.CONTENT.contains("### 1.3 禁止打洞式修复"))
        assertTrue(DefaultPromptTemplate.CONTENT.contains("### 1.4 项目 AGENTS.md 初始化规则"))
        assertTrue(DefaultPromptTemplate.CONTENT.contains("不得仅因为本规则而覆盖、重写或扩展已有的 `AGENTS.md`"))
        assertTrue(DefaultPromptTemplate.CONTENT.contains("不得为了初始化 `AGENTS.md` 自动执行依赖安装、项目构建、测试"))
        assertFalse(DefaultPromptTemplate.CONTENT.contains("私有方法种"))
    }
}
