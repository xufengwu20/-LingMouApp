package com.example.lingmouapp.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * 「谁来提供数据库连接」这一层抽象。
 *
 * 这是整个模块**唯一**需要按平台定制的地方：
 *   - Android  → `AndroidSqliteDriver`（见 app 模块的 `DatabaseService`）
 *   - 桌面/JVM → [JvmDatabaseDriverFactory]（未来的 Windows 端直接复用）
 *
 * 实现方必须保证返回的 driver **表结构已经就绪**：
 *   - Android 用 `AndroidSqliteDriver(LingMouDatabase.Schema, ...)`，它自己会建表；
 *   - JVM 用 `JdbcSqliteDriver` + 手动 `Schema.create`。
 * 之所以把这件事交给实现方，是因为「建表/迁移」的时机在两个平台上不一样，
 * 统一成一句 `Schema.create` 反而会在 Android 上重复建表报错。
 */
interface DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

/** 打开数据库。CRUD 全部走 [KnowledgeRepository]，平台差异到此为止。 */
fun openDatabase(factory: DatabaseDriverFactory): LingMouDatabase =
    LingMouDatabase(factory.createDriver())

/**
 * 桌面 / JVM 用的驱动工厂。
 *
 * 默认使用内存库，适合单测；未来的 Windows 端只要把 url 换成文件路径即可：
 * ```
 * JvmDatabaseDriverFactory("jdbc:sqlite:$userHome/.lingmou/lingmou.db")
 * ```
 *
 * 注意：这里对**每次调用**都执行 Schema.create，因此适用于「内存库」或「全新文件」。
 * 如果要做可持久化的正式文件库，需要按 `PRAGMA user_version` 判断后再建表 ——
 * 这一块留到 Windows 端真正落地时再补。
 */
class JvmDatabaseDriverFactory(
    private val jdbcUrl: String = "jdbc:sqlite::memory:"
) : DatabaseDriverFactory {

    override fun createDriver(): SqlDriver =
        JdbcSqliteDriver(jdbcUrl).also { driver ->
            LingMouDatabase.Schema.create(driver)
        }
}
