package com.example.lingmouapp.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * 桌面 / JVM 用的驱动工厂（放在 jvmMain，因为它依赖 sqlite-jdbc 这个 JVM 库）。
 *
 * 默认使用内存库，适合单测；Windows 端把 url 换成文件路径即可：
 * ```
 * JvmDatabaseDriverFactory("jdbc:sqlite:$userHome/.lingmou/lingmou.db")
 * ```
 *
 * 注意：这里对**每次调用**都执行 Schema.create，因此适用于「内存库」或「全新文件」。
 * 要做可持久化的正式文件库，需要按 `PRAGMA user_version` 判断后再建表 —— 这一块
 * 等 Windows 端真正需要持久化时再补（当前桌面端用内存库演示闭环）。
 */
class JvmDatabaseDriverFactory(
    private val jdbcUrl: String = "jdbc:sqlite::memory:"
) : DatabaseDriverFactory {

    override fun createDriver(): SqlDriver =
        JdbcSqliteDriver(jdbcUrl).also { driver ->
            LingMouDatabase.Schema.create(driver)
        }
}
