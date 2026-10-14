package com.example.lingmouapp.service

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.lingmouapp.db.KnowledgeRepository
import com.example.lingmouapp.db.LingMouDatabase
import com.example.lingmouapp.model.NoteItem
import com.example.lingmouapp.model.RecipeItem
import com.example.lingmouapp.shared.RecipeDraft

/**
 * Android 侧的数据库入口。
 *
 * 分工刻意划清：
 *   - 增删改查的**逻辑**全在 :shared 的 `KnowledgeRepository`（无 Android 依赖，可被 Windows 端复用）
 *   - 这里只做两件 Android 特有的事：用 `AndroidSqliteDriver` 打开库，以及把行对象映射成 UI 模型
 *
 * 未来 Windows 端的对应物只需要把 `AndroidSqliteDriver` 换成 `JdbcSqliteDriver`，
 * 其余一行不用改 —— 参见 :shared 里的 `JvmDatabaseDriverFactory`。
 *
 * 所有方法都对「数据库尚未初始化」保持宽容：返回 null / 空列表，绝不抛异常。
 */
object DatabaseService {

    private const val DB_NAME = "lingmou.db"

    @Volatile
    private var repository: KnowledgeRepository? = null

    @Synchronized
    fun init(context: Context) {
        if (repository != null) return
        try {
            val driver = AndroidSqliteDriver(
                schema = LingMouDatabase.Schema,
                context = context.applicationContext,
                name = DB_NAME
            )
            repository = KnowledgeRepository(LingMouDatabase(driver))
        } catch (e: Exception) {
            // 建库失败也不能让 App 起不来：知识库功能会显示为不可用
            repository = null
        }
    }

    fun isReady(): Boolean = repository != null

    // ------------------------------------------------------------------ 写入

    /** 保存一条学习笔记，返回自增 id；数据库不可用时返回 null */
    fun saveSummary(
        title: String,
        content: String,
        mode: String,
        sourceTranscript: String
    ): Long? = repository?.insertNote(
        title = title.ifBlank { "未命名笔记" },
        content = content,
        mode = mode,
        sourceTranscript = sourceTranscript
    )

    /** 保存菜谱及其步骤，返回菜谱 id */
    fun saveRecipe(draft: RecipeDraft, sourceTranscript: String): Long? = repository?.insertRecipe(
        title = draft.title.ifBlank { "未命名菜谱" },
        summary = draft.summary,
        sourceTranscript = sourceTranscript,
        steps = draft.steps
    )

    /** 记一次专注会话 */
    fun saveFocusSession(
        startedAt: Long,
        endedAt: Long,
        distractionCount: Long,
        noteId: Long? = null
    ): Long? = repository?.insertSession(
        startedAt = startedAt,
        endedAt = endedAt,
        distractionCount = distractionCount,
        noteId = noteId
    )

    /** 记一次分心 */
    fun saveDistraction(
        sessionId: Long,
        happenedAt: Long,
        awayMillis: Long,
        faceCount: Long,
        kind: String
    ): Long? = repository?.insertDistraction(
        sessionId = sessionId,
        happenedAt = happenedAt,
        awayMillis = awayMillis,
        faceCount = faceCount,
        kind = kind
    )

    // ------------------------------------------------------------------ 读取

    fun notes(limit: Int = DEFAULT_LIMIT): List<NoteItem> {
        val repo = repository ?: return emptyList()
        return try {
            repo.notes(limit).map {
                NoteItem(
                    id = it.id,
                    title = it.title,
                    content = it.content,
                    mode = it.mode,
                    createdAt = it.createdAt
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun recipes(limit: Int = DEFAULT_LIMIT): List<RecipeItem> {
        val repo = repository ?: return emptyList()
        return try {
            repo.recipes(limit).map { recipe ->
                RecipeItem(
                    id = recipe.id,
                    title = recipe.title,
                    summary = recipe.summary,
                    steps = repo.stepsOf(recipe.id).map { step ->
                        if (step.durationHint.isBlank()) {
                            "${step.stepIndex}. ${step.instruction}"
                        } else {
                            "${step.stepIndex}. ${step.instruction}（${step.durationHint}）"
                        }
                    },
                    createdAt = recipe.createdAt
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun noteCount(): Long = repository?.noteCount() ?: 0L

    fun recipeCount(): Long = repository?.recipeCount() ?: 0L

    // ------------------------------------------------------------------ 删除

    fun deleteNote(id: Long) {
        try {
            repository?.deleteNote(id)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun deleteRecipe(id: Long) {
        try {
            repository?.deleteRecipe(id)
        } catch (e: Exception) {
            // ignore
        }
    }

    private const val DEFAULT_LIMIT = 100
}
