package com.lhstack.tools.db

import com.baomidou.mybatisplus.annotation.DbType
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
import java.util.concurrent.locks.ReentrantLock

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
    private val writeLock = ReentrantLock()

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
    /**
     * SQLite 同一进程同时只能有一个写者。对齐 awake `sqlite_write_lock`：
     * 进入事务前先拿进程内写锁，避免连接池里多条连接互相抢 RESERVED/EXCLUSIVE。
     * 当前所有 execute 都开事务，因此一律串行化；同线程可重入，允许嵌套 execute。
     */
    fun <T> execute(action: (SqlSession) -> T): T {
        val factory = sqlSessionFactory
            ?: throw IllegalStateException("AgentDatabase is not initialized")
        writeLock.lock()
        try {
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
        } finally {
            writeLock.unlock()
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
        ds.jdbcUrl = "jdbc:sqlite:$dbPath?journal_mode=WAL&synchronous=NORMAL&busy_timeout=30000&foreign_keys=on"
        ds.connectionInitSql = "PRAGMA busy_timeout=30000"
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
