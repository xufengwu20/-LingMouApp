package com.example.lingmouapp.db

import app.cash.sqldelight.db.SqlDriver

/**
 * 「谁来提供数据库连接」这一层抽象。
 *
 * 这是整个模块**唯一**需要按平台定制的地方：
 *   - Android  → `AndroidSqliteDriver`（见 app 模块的 `DatabaseService`）
 *   - 桌面/JVM → `JvmDatabaseDriverFactory`（在 jvmMain 里）
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
