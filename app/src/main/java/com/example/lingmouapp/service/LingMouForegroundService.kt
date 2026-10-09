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
import com.example.lingmouapp.service.asr.AsrEngine
import com.example.lingmouapp.service.asr.SiliconFlowAsrEngine
import com.example.lingmouapp.service.asr.SystemAsrEngine
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
    private var asrEngine: AsrEngine? = null

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
                startCamera()
            }
            AppMode.COOKING -> {
                gazeTracker = null
                setupHeadGestures()
                startCamera()
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
            gazeTracker?.onFrame(frame.faceCount > 0 && frame.attentive)

            if (frame.faceCount >= 2) {
                val now = SystemClock.elapsedRealtime()
                if (now - intruderNotifiedAt >= INTRUDER_COOLDOWN_MS) {
                    intruderNotifiedAt = now
                    notificationHelper.notifyIntruder(frame.faceCount)
                    LingMouEngine.log("检测到 ${frame.faceCount} 张人脸 → 已发送「有人靠近」通知")
                }
            }
        }

        LingMouEngine.onFaceFrame(
            faceCount = frame.faceCount,
            attentive = frame.attentive,
            pitch = frame.headEulerX,
            yaw = frame.headEulerY
        )
    }

    // ------------------------------------------------------------ 语音

    private fun startAsr() {
        stopAsr()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            LingMouEngine.setMicrophoneState("缺少 RECORD_AUDIO 权限")
            LingMouEngine.setAsrBackend("不可用")
            LingMouEngine.log("无法启动语音识别：未授予 RECORD_AUDIO 权限")
            return
        }

        val engine = if (LingMouEngine.useCloudAsr()) {
            SiliconFlowAsrEngine(apiKey = com.example.lingmouapp.BuildConfig.SILICONFLOW_API_KEY)
        } else {
            SystemAsrEngine(this)
        }

        asrEngine = engine
        LingMouEngine.setAsrBackend(engine.displayName)
        LingMouEngine.setMicrophoneState("监听中")
        LingMouEngine.log("语音识别已启动：${engine.displayName}")

        engine.start(
            onText = { text -> LingMouEngine.onSpeechText(text) },
            onError = { message ->
                LingMouEngine.log("语音识别：$message")
                LingMouEngine.setMicrophoneState("异常：$message")
            }
        )
    }

    private fun stopAsr() {
        val engine = asrEngine
        asrEngine = null
        if (engine != null) {
            try {
                engine.stop()
                engine.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        LingMouEngine.setAsrBackend("未启动")
    }

    // ------------------------------------------------------------ 收尾

    private fun shutdown() {
        stopAsr()

        tickerJob?.cancel()
        tickerJob = null

        gazeTracker?.reset()
        gazeTracker = null
        headGestureDetector = null

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
