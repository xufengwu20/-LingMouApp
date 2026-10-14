package com.example.lingmouapp.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * **文件库**驱动工厂 —— 桌面端正式用的那个，可以反复打开同一个数据库文件。
 *
 * 与 [JvmDatabaseDriverFactory] 的区别：
 *   - `JvmDatabaseDriverFactory` 每次调用都 `Schema.create`，适合内存库/全新文件；
 *   - 这个用 `PRAGMA user_version` 记录 schema 版本，只在版本落后时才建表，
 *     所以关掉 App 再打开，数据还在，也不会因为「表已存在」而报错。
 *
 * 之所以单独放一个类而不是给 [JvmDatabaseDriverFactory] 加参数：
 * 「每次重建」和「判断后再建」是两种不同的语义，混在一个类里靠布尔量切换最容易出错。
 */
class JvmFileDatabaseFactory(
    private val filePath: String
) : DatabaseDriverFactory {

    override fun createDriver(): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$filePath")
        val existing = readUserVersion(driver)
        if (existing < SCHEMA_VERSION) {
            LingMouDatabase.Schema.create(driver)
            driver.execute(identifier = null, sql = "PRAGMA user_version = $SCHEMA_VERSION", parameters = 0)
        }
        return driver
    }

    private fun readUserVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                val hasRow = cursor.next().value
                QueryResult.Value(if (hasRow) cursor.getLong(0) ?: 0L else 0L)
            },
            parameters = 0
        ).value

    companion object {
        /** 表结构变了就把这个数字 +1，并在下面补上迁移语句 */
        const val SCHEMA_VERSION = 1L

        /** 默认放用户目录下，避免污染工程 */
        fun defaultPath(): String =
            System.getProperty("user.home") + "/.lingmou/lingmou.db"
    }
}
