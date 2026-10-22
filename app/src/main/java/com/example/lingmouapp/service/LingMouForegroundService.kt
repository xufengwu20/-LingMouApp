package com.example.lingmouapp.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.FaceFrame
import com.example.lingmouapp.model.MediaAction
import com.example.lingmouapp.service.gesture.HeadGestureClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 三模式的执行者，也是摄像头与麦克风的唯一所有者。
 *
 * 为什么放在 Service 而不是 Activity：
 *   1. 「切到后台继续检测」这条需求，CameraX 需要一个活着的 LifecycleOwner；
 *      LifecycleService 正好提供，且不依赖界面是否存在。
 *   2. 前台服务通知本身就是需求 6 要的「后台状态展示」。
 *   3. 摄像头归属唯一，就不会出现 Activity 与 Service 抢相机的经典问题。
 *
 * Compose 侧通过 [LingMouEngine.preview] 拿到 Preview 用例，
 * 自己把 PreviewView 的 SurfaceProvider 挂上去；服务只负责绑定用例。
 */
class LingMouForegroundService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null

    private var mode: AppMode = AppMode.DO_NOT_DISTURB

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var faceAnalyzer: CameraFaceAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null
    private var useFrontCamera = true

    private var gazeTracker: GazeTracker? = null
    private var headGestureDetector: HeadGestureDetector? = null

    private var intruderNotifiedAt = 0L

    private val notificationHelper by lazy { NotificationHelper(this) }

    override fun onCreate() {
        super.onCreate()
        LingMouEngine.init(applicationContext)
        notificationHelper.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        mode = if (intent == null) mode else AppMode.fromName(intent.getStringExtra(EXTRA_MODE))
        LingMouEngine.setMode(mode)

        if (mode == AppMode.DO_NOT_DISTURB) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithType()
        startTicker()
        applyMode()
        LingMouEngine.setEngineRunning(true)
        LingMouEngine.log("进入${mode.label}")
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 前台通知

    private fun startForegroundWithType() {
        val types = when {
            mode.usesCamera && mode.usesMicrophone ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            mode.usesCamera -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            mode.usesMicrophone -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }
        val notification = notificationHelper.buildStatus("${mode.label}运行中", statusText())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.ID_STATUS,
                    notification,
                    types
                )
            } else {
                startForeground(NotificationHelper.ID_STATUS, notification)
            }
        } catch (e: Exception) {
            LingMouEngine.log("前台服务通知启动失败：${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun statusText(): String {
        val s = LingMouEngine.state.value
        val away = if (s.awayMillis > 300L) "（已离开 ${s.awayMillis / 1000} 秒）" else ""
        return buildString {
            append("摄像头：").append(s.cameraState)
            append(" · 人脸 ").append(s.faceCount).append(" 张")
            append(" · ").append(s.attention.label).append(away)
            append(" · 麦克风：").append(s.microphoneState)
            if (s.reminders.isNotEmpty()) {
                append(" · 待触发提醒 ").append(s.reminders.size).append(" 个")
            }
        }
    }

    // ------------------------------------------------------------ 模式调度

    private fun applyMode() {
        when (mode) {
            AppMode.FOCUS -> {
                stopAsr()
                LingMouEngine.setMicrophoneState("已释放（专注模式不需要麦克风）")
                setupGazeTracker()
                headGestureDetector = null
                LingMouEngine.setHeadControl(false)
                startCamera()
            }
            AppMode.COOKING -> {
                gazeTracker = null
                setupHeadGestures()
                startCamera()
                // 防御：进烹饪模式前先释放上一模式可能占用的麦克风（AudioRecord/SpeechRecognizer），
                // 再启动本模式的识别，避免两个采集端互相抢占导致初始化失败或崩溃。
                stopAsr()
                startAsr()
            }
            AppMode.DO_NOT_DISTURB -> shutdown()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var ticks = 0
            while (isActive) {
                val away = gazeTracker?.tick() ?: 0L
                LingMouEngine.setAway(away)

                ticks++
                if (ticks % NOTIFICATION_TICK_EVERY == 0) {
                    notificationHelper.updateStatus("${mode.label}运行中", statusText())
                    LingMouEngine.refreshPermissions()
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun setupGazeTracker() {
        gazeTracker = GazeTracker(
            awayThresholdMs = AWAY_THRESHOLD_MS,
            onAwayTimeout = {
                val seconds = AWAY_THRESHOLD_MS / 1000
                LingMouEngine.log("视线离开屏幕已超过 $seconds 秒 → 发送暂停")
                LingMouEngine.recordDistraction(
                    awayMillis = AWAY_THRESHOLD_MS,
                    faceCount = LingMouEngine.state.value.faceCount.toLong(),
                    kind = "gaze_away"
                )
                LingMouEngine.performMediaAction(MediaAction.PAUSE, "视线离开 $seconds 秒")
            },
            onAttentionBack = {
                LingMouEngine.log("视线回归屏幕 → 发送播放")
                LingMouEngine.performMediaAction(MediaAction.PLAY, "视线回归屏幕")
            }
        )
        gazeTracker?.reset()
    }

    private fun setupHeadGestures() {
        headGestureDetector = HeadGestureDetector(
            onNod = { LingMouEngine.onNodDetected() },
            onShake = { LingMouEngine.onShakeDetected() }
        )
        LingMouEngine.setHeadControl(true)
        LingMouEngine.log(
            "头部控制已启用（点头=打进度标记，摇头=回退 5 秒；" +
                "阈值 俯仰 ${HeadGestureClassifier.DEFAULT_NOD_THRESHOLD_DEG}° / " +
                "偏航 ${HeadGestureClassifier.DEFAULT_SHAKE_THRESHOLD_DEG}°）"
        )
    }

    // ------------------------------------------------------------ 摄像头

    private fun startCamera() {
        if (cameraProvider != null) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            LingMouEngine.setCameraState("缺少 CAMERA 权限")
            LingMouEngine.log("无法启动摄像头：未授予 CAMERA 权限")
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                LingMouEngine.setCameraState("摄像头初始化失败")
                LingMouEngine.log("获取 ProcessCameraProvider 失败：${e.message}")
                null
            }
            if (provider == null) return@addListener

            cameraProvider = provider

            val executor = Executors.newSingleThreadExecutor()
            analysisExecutor = executor

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val analyzer = CameraFaceAnalyzer(
                onFrame = { frame -> handleFaceFrame(frame) },
                onError = { message -> LingMouEngine.log("人脸检测异常：$message") }
            )
            analysis.setAnalyzer(executor, analyzer)

            faceAnalyzer = analyzer
            imageAnalysis = analysis
            preview = Preview.Builder().build()
            LingMouEngine.setPreview(preview)

            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Preview 与 ImageAnalysis 一起绑定。
     * Preview 在界面还没挂上 SurfaceProvider 时也不影响分析帧的产出，
     * 这样「后台继续检测」和「前台看到画面」可以共存。
     */
    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val analysis = imageAnalysis ?: return

        provider.unbindAll()

        val useCases = mutableListOf<UseCase>()
        preview?.let { useCases.add(it) }
        useCases.add(analysis)

        val selector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.bindToLifecycle(this, selector, *useCases.toTypedArray())
            LingMouEngine.setCameraState("已启动（${if (useFrontCamera) "前置" else "后置"}摄像头）")
        } catch (e: Exception) {
            LingMouEngine.log("绑定摄像头失败：${e.message}")
            if (useFrontCamera) {
                useFrontCamera = false
                try {
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        *useCases.toTypedArray()
                    )
                    LingMouEngine.setCameraState("已启动（后置摄像头，前置不可用）")
                } catch (e2: Exception) {
                    LingMouEngine.setCameraState("摄像头不可用")
                    LingMouEngine.log("后置摄像头同样绑定失败：${e2.message}")
                }
            } else {
                LingMouEngine.setCameraState("摄像头不可用")
            }
        }
    }

    private fun handleFaceFrame(frame: FaceFrame) {
        if (mode == AppMode.COOKING && frame.faceCount > 0) {
            headGestureDetector?.onHeadPose(frame.headEulerX, frame.headEulerY)
        }

        if (mode == AppMode.FOCUS) {
            val tracker = gazeTracker
            if (tracker != null) {
                val hasFace = frame.faceCount > 0
                // 第一次检测到人脸时写一条日志：确认「视线离开自动暂停」已经就绪
                if (hasFace && !tracker.isArmed()) {
                    LingMouEngine.log("检测到人脸 → 视线离开自动暂停已就绪（离开 ${AWAY_THRESHOLD_MS / 1000} 秒触发）")
                }
                tracker.onFrame(hasFace = hasFace, attentiveNow = frame.attentive)
            }

            if (frame.faceCount >= 2) {
                val now = SystemClock.elapsedRealtime()
                if (now - intruderNotifiedAt >= INTRUDER_COOLDOWN_MS) {
                    intruderNotifiedAt = now
                    notificationHelper.notifyIntruder(frame.faceCount)
                    LingMouEngine.recordDistraction(
                        awayMillis = 0L,
                        faceCount = frame.faceCount.toLong(),
                        kind = "intruder"
                    )
                    LingMouEngine.log("检测到 ${frame.faceCount} 张人脸 → 已发送「有人靠近」通知")
                }
            }
        }

        LingMouEngine.onFaceFrame(
            faceCount = frame.faceCount,
            attentive = frame.attentive,
            pitch = frame.headEulerX,
            yaw = frame.headEulerY,
            roll = frame.headEulerZ
        )
    }

    // ------------------------------------------------------------ 语音
    //
    // ASR 的「所有权」已经上移到 LingMouEngine —— 这样界面上的「云端识别」开关
    // 也能直接 start / stop，不必先切到烹饪模式。服务只负责在模式变化时发号施令，
    // 具体用云端还是系统内置、要不要降级，全部由引擎决定。

    private fun startAsr() {
        LingMouEngine.startAsr(
            preferCloud = LingMouEngine.state.value.preferCloudAsr,
            source = "烹饪模式"
        )
    }

    private fun stopAsr() {
        LingMouEngine.stopAsr()
    }

    // ------------------------------------------------------------ 收尾

    private fun shutdown() {
        stopAsr()
        // 服务被直接停掉（没有走 setMode）时也要结算会话，否则这段的统计和文字稿会丢
        LingMouEngine.endSession("引擎停止")

        tickerJob?.cancel()
        tickerJob = null

        gazeTracker?.reset()
        gazeTracker = null
        headGestureDetector = null
        LingMouEngine.setHeadControl(false)

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            // ignore
        }
        faceAnalyzer?.close()
        faceAnalyzer = null
        imageAnalysis = null
        preview = null
        LingMouEngine.setPreview(null)
        cameraProvider = null

        analysisExecutor?.shutdown()
        analysisExecutor = null
        useFrontCamera = true

        LingMouEngine.setCameraState("已释放")
        LingMouEngine.setMicrophoneState("已释放")
        LingMouEngine.setAway(0L)
        LingMouEngine.onFaceFrame(0, false, 0f, 0f)
        LingMouEngine.setEngineRunning(false)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignore
        }

        LingMouEngine.log("已释放摄像头与麦克风，AI 检测全部停止")
    }

    companion object {
        const val ACTION_START = "com.example.lingmouapp.action.START_ENGINE"
        const val ACTION_STOP = "com.example.lingmouapp.action.STOP_ENGINE"
        const val EXTRA_MODE = "extra_mode"

        private const val TICK_INTERVAL_MS = 200L
        private const val NOTIFICATION_TICK_EVERY = 10
        private const val AWAY_THRESHOLD_MS = 3_000L
        private const val INTRUDER_COOLDOWN_MS = 30_000L

        /** 进入某个模式：启动（或重启）前台服务 */
        fun start(context: Context, mode: AppMode) {
            if (mode == AppMode.DO_NOT_DISTURB) {
                stop(context)
                return
            }
            val intent = Intent(context, LingMouForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                LingMouEngine.log("启动前台服务失败：${e.javaClass.simpleName} ${e.message}")
            }
        }

        /** 释放摄像头与麦克风 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LingMouForegroundService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
