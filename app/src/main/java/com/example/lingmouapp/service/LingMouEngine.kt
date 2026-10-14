package com.example.lingmouapp.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import com.example.lingmouapp.BuildConfig
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState
import com.example.lingmouapp.model.AttentionState
import com.example.lingmouapp.model.MediaAction
import com.example.lingmouapp.model.PlayerState
import com.example.lingmouapp.service.asr.AsrEngine
import com.example.lingmouapp.service.asr.SiliconFlowAsrEngine
import com.example.lingmouapp.service.asr.SystemAsrEngine
import com.example.lingmouapp.service.nlp.LocalCommandRouter
import com.example.lingmouapp.service.nlp.TimeExpressionParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 应用的中枢：唯一的状态源 + 唯一的指令出口。
 *
 * 分层约定：
 *   service 层（前台服务、分析器、ASR）→ 只通过这里写状态 / 发指令
 *   viewmodel 层                       → 只读 [state]，调用这里的动作方法
 *   ui 层                              → 只消费 viewmodel 暴露的 state
 *
 * 之所以用单例而不是 DI：当前阶段目标是把交互链路跑通，
 * 引入 Hilt/Koin 只会增加编译与调试成本。
 */
object LingMouEngine {

    private const val MAX_LOG_LINES = 150
    private const val PLAYER_TICK_MS = 500L

    /** 指令来源标记，只用于日志区分 */
    private const val SOURCE_MANUAL = "手动模拟"
    private const val SOURCE_SPEECH = "语音识别"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** 相机由前台服务持有，这里只把 Preview 用例暴露给 Compose 去挂 SurfaceProvider */
    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview.asStateFlow()

    private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private var appContext: Context? = null
    private var mediaSession: MediaSessionController? = null
    private var reminderScheduler: ReminderScheduler? = null

    /**
     * 语音识别会话的所有者。
     *
     * 放在引擎里而不是前台服务里，是为了让界面上的「云端识别」开关也能直接
     * start / stop，不必先切到烹饪模式。全应用同一时刻只有一个录音会话，
     * 不会出现两个识别器抢麦克风。
     */
    @Volatile
    private var asr: AsrEngine? = null

    /** 云端连续失败降级后，本次进程内不再重试云端，直到用户在界面上重新打开开关 */
    @Volatile
    private var cloudAsrSuspended: Boolean = false

    private var playerAnchorElapsed = 0L
    private var playerAnchorPosition = 0L

    // ---------------------------------------------------------------- 初始化

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        mediaSession = MediaSessionController(ctx)
        reminderScheduler = ReminderScheduler(ctx)
        NotificationHelper(ctx).ensureChannels()
        DatabaseService.init(ctx)
        refreshKnowledge()

        val keyConfigured = BuildConfig.SILICONFLOW_API_KEY.isNotBlank()
        val modelIsDefault = BuildConfig.SILICONFLOW_ASR_MODEL_IS_DEFAULT
        _state.update {
            it.copy(
                siliconFlowKeyConfigured = keyConfigured,
                preferCloudAsr = keyConfigured,
                asrModel = BuildConfig.SILICONFLOW_ASR_MODEL,
                asrModelIsDefault = modelIsDefault
            )
        }

        startPlayerTicker()
        refreshPermissions()
        log(
            "引擎初始化完成（云端 ASR Key：${if (keyConfigured) "已配置" else "未配置"}，" +
                "模型 ${BuildConfig.SILICONFLOW_ASR_MODEL}${if (modelIsDefault) " 默认值" else ""}）"
        )
    }

    fun log(message: String) {
        val line = "${LocalTime.now().format(clockFormat)}  $message"
        _state.update { state -> state.copy(log = (listOf(line) + state.log).take(MAX_LOG_LINES)) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    // ---------------------------------------------------------------- 状态写入

    private fun update(transform: (AppUiState) -> AppUiState) = _state.update(transform)

    fun setMode(mode: AppMode) {
        val previous = _state.value.mode
        if (previous == mode) return

        // 会话边界就锚在「模式切换」上：切换即结算上一段、开启新一段。
        // 放在这里（而不是前台服务里）是因为模式也可能被服务直接改（START_STICKY 重启等）。
        if (sessionMode != null) {
            endSession("切换模式 ${previous.label} → ${mode.label}")
        }

        update { it.copy(mode = mode) }

        if (mode != AppMode.DO_NOT_DISTURB) {
            startSession(mode)
        }

        // 需求：退出烹饪模式 / 切换模式时必须停止字幕识别并清空状态。
        if (_state.value.captionEnabled) {
            stopCaption("切换模式 ${previous.label} → ${mode.label}")
        }
    }

    fun setEngineRunning(running: Boolean) = update { it.copy(engineRunning = running) }

    fun setCameraState(text: String) = update { it.copy(cameraState = text) }

    fun setMicrophoneState(text: String) = update { it.copy(microphoneState = text) }

    fun setAsrBackend(text: String) = update { it.copy(asrBackend = text) }

    fun setAway(millis: Long) = update { it.copy(awayMillis = millis) }

    /** 头部动作控制的启停（由前台服务在模式切换时调用） */
    fun setHeadControl(enabled: Boolean) = update { it.copy(headControlEnabled = enabled) }

    fun setGenerating(generating: Boolean, note: String? = null) = update {
        it.copy(isGenerating = generating, generationNote = note ?: it.generationNote)
    }

    fun setCameraPermission(granted: Boolean) = update { it.copy(cameraPermission = granted) }

    fun setAudioPermission(granted: Boolean) = update { it.copy(audioPermission = granted) }

    fun setPreferCloudAsr(enabled: Boolean) = update { it.copy(preferCloudAsr = enabled) }

    fun useCloudAsr(): Boolean {
        val s = _state.value
        return s.preferCloudAsr && s.siliconFlowKeyConfigured
    }

    fun setPreview(preview: Preview?) {
        _preview.value = preview
    }

    fun onFaceFrame(
        faceCount: Int,
        attentive: Boolean,
        pitch: Float,
        yaw: Float,
        roll: Float = 0f
    ) {
        update {
            it.copy(
                faceCount = faceCount,
                attention = AttentionState.of(faceCount, attentive),
                headPitch = pitch,
                headYaw = yaw,
                headRoll = roll
            )
        }
    }

    fun refreshPermissions() {
        val ctx = appContext ?: return
        val notificationsEnabled = NotificationHelper(ctx).notificationsEnabled()
        val notificationAccess = mediaSession?.hasNotificationAccess() == true
        val exactAlarm = reminderScheduler?.canScheduleExact() ?: false
        update {
            it.copy(
                notificationsEnabled = notificationsEnabled,
                notificationAccess = notificationAccess,
                exactAlarmAllowed = exactAlarm
            )
        }

        // 通知使用权一变，就同步注册/注销「活动媒体会话变化」监听，
        // 这样授权之后不用等轮询，界面立刻就能看到会话。
        val controller = mediaSession
        if (controller != null) {
            if (notificationAccess) {
                controller.ensureWatching { scope.launch { refreshMediaSession() } }
            } else {
                controller.stopWatching()
            }
        }
        refreshMediaSession()
    }

    /**
     * 由 [MediaNotificationListener] 在系统绑定 / 解绑监听服务时回调。
     *
     * 这是「授权完成」最及时的信号 —— 比从设置页返回后再轮询要快，
     * 也顺便覆盖了「用户在设置里直接改、没经过本应用界面」的情况。
     * 注意：可能在非主线程被调用，所以这里只做线程安全的状态写入。
     */
    fun onNotificationAccessChanged() {
        val granted = mediaSession?.hasNotificationAccess() == true
        log(
            if (granted) "系统已连接通知监听服务 → 跨应用媒体控制就绪"
            else "系统已断开通知监听服务 → 跨应用媒体控制不可用"
        )
        refreshPermissions()
    }

    /** 轻量刷新：只更新界面上的媒体会话描述 */
    fun refreshMediaSession() {
        val controller = mediaSession
        if (controller == null) {
            update { it.copy(mediaSessionSummary = "媒体会话不可用", mediaSessionCount = 0) }
            return
        }
        val snapshot = controller.snapshot()
        update {
            it.copy(
                mediaSessionSummary = snapshot.summary,
                mediaSessionCount = snapshot.sessionCount
            )
        }
    }

    /** 用户主动点「刷新媒体会话」时用：刷新 + 把诊断细节写进日志 */
    fun diagnoseMediaSession() {
        val controller = mediaSession
        if (controller == null) {
            log("媒体会话不可用：这台设备没有 MediaSessionManager")
            return
        }
        val snapshot = controller.snapshot()
        update {
            it.copy(
                mediaSessionSummary = snapshot.summary,
                mediaSessionCount = snapshot.sessionCount
            )
        }
        log("媒体会话诊断：${snapshot.summary}")
        if (snapshot.detail.isNotBlank()) {
            log("  会话清单：${snapshot.detail}")
        }
    }

    // ---------------------------------------------------------------- 媒体控制

    /**
     * 所有暂停/播放/回退都从这里走，保证三件事同时发生：
     *   1. 尽力把指令下发到系统真实的媒体会话（B 站、华为视频等）
     *   2. 占位播放器同步变化，方便肉眼确认链路
     *   3. 写一条日志，说明到底有没有真的下发出去
     */
    fun performMediaAction(action: MediaAction, reason: String, seconds: Int = 0) {
        val delivered = when (action) {
            MediaAction.PAUSE -> mediaSession?.pause() ?: false
            MediaAction.PLAY -> mediaSession?.play() ?: false
            MediaAction.REWIND -> mediaSession?.rewind(seconds) ?: false
        }

        when (action) {
            MediaAction.PAUSE -> playerPause()
            MediaAction.PLAY -> playerPlay()
            MediaAction.REWIND -> playerRewind(seconds)
        }

        val describe = when (action) {
            MediaAction.PAUSE -> "暂停"
            MediaAction.PLAY -> "播放"
            MediaAction.REWIND -> "回退 ${seconds} 秒"
        }
        val target = if (delivered) "已下发系统媒体会话" else "无外部会话，仅作用于占位播放器"

        update { it.copy(lastMediaAction = "$describe（$target）") }
        log("媒体指令「$describe」← $reason（$target）")

        // 下发完立刻刷新一次，界面上的会话状态会跟着动
        refreshMediaSession()
    }

    fun currentPlayerPosition(): Long {
        val player = _state.value.player
        return if (player.isPlaying) {
            (playerAnchorPosition + (SystemClock.elapsedRealtime() - playerAnchorElapsed))
                .coerceIn(0L, player.durationMs)
        } else {
            playerAnchorPosition
        }
    }

    fun playerPlay() {
        if (_state.value.player.isPlaying) return
        playerAnchorPosition = _state.value.player.positionMs
        playerAnchorElapsed = SystemClock.elapsedRealtime()
        update { it.copy(player = it.player.copy(isPlaying = true)) }
    }

    fun playerPause() {
        val position = currentPlayerPosition()
        playerAnchorPosition = position
        playerAnchorElapsed = SystemClock.elapsedRealtime()
        update { it.copy(player = it.player.copy(isPlaying = false, positionMs = position)) }
    }

    fun playerToggle() {
        if (_state.value.player.isPlaying) playerPause() else playerPlay()
    }

    fun playerRewind(seconds: Int) {
        val position = (currentPlayerPosition() - seconds * 1000L).coerceAtLeast(0L)
        playerAnchorPosition = position
        playerAnchorElapsed = SystemClock.elapsedRealtime()
        update { it.copy(player = it.player.copy(positionMs = position)) }
    }

    private fun startPlayerTicker() {
        scope.launch {
            while (isActive) {
                if (_state.value.player.isPlaying) {
                    val position = currentPlayerPosition()
                    update { it.copy(player = it.player.copy(positionMs = position)) }
                }
                delay(PLAYER_TICK_MS)
            }
        }
    }

    // ---------------------------------------------------------------- 语音识别

    /**
     * 启动语音识别。**任何异常输入都不会抛异常、不会崩、不会卡住。**
     *
     * 三级降级规则（这是「不允许因为 API Key 缺失或网络问题阻塞功能」的落点）：
     *   1. 没有 RECORD_AUDIO 权限   → 不启动，写状态并返回
     *   2. 想用云端但没有 Key       → 自动改用系统内置识别，界面标注降级原因
     *   3. 云端连续失败达到阈值     → 自动切到系统内置识别，本次进程不再重试云端
     *
     * @param preferCloud 是否优先使用云端；false 时直接用系统内置识别
     * @param source      日志来源标记（"烹饪模式" / "云端识别开关" / "自动降级" …）
     */
    @Synchronized
    fun startAsr(preferCloud: Boolean, source: String) {
        releaseAsr()

        val ctx = appContext ?: return

        if (!hasAudioPermission(ctx)) {
            update {
                it.copy(
                    asrRunning = false,
                    asrBackend = "不可用",
                    microphoneState = "缺少 RECORD_AUDIO 权限",
                    asrNote = "未授予麦克风权限，无法进行语音识别（本地指令不受影响）"
                )
            }
            log("[$source] 无法启动语音识别：未授予 RECORD_AUDIO 权限")
            return
        }

        val state = _state.value
        val wantCloud = preferCloud && !cloudAsrSuspended
        val cloudUsable = wantCloud && state.siliconFlowKeyConfigured

        if (wantCloud && !state.siliconFlowKeyConfigured) {
            update {
                it.copy(
                    asrDegraded = true,
                    asrNote = "未配置 SILICONFLOW_API_KEY → 已自动降级为系统内置识别"
                )
            }
            log("[$source] 云端 ASR 不可用（未配置 SILICONFLOW_API_KEY）→ 自动降级为系统内置识别")
        } else if (preferCloud && cloudAsrSuspended) {
            log("[$source] 云端 ASR 本次会话已因连续失败被挂起 → 继续使用系统内置识别")
        }

        val engine: AsrEngine = if (cloudUsable) {
            SiliconFlowAsrEngine(onDegrade = { reason -> onCloudAsrDegraded(reason) })
        } else {
            SystemAsrEngine(ctx)
        }

        asr = engine
        update {
            it.copy(
                asrRunning = true,
                asrBackend = engine.displayName,
                microphoneState = "监听中"
            )
        }
        log("[$source] 语音识别已启动：${engine.displayName}")

        try {
            engine.start(
                onText = { text -> onSpeechText(text) },
                onError = { message ->
                    log("[$source] 语音识别：$message")
                    update { it.copy(asrNote = message) }
                }
            )
        } catch (e: Exception) {
            // 兜底：识别引擎自身出问题也不能让上层崩
            log("[$source] 语音识别启动异常：${e.javaClass.simpleName} ${e.message}")
            update { it.copy(asrRunning = false, asrBackend = "启动失败", asrNote = "语音识别启动失败") }
        }
    }

    /**
     * 停止语音识别并释放麦克风。
     * 进入勿扰模式、离开烹饪模式、或用户关掉「云端识别」时调用。
     */
    @Synchronized
    fun stopAsr() {
        releaseAsr()
        val captionsWereOn = _state.value.captionEnabled
        update {
            it.copy(
                asrRunning = false,
                asrBackend = "未启动",
                microphoneState = if (it.mode.usesMicrophone) "已关闭" else it.microphoneState,
                // 字幕的数据源就是语音识别，识别一停字幕就无源可依 ——
                // 一起收掉，避免界面停在上一条文本造成「还在识别」的错觉。
                captionEnabled = false,
                captionText = "",
                captionNote = if (captionsWereOn) "语音识别已停止，字幕随之关闭" else it.captionNote
            )
        }
    }

    /** 用户在界面上重新打开云端开关时调用，解除「连续失败挂起」 */
    fun allowCloudAsrRetry() {
        cloudAsrSuspended = false
    }

    /** 当前实际在跑的是不是云端引擎（用于界面上如实显示） */
    fun isCloudAsrRunning(): Boolean = asr is SiliconFlowAsrEngine

    /** 云端连续失败 → 自动切回系统内置识别 */
    private fun onCloudAsrDegraded(reason: String) {
        // 用引擎自己的协程执行，避免在 ASR 引擎的回调栈上做释放动作
        scope.launch {
            cloudAsrSuspended = true
            update { it.copy(asrDegraded = true, asrNote = reason) }
            log("云端 ASR 降级：$reason")
            startAsr(preferCloud = false, source = "自动降级")
        }
    }

    private fun releaseAsr() {
        val engine = asr
        asr = null
        if (engine != null) {
            try {
                engine.stop()
                engine.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun hasAudioPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- AI 字幕

    /**
     * 开关 AI 字幕。
     *
     * 设计取舍：字幕**没有独立的识别通道**，它的数据来源就是语音识别的原文 ——
     * 否则就要开第二个 AudioRecord，和识别器抢麦克风。
     * 因此开启字幕时若识别没在跑，会顺手把它拉起来；拉不起来的原因会如实写进 captionNote。
     *
     * @param enabled true 开启并确保识别在跑；false 关闭并清空
     */
    @Synchronized
    fun setCaptionEnabled(enabled: Boolean) {
        if (enabled == _state.value.captionEnabled) return

        if (!enabled) {
            stopCaption("用户手动关闭")
            return
        }

        update { it.copy(captionEnabled = true, captionText = "", captionNote = "") }
        log("[字幕] 已开启（数据来源：${_state.value.asrBackend}）")

        if (!_state.value.asrRunning) {
            startAsr(preferCloud = _state.value.preferCloudAsr, source = "AI 字幕")
        }

        // 把启动过程中的降级 / 报错同步给字幕区，避免用户看着空白字幕不知道发生了什么
        val note = _state.value.asrNote
        if (note.isNotBlank()) {
            update { it.copy(captionNote = note) }
        }
        if (!_state.value.asrRunning) {
            update { it.copy(captionNote = "语音识别未启动，字幕不会有内容", captionText = "") }
        }
    }

    /** 关闭字幕并清空文本。模式切换、语音识别停止、用户手动关闭都会走这里。 */
    @Synchronized
    fun stopCaption(reason: String) {
        val state = _state.value
        if (!state.captionEnabled && state.captionText.isEmpty() && state.captionNote.isEmpty()) {
            return
        }
        update { it.copy(captionEnabled = false, captionText = "", captionNote = "") }
        log("[字幕] 已关闭（$reason）")
    }

    // ---------------------------------------------------------------- 会话文字稿 & 知识库

    /**
     * 当前会话累计的文字稿。
     *
     * 为什么需要它：ASR 是「逐句识别、立刻当指令消费掉」的，
     * 如果不额外累积，会话结束时手里只剩最后一句，根本没法总结。
     * 手动模拟输入**不计入**，否则调试用的文字会污染总结内容。
     */
    private val transcriptBuffer = StringBuilder()
    private var sessionStartedAt = 0L
    private var sessionMode: AppMode? = null

    /** 会话期间攒下的分心记录，会话结束时连着 session 一起落库 */
    private val pendingDistractions = mutableListOf<PendingDistraction>()

    private data class PendingDistraction(
        val happenedAt: Long,
        val awayMillis: Long,
        val faceCount: Long,
        val kind: String
    )

    private val aiService: AIService by lazy { AIService() }

    private fun appendTranscript(text: String) {
        if (sessionStartedAt == 0L) sessionStartedAt = System.currentTimeMillis()
        if (transcriptBuffer.isNotEmpty()) transcriptBuffer.append('\n')
        transcriptBuffer.append(text)
        update { it.copy(transcriptLines = transcriptBuffer.lineSequence().count()) }
    }

    private fun currentTranscript(): String = transcriptBuffer.toString().trim()

    /** 进入一个模式时开启新会话（由前台服务调用） */
    @Synchronized
    fun startSession(mode: AppMode) {
        transcriptBuffer.setLength(0)
        pendingDistractions.clear()
        sessionStartedAt = System.currentTimeMillis()
        sessionMode = mode
        update { it.copy(transcriptLines = 0, generationNote = "") }
        log("[会话] 开始记录文字稿（${mode.label}）")
    }

    /**
     * 结束会话：把专注会话与分心记录落库，必要时自动生成笔记。
     *
     * 自动生成只在「确实有文字稿」且「配置了 Key」时发生 —— 否则只落库统计，
     * 把生成留给用户手动点按钮，避免每次都弹一个必然失败的提示。
     */
    @Synchronized
    fun endSession(reason: String) {
        val mode = sessionMode ?: return
        val startedAt = sessionStartedAt
        val transcript = currentTranscript()
        val distractions = pendingDistractions.toList()

        sessionMode = null
        sessionStartedAt = 0L
        pendingDistractions.clear()

        if (startedAt == 0L) return
        val endedAt = System.currentTimeMillis()

        if (mode == AppMode.FOCUS) {
            val sessionId = DatabaseService.saveFocusSession(
                startedAt = startedAt,
                endedAt = endedAt,
                distractionCount = distractions.size.toLong()
            )
            if (sessionId != null) {
                distractions.forEach { d ->
                    DatabaseService.saveDistraction(
                        sessionId = sessionId,
                        happenedAt = d.happenedAt,
                        awayMillis = d.awayMillis,
                        faceCount = d.faceCount,
                        kind = d.kind
                    )
                }
            }
        }

        log(
            "[会话] 结束（$reason）：文字稿 ${transcript.lineSequence().count()} 句，" +
                "分心 ${distractions.size} 次"
        )
        refreshKnowledge()
    }

    /** 供前台服务记录分心（视线离开 / 有人靠近） */
    fun recordDistraction(awayMillis: Long, faceCount: Long, kind: String) {
        pendingDistractions += PendingDistraction(
            happenedAt = System.currentTimeMillis(),
            awayMillis = awayMillis,
            faceCount = faceCount,
            kind = kind
        )
    }

    // ---- 生成 ----

    /** 把当前文字稿总结成学习笔记并落库 */
    fun generateSummary() = generate(kind = GenerationKind.SUMMARY)

    /** 把当前文字稿抽取成菜谱并落库 */
    fun generateRecipe() = generate(kind = GenerationKind.RECIPE)

    private enum class GenerationKind { SUMMARY, RECIPE }

    private fun generate(kind: GenerationKind) {
        val transcript = currentTranscript()
        val label = if (kind == GenerationKind.SUMMARY) "学习笔记" else "菜谱"

        if (transcript.isBlank()) {
            update { it.copy(generationNote = "还没有文字稿 —— 先开启语音（或 AI 字幕）让识别跑一会儿") }
            log("[生成] 文字稿为空，未调用 LLM")
            return
        }
        if (!aiService.isConfigured) {
            // 关键降级点：没 Key 直接给出可操作的原因，不发起请求、不崩
            update { it.copy(generationNote = "未配置 SILICONFLOW_API_KEY，无法生成$label") }
            log("[生成] 未配置 API Key，跳过云端生成")
            return
        }
        if (DatabaseService.isReady().not()) {
            update { it.copy(generationNote = "数据库不可用，生成结果无处保存") }
            return
        }

        scope.launch {
            update { it.copy(isGenerating = true, generationNote = "正在生成$label…") }
            log("[生成] 开始生成$label（文字稿 ${transcript.length} 字）")

            val result = if (kind == GenerationKind.SUMMARY) {
                aiService.summarizeVideo(transcript).map { summary ->
                    val content = buildString {
                        append(summary.summary)
                        if (summary.keyPoints.isNotEmpty()) {
                            append("\n\n要点：")
                            summary.keyPoints.forEachIndexed { index, point ->
                                append("\n${index + 1}. $point")
                            }
                        }
                    }
                    val id = DatabaseService.saveSummary(
                        title = summary.title,
                        content = content,
                        mode = (sessionMode ?: _state.value.mode).name,
                        sourceTranscript = transcript
                    )
                    if (id != null) "已保存笔记「${summary.title}」" else "生成成功但保存失败"
                }
            } else {
                aiService.extractRecipe(transcript).map { draft ->
                    if (draft.steps.isEmpty() && draft.title.isBlank()) {
                        "文字稿里没有识别到做菜内容"
                    } else {
                        val id = DatabaseService.saveRecipe(draft, transcript)
                        if (id != null) {
                            "已保存菜谱「${draft.title}」（${draft.steps.size} 步）"
                        } else {
                            "生成成功但保存失败"
                        }
                    }
                }
            }

            result.fold(
                onSuccess = { message ->
                    update { it.copy(isGenerating = false, generationNote = message) }
                    log("[生成] $message")
                    refreshKnowledge()
                },
                onFailure = { error ->
                    val reason = error.message ?: error.javaClass.simpleName
                    update { it.copy(isGenerating = false, generationNote = "生成$label 失败：$reason") }
                    log("[生成] 失败：$reason")
                }
            )
        }
    }

    // ---- 知识库读取 ----

    fun refreshKnowledge() {
        val notes = DatabaseService.notes()
        val recipes = DatabaseService.recipes()
        update {
            it.copy(
                databaseReady = DatabaseService.isReady(),
                notes = notes,
                recipes = recipes
            )
        }
    }

    fun setShowKnowledgeBase(show: Boolean) {
        update { it.copy(showKnowledgeBase = show) }
        if (show) refreshKnowledge()
    }

    fun deleteNote(id: Long) {
        DatabaseService.deleteNote(id)
        refreshKnowledge()
        log("[知识库] 删除笔记 #$id")
    }

    fun deleteRecipe(id: Long) {
        DatabaseService.deleteRecipe(id)
        refreshKnowledge()
        log("[知识库] 删除菜谱 #$id")
    }

    // ---------------------------------------------------------------- 本地指令

    /**
     * 手动模拟输入 / 快捷按钮入口。
     *
     * 这里**没有**、也不允许有「是否配置了 API Key」的判断 ——
     * LingMouEngine → [LocalCommandRouter] → SpeechCommandParser 全程是本机计算，
     * 连 INTERNET 权限都用不上。云端 ASR 只影响「对着手机说话」那一条路。
     *
     * 换句话说：识别后端是「未启动」还是「云端 ASR」，对下面这条链路毫无影响。
     */
    fun runManualCommand(text: String) = executeLocal(text, SOURCE_MANUAL, fromSpeech = false)

    /** 语音识别结果入口 —— 与手动入口共用同一套解析与执行逻辑，保证行为一致 */
    fun onSpeechText(text: String) = executeLocal(text, SOURCE_SPEECH, fromSpeech = true)

    private fun executeLocal(text: String, source: String, fromSpeech: Boolean) {
        // AI 字幕：语音识别的原文在进入指令解析之前就先推给字幕区，
        // 这样即使这句话不是控制指令，字幕也能正常显示。
        if (fromSpeech && _state.value.captionEnabled) {
            update { it.copy(captionText = text) }
        }

        // 会话文字稿：只有真正的语音识别结果才计入（手动模拟不算，
        // 否则「手动输入的文字」会污染总结内容）。
        if (fromSpeech) {
            appendTranscript(text)
        }

        val actions = LocalCommandRouter.route(text, source)
        val summary = actions.joinToString("；") { it.describe() }

        // 先无条件把「收到什么、解析成什么」写进状态，保证界面一定有反馈，
        // 哪怕后面的媒体指令因为没有外部会话而没有实际效果。
        update { it.copy(lastTranscript = text, lastCommand = summary) }
        log("[$source] 收到「$text」→ $summary")

        actions.forEach { action ->
            when (action) {
                is LocalCommandRouter.PlannedAction.Media ->
                    performMediaAction(action.action, action.reason, action.seconds)

                is LocalCommandRouter.PlannedAction.ScheduleTimer ->
                    scheduleTimer(action.durationMs, action.label, text)

                is LocalCommandRouter.PlannedAction.Nothing ->
                    log("[$source] 「$text」不是已知指令，未执行任何动作")
            }
        }
    }

    fun scheduleTimer(durationMs: Long, label: String, raw: String) {
        val scheduler = reminderScheduler
        if (scheduler == null) {
            log("定时器不可用，无法设置提醒")
            return
        }
        val reminder = scheduler.schedule(durationMs, label)
        update { it.copy(reminders = it.reminders + reminder) }
        val exactText = if (reminder.exact) "精确闹钟" else "非精确闹钟（未授予精确闹钟权限）"
        log("已设置提醒「$label」，${TimeExpressionParser.describe(durationMs)}后触发（$exactText）")
    }

    fun cancelReminder(id: Int) {
        reminderScheduler?.cancel(id)
        update { it.copy(reminders = it.reminders.filterNot { r -> r.id == id }) }
        log("已取消提醒 #$id")
    }

    fun onReminderFired(id: Int, label: String) {
        update {
            it.copy(
                reminders = it.reminders.filterNot { r -> r.id == id },
                lastCommand = "定时提醒已触发：$label"
            )
        }
        log("定时提醒触发：$label")
    }

    // ---------------------------------------------------------------- 指令自检

    /**
     * 本地指令自检：把固定的一批句子直接喂给 [LocalCommandRouter]，
     * 校验「文本 → 最终控制动作」这一层，而不只是中间的命令对象。
     *
     * 这条路径完全不经过麦克风、不经过网络、不使用任何 API Key，
     * 所以它全绿就说明「送入指令 → 触发控制行为」是通的。
     */
    fun runCommandSelfTest() {
        val results = LocalCommandRouter.selfTest()
        val passed = results.count { it.passed }
        update { it.copy(commandSelfTests = results) }
        log("本地指令自检：$passed/${results.size} 通过（纯本机计算：不经过麦克风、不联网、与 API Key 无关）")
        results.filterNot { it.passed }.forEach { failed ->
            log("  未通过「${failed.input}」期望：${failed.expected}，实际：${failed.actual}")
        }
    }

    // ---------------------------------------------------------------- 头部动作

    /** 点头 = 在当前进度打标记（当前版本只记录进度点） */
    fun onNodDetected() {
        val position = PlayerState.formatMillis(currentPlayerPosition())
        val description = "点头 → 已在 $position 打标记"
        update { it.copy(lastCommand = description, lastHeadGesture = description) }
        log("头部动作：点头（nod）→ 在 $position 打了一个进度标记")
    }

    /** 摇头 = 回退 5 秒 */
    fun onShakeDetected() {
        update { it.copy(lastCommand = "摇头 → 回退 5 秒", lastHeadGesture = "摇头 → 回退 5 秒") }
        performMediaAction(MediaAction.REWIND, "头部动作：摇头", 5)
    }
}
