package com.lhstack.tools.db

import com.baomidou.mybatisplus.annotation.DbType
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.baomidou.mybatisplus.core.MybatisConfiguration
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder
import com.baomidou.mybatisplus.core.config.GlobalConfig
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor
import com.lhstack.tools.const.Const
import com.zaxxer.hikari.HikariDataSource
import org.apache.ibatis.mapping.Environment
import org.apache.ibatis.session.SqlSession
import org.apache.ibatis.session.SqlSessionFactory
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory
import java.io.File
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 数据库入口。全局单例，参考 jtools-runtime-environment 的
 * HikariCP + MyBatis-Plus 用法。数据库文件位于 ~/.jtools/agent/agent.db。
 */
object AgentDatabase {

    private const val DB_SUBDIR = "agent"
    private const val DB_FILE = "agent.db"

    @Volatile
    private var dataSource: HikariDataSource? = null

    @Volatile
    private var sqlSessionFactory: SqlSessionFactory? = null

    private val initialized = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val lock = Any()

    /** 需要注册到 MyBatis 的 Mapper 接口集合。各模块在此登记。 */
    private val mapperClasses: MutableList<Class<*>> = mutableListOf()

    fun registerMapper(vararg mappers: Class<*>) {
        synchronized(lock) {
            mappers.forEach { mapper ->
                if (mapperClasses.none { it == mapper }) {
                    mapperClasses.add(mapper)
                }
            }
        }
    }

    fun init() {
        check(initialized.compareAndSet(false, true)) { "AgentDatabase is already initialized" }
        synchronized(lock) {
            try {
                destroyed.set(false)
                val databaseFile = databaseFile()
                val ds = buildDataSource(databaseFile)
                dataSource = ds
                applySchema(ds, databaseFile)
                sqlSessionFactory = buildSqlSessionFactory(ds)
            } catch (e: Throwable) {
                initialized.set(false)
                throw RuntimeException("Failed to initialize AgentDatabase", e)
            }
        }
    }

    fun isReady(): Boolean =
        initialized.get() && !destroyed.get() && sqlSessionFactory != null

    /**
     * 打开一个事务性会话，回调结束后统一提交；异常回滚。
     * 与 awake-claw 的 repository 事务边界一致：每次业务操作在一个明确事务内完成。
     */
    fun <T> execute(action: (SqlSession) -> T): T {
        val factory = sqlSessionFactory
            ?: throw IllegalStateException("AgentDatabase is not initialized")
        val session = factory.openSession(false)
        try {
            val result = action(session)
            session.commit()
            return result
        } catch (e: Throwable) {
            session.rollback()
            throw e
        } finally {
            session.close()
        }
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return
        }
        synchronized(lock) {
            try {
                dataSource?.takeIf { !it.isClosed }?.close()
            } catch (_: Throwable) {
                // ignore close error
            } finally {
                dataSource = null
                sqlSessionFactory = null
                initialized.set(false)
            }
        }
    }

    private fun databaseFile(): File {
        val dir = File(Const.JTOOLS_PLUGIN_HOME, DB_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, DB_FILE)
    }

    private fun buildDataSource(databaseFile: File): HikariDataSource {
        val ds = HikariDataSource()
        val dbPath = databaseFile.absolutePath.replace("\\", "/")
        ds.driverClassName = "org.sqlite.JDBC"
        ds.jdbcUrl = "jdbc:sqlite:$dbPath"
        ds.isAutoCommit = false
        ds.minimumIdle = 1
        ds.maximumPoolSize = 5
        ds.maxLifetime = 60_000
        ds.idleTimeout = 30_000
        return ds
    }

    private fun applySchema(ds: HikariDataSource, databaseFile: File) {
        ds.connection.use { connection: Connection ->
            val initializeDefaultPrompt = !tableExists(connection, "prompt_templates")
            connection.createStatement().use { statement ->
                AgentSchema.STATEMENTS.forEach { sql ->
                    statement.execute(sql)
                }
            }
            ensureChatSessionScopeColumns(connection)
            migrateIdeProjectToolNames(connection)
            if (initializeDefaultPrompt) DefaultPromptTemplate.insert(connection)
            verifyDefaultPromptInitialization(connection, databaseFile, initializeDefaultPrompt)
            connection.commit()
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

    private fun ensureChatSessionScopeColumns(connection: Connection) {
        val columns = connection.createStatement().use { statement ->
            statement.executeQuery("pragma table_info(chat_sessions)").use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString("name"))
                }
            }
        }
        connection.createStatement().use { statement ->
            if ("session_type" !in columns) {
                statement.execute("alter table chat_sessions add column session_type text not null default 'global'")
            }
            if ("project_path" !in columns) {
                statement.execute("alter table chat_sessions add column project_path text")
            }
        }
    }

    private fun migrateIdeProjectToolNames(connection: Connection) {
        val aliases = mapOf(
            "read_file" to "read_project_files",
            "write_file" to "write_project_files",
            "replace_text_in_file" to "replace_project_text",
            "find_files" to "find_project_files",
            "search_text" to "search_project_text",
            "format_file" to "format_project_files",
            "compile_project" to "build_project",
            "get_file_problems" to "inspect_project_files",
        )
        val updates = mutableListOf<Pair<Long, String>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("select id, ext_config from agents").use { rows ->
                while (rows.next()) {
                    val id = rows.getLong("id")
                    val original = rows.getString("ext_config")
                    val parsed = try {
                        JsonParser.parseString(original)
                    } catch (error: Throwable) {
                        throw IllegalStateException("Agent `$id` ext_config is not valid JSON", error)
                    }
                    val root = parsed.takeIf { it.isJsonObject }?.asJsonObject
                        ?: throw IllegalStateException("Agent `$id` ext_config must be a JSON object")
                    if (migrateToolNames(root, aliases)) updates.add(id to root.toString())
                }
            }
        }
        connection.prepareStatement("update agents set ext_config = ? where id = ?").use { statement ->
            updates.forEach { (id, config) ->
                statement.setString(1, config)
                statement.setLong(2, id)
                statement.addBatch()
            }
            if (updates.isNotEmpty()) statement.executeBatch()
        }
    }

    private fun migrateToolNames(root: JsonObject, aliases: Map<String, String>): Boolean {
        val tools = root.get("tools") ?: return false
        require(tools.isJsonObject) { "Agent ext_config.tools must be a JSON object" }
        var changed = false
        listOf("enabled", "disabled").forEach { property ->
            val value = tools.asJsonObject.get(property) ?: return@forEach
            require(value.isJsonArray) { "Agent ext_config.tools.$property must be a JSON array" }
            val migrated = JsonArray()
            val names = linkedSetOf<String>()
            value.asJsonArray.forEach { item ->
                require(item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                    "Agent ext_config.tools.$property values must be strings"
                }
                names.add(aliases[item.asString] ?: item.asString)
            }
            names.forEach(migrated::add)
            if (migrated != value) {
                tools.asJsonObject.add(property, migrated)
                changed = true
            }
        }
        return changed
    }

    private fun buildSqlSessionFactory(ds: HikariDataSource): SqlSessionFactory {
        val configuration = MybatisConfiguration()

        val interceptor = MybatisPlusInterceptor()
        val pagination = PaginationInnerInterceptor()
        pagination.dbType = DbType.SQLITE
        pagination.isOptimizeJoin = true
        interceptor.addInnerInterceptor(pagination)

        configuration.environment = Environment("agent", JdbcTransactionFactory(), ds)
        configuration.addInterceptor(interceptor)
        configuration.isMapUnderscoreToCamelCase = true
        configuration.isUseGeneratedKeys = true

        synchronized(lock) {
            mapperClasses.forEach { mapper ->
                if (!configuration.hasMapper(mapper)) {
                    configuration.addMapper(mapper)
                }
            }
        }

        val globalConfig: GlobalConfig = GlobalConfigUtils.getGlobalConfig(configuration)
        globalConfig.sqlInjector = DefaultSqlInjector()
        globalConfig.identifierGenerator = DefaultIdentifierGenerator()
        globalConfig.metaObjectHandler = AgentMetaObjectHandler()

        return MybatisSqlSessionFactoryBuilder().build(configuration)
    }
}
