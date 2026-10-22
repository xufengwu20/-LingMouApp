package com.example.lingmouapp.desktop

import com.example.lingmouapp.db.JvmDatabaseDriverFactory
import com.example.lingmouapp.db.JvmFileDatabaseFactory
import com.example.lingmouapp.db.KnowledgeRepository
import com.example.lingmouapp.db.openDatabase
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState
import com.example.lingmouapp.model.MediaAction
import com.example.lingmouapp.model.NoteItem
import com.example.lingmouapp.model.PlatformCapabilities
import com.example.lingmouapp.model.PlayerState
import com.example.lingmouapp.model.RecipeItem
import com.example.lingmouapp.shared.RecipeStepDraft
import com.example.lingmouapp.model.Reminder
import com.example.lingmouapp.service.nlp.LocalCommandRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Windows 桌面端的状态中枢。
 *
 * 结构与 Android 的 `LingMouEngine` 对应，但**只保留桌面端真正能做的事**：
 *   - 复用 :shared 的 `LocalCommandRouter`（本地指令闭环）、SQLDelight 仓库、模型
 *   - 占位播放器用 `ScheduledExecutorService` 每 500ms 推进进度，让
 *     「暂停 / 播放 / 回退 5 秒」看得见
 *   - 定时提醒同样用 `ScheduledExecutorService`（**进程存活期间有效**）
 *   - 跨应用媒体控制、人脸检测、麦克风 ASR、云端 LLM 全部不做，
 *     由 `PlatformCapabilities.WINDOWS.unsupported` 在 UI 上明确标注
 */
object DesktopEngine {

    private const val MAX_LOG_LINES = 150
    private const val PLAYER_TICK_MS = 500L

    private val _state = MutableStateFlow(
        AppUiState(
            capabilities = PlatformCapabilities.WINDOWS,
            mediaSessionSummary = "当前平台暂不支持",
            asrNote = "当前平台暂不支持麦克风语音识别",
            player = PlayerState(title = "桌面占位播放器", isPlaying = false)
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val scheduler = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "lingmou-desktop-scheduler").apply { isDaemon = true }
    }

    private var repository: KnowledgeRepository? = null
    private var playerTicker: ScheduledFuture<*>? = null
    private var playerPositionMs = 0L
    private val reminderTasks = LinkedHashMap<Int, ScheduledFuture<*>>()
    private var nextReminderId = 1

    // 会话文字稿（桌面端没有 ASR，所以这里只会由「手动模拟」以外的路径写入；
    // 保留字段是为了和 Android 端共用同一套 AppUiState）
    private val transcript = StringBuilder()
    private var sessionStartedAt = 0L
    private var sessionMode: AppMode? = null

    // ------------------------------------------------------------------ 基础

    private inline fun update(block: (AppUiState) -> AppUiState) {
        _state.value = block(_state.value)
    }

    fun log(message: String) {
        update { current ->
            val stamped = "[%s] %s".format(currentLogTime(), message)
            current.copy(log = (listOf(stamped) + current.log).take(MAX_LOG_LINES))
        }
    }

    private fun currentLogTime(): String {
        val now = java.time.LocalTime.now()
        return "%02d:%02d:%02d".format(now.hour, now.minute, now.second)
    }

    /** 统一的「暂不支持」出口：既写日志，也把原因显示在界面上 */
    fun notSupported(feature: String) {
        log("当前平台暂不支持：$feature")
    }

    // ------------------------------------------------------------------ 初始化

    @Synchronized
    fun init() {
        if (repository != null) return

        repository = try {
            // 优先用文件库（关掉再打开数据还在）；受限环境下写不了用户目录就退回内存库
            val path = JvmFileDatabaseFactory.defaultPath()
            File(path).parentFile?.mkdirs()
            KnowledgeRepository(openDatabase(JvmFileDatabaseFactory(path)))
        } catch (fileError: Throwable) {
            try {
                log("文件库不可用（${fileError.message}），退回内存库：本次运行的数据不会保留")
                KnowledgeRepository(openDatabase(JvmDatabaseDriverFactory()))
            } catch (memoryError: Throwable) {
                log("数据库初始化失败：${memoryError.message}")
                null
            }
        }

        update { it.copy(databaseReady = repository != null) }
        log("桌面端启动 · 平台：${PlatformCapabilities.WINDOWS.platformName}")
        log(
            "平台暂不支持的能力共 ${PlatformCapabilities.WINDOWS.unsupported.size} 项，" +
                "已在「平台能力」卡片中逐条标注"
        )
        refreshKnowledge()
        startPlayerTicker()
    }

    fun shutdown() {
        playerTicker?.cancel(true)
        reminderTasks.values.forEach { it.cancel(true) }
        reminderTasks.clear()
        scheduler.shutdownNow()
    }

    // ------------------------------------------------------------------ 模式

    fun setMode(mode: AppMode) {
        val previous = _state.value.mode
        if (previous == mode) return

        if (sessionMode != null) endSession("切换模式 ${previous.label} → ${mode.label}")
        update { it.copy(mode = mode) }

        if (mode != AppMode.DO_NOT_DISTURB) startSession(mode)

        log("切换模式：${previous.label} → ${mode.label}")
        if (mode == AppMode.FOCUS || mode == AppMode.COOKING) {
            log("注意：桌面端没有人脸检测与麦克风，${mode.label} 的自动能力不可用，仅保留本地指令与占位播放器")
        }
    }

    private fun startSession(mode: AppMode) {
        transcript.setLength(0)
        sessionStartedAt = System.currentTimeMillis()
        sessionMode = mode
        update { it.copy(transcriptLines = 0) }
    }

    @Synchronized
    fun endSession(reason: String) {
        val mode = sessionMode ?: return
        val startedAt = sessionStartedAt
        val lines = transcript.lineSequence().count()
        sessionMode = null
        sessionStartedAt = 0L
        if (startedAt == 0L) return

        if (mode == AppMode.FOCUS) {
            repository?.insertSession(
                startedAt = startedAt,
                endedAt = System.currentTimeMillis(),
                distractionCount = 0L
            )
        }
        log("[会话] 结束（$reason）：模式 ${mode.label}，文字稿 $lines 句")
        refreshKnowledge()
    }

    // ------------------------------------------------------------------ 本地指令闭环

    /** 手动模拟输入 —— 与 Android 端共用同一个 LocalCommandRouter */
    fun runManualCommand(text: String) {
        executeLocal(text, source = "手动模拟")
    }

    private fun executeLocal(text: String, source: String) {
        val actions = LocalCommandRouter.route(text, source)

        val summary = if (actions.isEmpty()) {
            "没有匹配到任何指令"
        } else {
            actions.joinToString("；") { planned ->
                when (planned) {
                    is LocalCommandRouter.PlannedAction.Media -> {
                        applyMedia(planned.action, planned.seconds)
                        "${planned.action.label}（${planned.reason}）"
                    }
                    is LocalCommandRouter.PlannedAction.ScheduleTimer -> {
                        scheduleTimer(planned.durationMs, planned.label)
                        "定时 ${planned.label}（${planned.reason}）"
                    }
                    is LocalCommandRouter.PlannedAction.Nothing -> "未识别（${planned.reason}）"
                }
            }
        }

        update { it.copy(lastTranscript = text, lastCommand = summary) }
        log("[$source] 收到「$text」→ $summary")
    }

    fun runSelfTest() {
        val results = LocalCommandRouter.selfTest()
        update { it.copy(commandSelfTests = results) }
        val passed = results.count { it.passed }
        log("本地指令自检：$passed / ${results.size} 通过（完全不依赖麦克风与 API Key）")
    }

    // ------------------------------------------------------------------ 占位播放器

    private fun startPlayerTicker() {
        if (playerTicker != null) return
        playerTicker = scheduler.scheduleAtFixedRate({
            val current = _state.value.player
            if (!current.isPlaying) return@scheduleAtFixedRate
            playerPositionMs = (playerPositionMs + PLAYER_TICK_MS)
                .coerceAtMost(current.durationMs)
            update { it.copy(player = it.player.copy(positionMs = playerPositionMs)) }
        }, PLAYER_TICK_MS, PLAYER_TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun applyMedia(action: MediaAction, seconds: Int) {
        when (action) {
            MediaAction.PAUSE -> setPlaying(false)
            MediaAction.PLAY -> setPlaying(true)
            MediaAction.REWIND -> rewind(if (seconds > 0) seconds.toLong() else 5L)
        }
    }

    fun togglePlayer() = setPlaying(!_state.value.player.isPlaying)

    fun pauseMedia() {
        setPlaying(false)
        notSupported("跨应用媒体控制（已改为只暂停内置占位播放器）")
    }

    fun playMedia() {
        setPlaying(true)
        notSupported("跨应用媒体控制（已改为只播放内置占位播放器）")
    }

    fun rewindMedia() {
        rewind(5L)
        notSupported("跨应用媒体控制（已改为只回退内置占位播放器）")
    }

    private fun setPlaying(playing: Boolean) {
        update { it.copy(player = it.player.copy(isPlaying = playing)) }
        update { it.copy(lastMediaAction = if (playing) "播放（占位播放器）" else "暂停（占位播放器）") }
        log(if (playing) "占位播放器：开始播放" else "占位播放器：已暂停")
    }

    private fun rewind(seconds: Long) {
        playerPositionMs = (playerPositionMs - seconds * 1000L).coerceAtLeast(0L)
        update {
            it.copy(
                player = it.player.copy(positionMs = playerPositionMs),
                lastMediaAction = "回退 ${seconds} 秒（占位播放器）"
            )
        }
        log("占位播放器：回退 ${seconds} 秒 → ${PlayerState.formatMillis(playerPositionMs)}")
    }

    // ------------------------------------------------------------------ 定时提醒

    private fun scheduleTimer(durationMs: Long, label: String) {
        val id = nextReminderId++
        val triggerAt = System.currentTimeMillis() + durationMs
        val task = scheduler.schedule({
            log("⏰ 定时提醒到点：$label")
            update { current ->
                current.copy(
                    reminders = current.reminders.filterNot { it.id == id },
                    lastCommand = "⏰ 定时提醒：$label"
                )
            }
        }, durationMs, TimeUnit.MILLISECONDS)

        reminderTasks[id] = task
        update {
            it.copy(
                reminders = it.reminders + Reminder(
                    id = id,
                    label = label,
                    triggerAtMillis = triggerAt,
                    // 桌面端没有系统级精确闹钟，这里如实标 false
                    exact = false
                )
            )
        }
        log("已排定定时提醒：$label（${durationMs / 1000} 秒后，仅进程存活期间有效）")
    }

    fun cancelReminder(id: Int) {
        reminderTasks.remove(id)?.cancel(true)
        update { current -> current.copy(reminders = current.reminders.filterNot { it.id == id }) }
        log("已取消定时提醒 #$id")
    }

    // ------------------------------------------------------------------ 知识库

    fun refreshKnowledge() {
        val repo = repository
        val notes: List<NoteItem> = repo?.notes(100)?.map {
            NoteItem(
                id = it.id,
                title = it.title,
                content = it.content,
                mode = it.mode,
                createdAt = it.createdAt,
                createdText = formatShortTime(it.createdAt)
            )
        } ?: emptyList()

        val recipes: List<RecipeItem> = repo?.recipes(100)?.map { recipe ->
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
        } ?: emptyList()

        update { it.copy(databaseReady = repo != null, notes = notes, recipes = recipes) }
    }

    fun setShowKnowledgeBase(show: Boolean) {
        update { it.copy(showKnowledgeBase = show) }
        if (show) refreshKnowledge()
    }

    fun deleteNote(id: Long) {
        repository?.deleteNote(id)
        refreshKnowledge()
        log("删除笔记 #$id")
    }

    fun deleteRecipe(id: Long) {
        repository?.deleteRecipe(id)
        refreshKnowledge()
        log("删除菜谱 #$id")
    }

    /** 一键注入演示数据（答辩用）：只在库为空时写入 */
    fun seedDemoData() {
        val repo = repository
        if (repo == null) {
            log("数据库不可用，无法注入演示数据")
            return
        }
        val seeded = try {
            if (repo.noteCount() > 0L || repo.recipeCount() > 0L) {
                false
            } else {
                val now = System.currentTimeMillis()
                val day = 86_400_000L
                repo.insertNote(
                    title = "线性代数：特征值与特征向量",
                    content = "特征向量是线性变换中方向保持不变的向量：Av = λv。\n" +
                        "要点：\n1. 特征值 λ 决定伸缩比例\n2. 相似矩阵特征值相同\n" +
                        "3. 谱分解把矩阵写成特征向量投影之和\n\n应用：PCA 降维、PageRank、振动分析。",
                    mode = "FOCUS",
                    createdAt = now - 3 * day
                )
                repo.insertNote(
                    title = "机器学习：过拟合与正则化",
                    content = "过拟合 = 模型记住了训练集的噪声，泛化变差。\n" +
                        "要点：\n1. 训练误差降、验证误差升，即过拟合\n2. L1/L2 正则化给参数加约束\n" +
                        "3. Dropout、早停、数据增强都是解药",
                    mode = "COOKING",
                    createdAt = now - 2 * day
                )
                repo.insertRecipe(
                    title = "番茄炒蛋",
                    summary = "10 分钟快手家常菜，酸甜下饭",
                    steps = listOf(
                        RecipeStepDraft("打散三个鸡蛋，加少许盐和几滴料酒", "1分钟"),
                        RecipeStepDraft("热锅下油，倒入蛋液炒至刚凝固盛出", "2分钟"),
                        RecipeStepDraft("下番茄块翻炒出汁，倒回鸡蛋调味", "5分钟")
                    ),
                    createdAt = now - 2 * day
                )
                repo.insertRecipe(
                    title = "红烧肉",
                    summary = "肥而不腻的经典硬菜，糖色是灵魂",
                    steps = listOf(
                        RecipeStepDraft("五花肉切块，冷水下锅焯去血沫", "5分钟"),
                        RecipeStepDraft("小火炒糖色，下肉块翻炒上色", "8分钟"),
                        RecipeStepDraft("加料酒、生抽、老抽和热水，小火炖 40 分钟", "40分钟")
                    ),
                    createdAt = now - 1 * day
                )
                true
            }
        } catch (e: Exception) {
            false
        }
        refreshKnowledge()
        log(if (seeded) "已注入演示数据（2 条笔记 + 2 条菜谱）" else "演示数据已存在或数据库不可用，跳过注入")
    }

    fun generateSummary() {
        update { it.copy(generationNote = "当前平台暂不支持：云端笔记生成（桌面端未接入 LLM）") }
        notSupported("云端笔记生成")
    }

    fun generateRecipe() {
        update { it.copy(generationNote = "当前平台暂不支持：云端菜谱生成（桌面端未接入 LLM）") }
        notSupported("云端菜谱生成")
    }

    // ------------------------------------------------------------------ 其他



    fun setPreferCloudAsr(enabled: Boolean) {
        update { it.copy(preferCloudAsr = false, asrNote = "当前平台暂不支持") }
        notSupported("云端语音识别")
    }

    fun refreshMediaSession() {
        update { it.copy(mediaSessionSummary = "当前平台暂不支持", mediaSessionCount = 0) }
        notSupported("读取系统媒体会话")
    }

    fun clearLog() {
        update { it.copy(log = emptyList()) }
    }

    private fun formatShortTime(millis: Long): String {
        val dt = java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
        return "%02d-%02d %02d:%02d".format(
            dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute
        )
    }
}
