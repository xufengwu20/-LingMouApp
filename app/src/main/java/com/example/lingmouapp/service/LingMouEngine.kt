package com.example.lingmouapp.service

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.Preview
import com.example.lingmouapp.BuildConfig
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState
import com.example.lingmouapp.model.AttentionState
import com.example.lingmouapp.model.MediaAction
import com.example.lingmouapp.model.PlayerState
import com.example.lingmouapp.model.SpeechCommand
import com.example.lingmouapp.service.nlp.SpeechCommandParser
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

        val keyConfigured = BuildConfig.SILICONFLOW_API_KEY.isNotBlank()
        _state.update {
            it.copy(
                siliconFlowKeyConfigured = keyConfigured,
                preferCloudAsr = keyConfigured
            )
        }

        startPlayerTicker()
        refreshPermissions()
        log("引擎初始化完成（云端 ASR Key：${if (keyConfigured) "已配置" else "未配置"}）")
    }

    fun log(message: String) {
        val line = "${LocalTime.now().format(clockFormat)}  $message"
        _state.update { state -> state.copy(log = (listOf(line) + state.log).take(MAX_LOG_LINES)) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    // ---------------------------------------------------------------- 状态写入

    private fun update(transform: (AppUiState) -> AppUiState) = _state.update(transform)

    fun setMode(mode: AppMode) = update { it.copy(mode = mode) }

    fun setEngineRunning(running: Boolean) = update { it.copy(engineRunning = running) }

    fun setCameraState(text: String) = update { it.copy(cameraState = text) }

    fun setMicrophoneState(text: String) = update { it.copy(microphoneState = text) }

    fun setAsrBackend(text: String) = update { it.copy(asrBackend = text) }

    fun setAway(millis: Long) = update { it.copy(awayMillis = millis) }

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

    fun onFaceFrame(faceCount: Int, attentive: Boolean, pitch: Float, yaw: Float) {
        update {
            it.copy(
                faceCount = faceCount,
                attention = AttentionState.of(faceCount, attentive),
                headPitch = pitch,
                headYaw = yaw
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

    // ---------------------------------------------------------------- 语音 & 定时

    fun onSpeechText(text: String) {
        val command = SpeechCommandParser.parse(text)
        update { it.copy(lastTranscript = text, lastCommand = command.describe()) }
        log("识别到「$text」→ ${command.describe()}")

        when (command) {
            is SpeechCommand.Pause -> performMediaAction(MediaAction.PAUSE, "语音指令：$text")
            is SpeechCommand.Resume -> performMediaAction(MediaAction.PLAY, "语音指令：$text")
            is SpeechCommand.Rewind ->
                performMediaAction(MediaAction.REWIND, "语音指令：$text", command.seconds)
            is SpeechCommand.SetTimer -> scheduleTimer(command.durationMs, command.label, text)
            is SpeechCommand.Unknown -> Unit
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
     * 本地指令自检：把固定的一批句子直接喂给 [SpeechCommandParser]。
     *
     * 这条路径完全不经过麦克风、不经过网络、不使用任何 API Key，
     * 所以它全绿就说明「送入指令 → 触发控制行为」是通的。
     */
    fun runCommandSelfTest() {
        val results = SpeechCommandParser.selfTest()
        val passed = results.count { it.passed }
        update { it.copy(commandSelfTests = results) }
        log("本地指令自检：$passed/${results.size} 通过（不经过麦克风，也不使用任何 API Key）")
        results.filterNot { it.passed }.forEach { failed ->
            log("  未通过「${failed.input}」期望：${failed.expected}，实际：${failed.actual}")
        }
    }

    // ---------------------------------------------------------------- 头部动作

    /** 点头 = 在当前进度打标记（当前版本只记录进度点） */
    fun onNodDetected() {
        val position = PlayerState.formatMillis(currentPlayerPosition())
        update { it.copy(lastCommand = "点头 → 已在 $position 打标记") }
        log("头部动作：点头 → 在 $position 打了一个进度标记")
    }

    /** 摇头 = 回退 5 秒 */
    fun onShakeDetected() {
        update { it.copy(lastCommand = "摇头 → 回退 5 秒") }
        performMediaAction(MediaAction.REWIND, "头部动作：摇头", 5)
    }
}
