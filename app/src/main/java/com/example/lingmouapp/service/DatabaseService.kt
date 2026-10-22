package com.example.lingmouapp.service

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.lingmouapp.db.KnowledgeRepository
import com.example.lingmouapp.db.LingMouDatabase
import com.example.lingmouapp.model.NoteItem
import com.example.lingmouapp.model.RecipeItem
import com.example.lingmouapp.shared.RecipeDraft
import com.example.lingmouapp.shared.RecipeStepDraft
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
                    createdAt = it.createdAt,
                    // 时间文本由平台侧格式化：commonMain 里拿不到 java.time
                    createdText = formatShortTime(it.createdAt)
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
                    createdAt = recipe.createdAt,
                    createdText = formatShortTime(recipe.createdAt)
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

    // ------------------------------------------------------------------ 演示数据

    /**
     * 一键注入演示数据（课程答辩用）：
     * 只在笔记与菜谱**都为空**时写入，绝不覆盖真实数据；返回是否真的写入了。
     */
    fun seedDemo(): Boolean {
        val repo = repository ?: return false
        return try {
            if (repo.noteCount() > 0L || repo.recipeCount() > 0L) return false
            val now = System.currentTimeMillis()
            val day = 86_400_000L

            repo.insertNote(
                title = "线性代数：特征值与特征向量",
                content = "特征向量是线性变换中方向保持不变的向量：Av = λv。\n" +
                    "要点：\n1. 特征值 λ 决定伸缩比例\n2. 相似矩阵特征值相同\n" +
                    "3. 谱分解把矩阵写成特征向量投影之和\n\n" +
                    "应用：PCA 降维、PageRank、振动分析。",
                mode = KnowledgeRepository.MODE_FOCUS,
                createdAt = now - 3 * day
            )
            repo.insertNote(
                title = "机器学习：过拟合与正则化",
                content = "过拟合 = 模型记住了训练集的噪声，泛化变差。\n" +
                    "要点：\n1. 训练误差降、验证误差升，即过拟合\n2. L1/L2 正则化给参数加约束\n" +
                    "3. Dropout、早停、数据增强都是解药\n\n" +
                    "一句话：让模型学会规律，而不是背答案。",
                mode = KnowledgeRepository.MODE_COOKING,
                createdAt = now - 2 * day
            )
            repo.insertNote(
                title = "论文精读：Attention Is All You Need",
                content = "Transformer 用自注意力取代循环结构，实现并行训练。\n" +
                    "要点：\n1. Q/K/V 矩阵与缩放点积注意力\n2. 多头注意力捕捉不同子空间\n" +
                    "3. 位置编码弥补顺序信息\n\n影响：GPT、BERT 的底座，NLP 的转折点。",
                mode = KnowledgeRepository.MODE_FOCUS,
                createdAt = now - 1 * day
            )

            repo.insertRecipe(
                title = "番茄炒蛋",
                summary = "10 分钟快手家常菜，酸甜下饭",
                steps = listOf(
                    RecipeStepDraft("打散三个鸡蛋，加少许盐和几滴料酒", "1分钟"),
                    RecipeStepDraft("热锅下油，倒入蛋液炒至刚凝固盛出", "2分钟"),
                    RecipeStepDraft("下番茄块翻炒出汁，倒回鸡蛋调味", "5分钟"),
                    RecipeStepDraft("撒葱花出锅，配米饭开吃", "")
                ),
                createdAt = now - 2 * day
            )
            repo.insertRecipe(
                title = "红烧肉",
                summary = "肥而不腻的经典硬菜，糖色是灵魂",
                steps = listOf(
                    RecipeStepDraft("五花肉切块，冷水下锅焯去血沫", "5分钟"),
                    RecipeStepDraft("小火炒糖色，下肉块翻炒上色", "8分钟"),
                    RecipeStepDraft("加料酒、生抽、老抽和热水，小火炖 40 分钟", "40分钟"),
                    RecipeStepDraft("大火收汁至浓稠，出锅装盘", "5分钟")
                ),
                createdAt = now - 1 * day
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private val shortTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** UI 模型里的时间文本在这里格式化（Android/JVM 有 java.time，commonMain 没有） */
    private fun formatShortTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(shortTimeFormat)
}
