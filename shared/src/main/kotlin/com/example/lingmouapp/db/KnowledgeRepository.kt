package com.example.lingmouapp.db

import com.example.lingmouapp.shared.RecipeStepDraft

/**
 * 平台无关的增删改查。
 *
 * 这个类**没有任何 Android 依赖**，Android 端和未来的 Windows 桌面端共用同一份实现；
 * 差异只在于构造它时传入的 [LingMouDatabase] 是用哪个驱动打开的。
 *
 * 所有方法都是同步的（SQLDelight 的 generateAsync 已关闭）。调用方在 Android 上
 * 要注意别在主线程做大批量查询 —— 当前数据量很小，实际影响可以忽略。
 */
class KnowledgeRepository(private val database: LingMouDatabase) {

    private val noteQueries get() = database.studyNoteQueries
    private val sessionQueries get() = database.focusSessionQueries
    private val distractionQueries get() = database.distractionRecordQueries
    private val recipeQueries get() = database.recipeQueries
    private val stepQueries get() = database.recipeStepQueries

    /**
     * 自增主键。`last_insert_rowid()` 是连接级的，所以一个查询服务所有表；
     * 必须在对应的 insert 之后立刻调用（本类内部都紧挨着调用）。
     */
    private fun lastRowId(): Long = database.metaQueries.lastInsertRowId().executeAsOne()

    // ------------------------------------------------------------------ 学习笔记

    fun insertNote(
        title: String,
        content: String,
        mode: String,
        sourceTranscript: String = "",
        createdAt: Long = now()
    ): Long {
        noteQueries.insert(title, content, mode, sourceTranscript, createdAt)
        return lastRowId()
    }

    fun notes(limit: Int = DEFAULT_LIMIT): List<StudyNote> =
        noteQueries.selectAll().executeAsList().take(limit)

    fun note(id: Long): StudyNote? = noteQueries.selectById(id).executeAsOneOrNull()

    fun noteCount(): Long = noteQueries.countAll().executeAsOne()

    fun deleteNote(id: Long) {
        noteQueries.deleteById(id)
    }

    fun clearNotes() {
        noteQueries.deleteAll()
    }

    // ------------------------------------------------------------------ 专注会话

    fun insertSession(
        startedAt: Long,
        endedAt: Long,
        distractionCount: Long = 0L,
        noteId: Long? = null
    ): Long {
        sessionQueries.insert(startedAt, endedAt, endedAt - startedAt, distractionCount, noteId)
        return lastRowId()
    }

    fun sessions(limit: Int = DEFAULT_LIMIT): List<FocusSession> =
        sessionQueries.selectAll().executeAsList().take(limit)

    fun latestSession(): FocusSession? = sessionQueries.latest().executeAsOneOrNull()

    fun attachNoteToSession(sessionId: Long, noteId: Long) {
        sessionQueries.attachNote(noteId, sessionId)
    }

    fun sessionCount(): Long = sessionQueries.selectAll().executeAsList().size.toLong()

    fun deleteSession(id: Long) {
        sessionQueries.deleteById(id)
    }

    fun clearSessions() {
        sessionQueries.deleteAll()
    }

    // ------------------------------------------------------------------ 分心记录

    fun insertDistraction(
        sessionId: Long = NO_SESSION,
        happenedAt: Long = now(),
        awayMillis: Long = 0L,
        faceCount: Long = 0L,
        kind: String = KIND_GAZE_AWAY
    ): Long {
        distractionQueries.insert(sessionId, happenedAt, awayMillis, faceCount, kind)
        return lastRowId()
    }

    fun distractions(limit: Int = DEFAULT_LIMIT): List<DistractionRecord> =
        distractionQueries.selectAll().executeAsList().take(limit)

    fun distractionsOf(sessionId: Long): List<DistractionRecord> =
        distractionQueries.selectBySession(sessionId).executeAsList()

    fun distractionCount(): Long = distractionQueries.countAll().executeAsOne()

    fun deleteDistraction(id: Long) {
        distractionQueries.deleteById(id)
    }

    fun clearDistractions() {
        distractionQueries.deleteAll()
    }

    // ------------------------------------------------------------------ 菜谱

    /**
     * 插入菜谱并连带插入它的步骤，返回菜谱 id。
     * 步骤序号从 1 开始，按传入顺序写入。
     */
    fun insertRecipe(
        title: String,
        summary: String,
        sourceTranscript: String = "",
        steps: List<RecipeStepDraft> = emptyList(),
        createdAt: Long = now()
    ): Long {
        recipeQueries.insert(title, summary, sourceTranscript, createdAt)
        val recipeId = lastRowId()
        steps.forEachIndexed { index, step ->
            stepQueries.insert(recipeId, (index + 1).toLong(), step.instruction, step.durationHint)
        }
        return recipeId
    }

    fun recipes(limit: Int = DEFAULT_LIMIT): List<Recipe> =
        recipeQueries.selectAll().executeAsList().take(limit)

    fun recipe(id: Long): Recipe? = recipeQueries.selectById(id).executeAsOneOrNull()

    fun recipeCount(): Long = recipeQueries.countAll().executeAsOne()

    fun stepsOf(recipeId: Long): List<RecipeStep> =
        stepQueries.selectByRecipe(recipeId).executeAsList()

    fun recipeStepCount(): Long = stepQueries.countAll().executeAsOne()

    /** 先删子表再删父表：不让级联行为依赖具体 SQLite 版本/驱动的 PRAGMA 设置 */
    fun deleteRecipe(id: Long) {
        stepQueries.deleteByRecipe(id)
        recipeQueries.deleteById(id)
    }

    fun clearRecipes() {
        stepQueries.deleteAll()
        recipeQueries.deleteAll()
    }

    // ------------------------------------------------------------------ 运维

    // 注意：这里**不提供** close()。
    // Transacter 接口上没有 close，关库是「谁创建 driver 谁负责」——
    // Android 端在 Application 生命周期内不需要关，桌面端关 SqlDriver 即可。

    companion object {
        const val DEFAULT_LIMIT = 200

        /** 没有归属会话的分心记录用它 */
        const val NO_SESSION = -1L

        const val KIND_GAZE_AWAY = "gaze_away"
        const val KIND_INTRUDER = "intruder"

        const val MODE_FOCUS = "FOCUS"
        const val MODE_COOKING = "COOKING"

        fun now(): Long = System.currentTimeMillis()
    }
}
