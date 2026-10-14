package com.example.lingmouapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lingmouapp.service.SystemSettingsNavigator
import com.example.lingmouapp.ui.AndroidCameraPreview
import com.example.lingmouapp.ui.KnowledgeScreen
import com.example.lingmouapp.ui.MainScreen
import com.example.lingmouapp.ui.theme.LingMouTheme
import com.example.lingmouapp.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val handler = Handler(Looper.getMainLooper())

    private var lastNotificationAccess = false
    private var lastExactAlarmAllowed = false
    private var baselineTaken = false

    private val recheckRunnable = Runnable { viewModel.refreshPermissions() }
    private val reportRunnable = Runnable { reportPermissionChanges() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.onCameraPermission(
            result[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        )
        viewModel.onAudioPermission(
            result[Manifest.permission.RECORD_AUDIO] == true ||
                hasPermission(Manifest.permission.RECORD_AUDIO)
        )
        viewModel.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        syncPermissionState()

        setContent {
            LingMouTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                // 两个页面用状态位切换，没有引入 Navigation Compose
                if (state.showKnowledgeBase) {
                    KnowledgeScreen(
                        state = state,
                        onBack = viewModel::closeKnowledgeBase,
                        onRefresh = viewModel::openKnowledgeBase,
                        onDeleteNote = viewModel::deleteNote,
                        onDeleteRecipe = viewModel::deleteRecipe
                    )
                } else {
                    MainScreen(
                        state = state,
                        previewSlot = { AndroidCameraPreview(viewModel) },
                        onSelectMode = viewModel::selectMode,
                        onRequestPermissions = { requestRuntimePermissions() },
                        onOpenNotificationAccess = { jumpToNotificationAccess() },
                        onOpenExactAlarmSettings = { jumpToExactAlarm() },
                        onOpenAppDetails = { openAppDetails() },
                        onToggleCloudAsr = viewModel::setPreferCloudAsr,
                        onToggleCaption = viewModel::setCaptionEnabled,
                        onOpenKnowledgeBase = viewModel::openKnowledgeBase,
                        onGenerateSummary = viewModel::generateSummary,
                        onGenerateRecipe = viewModel::generateRecipe,
                        onTogglePlayer = viewModel::togglePlayer,
                        onPauseMedia = viewModel::pauseMedia,
                        onPlayMedia = viewModel::playMedia,
                        onRewindMedia = viewModel::rewindMedia,
                        onSimulateSpeech = viewModel::simulateSpeech,
                        onRunSelfTest = viewModel::runCommandSelfTest,
                        onRefreshMediaSession = viewModel::refreshMediaSession,
                        onCancelReminder = viewModel::cancelReminder,
                        onClearLog = viewModel::clearLog
                    )
                }
            }
        }

        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        syncPermissionState()
        viewModel.refreshPermissions()
        // 从系统设置页返回时，系统可能还要几百毫秒才把新授权写进设置项，
        // 所以补查一次，并把变化用 Toast 明确告诉用户。
        handler.removeCallbacks(recheckRunnable)
        handler.removeCallbacks(reportRunnable)
        handler.postDelayed(recheckRunnable, 700L)
        handler.postDelayed(reportRunnable, 1000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(recheckRunnable)
        handler.removeCallbacks(reportRunnable)
    }

    // ------------------------------------------------------------ 权限跳转

    private fun jumpToNotificationAccess() {
        val jump = SystemSettingsNavigator.openNotificationListener(this)
        toast(jump.page)
        viewModel.log(
            if (jump.alreadySatisfied) {
                "通知使用权：${jump.page}"
            } else {
                "跳转系统设置（通知使用权）：${jump.page}${if (jump.ok) "" else " —— 跳转失败"}"
            }
        )
    }

    private fun jumpToExactAlarm() {
        val jump = SystemSettingsNavigator.openExactAlarm(this)
        toast(jump.page)
        viewModel.log(
            if (jump.alreadySatisfied) {
                "精确闹钟：${jump.page}"
            } else {
                "跳转系统设置（精确闹钟）：${jump.page}${if (jump.ok) "" else " —— 跳转失败"}"
            }
        )
    }

    private fun openAppDetails() {
        val jump = SystemSettingsNavigator.openAppDetails(this)
        toast(jump.page)
        viewModel.log("跳转系统设置（应用详情）：${jump.page}")
    }

    private fun reportPermissionChanges() {
        val state = viewModel.state.value
        if (!baselineTaken) {
            baselineTaken = true
            lastNotificationAccess = state.notificationAccess
            lastExactAlarmAllowed = state.exactAlarmAllowed
            return
        }
        if (state.notificationAccess != lastNotificationAccess) {
            lastNotificationAccess = state.notificationAccess
            toast(
                if (state.notificationAccess) {
                    "通知使用权已开启，跨应用媒体控制可用"
                } else {
                    "通知使用权已关闭"
                }
            )
        }
        if (state.exactAlarmAllowed != lastExactAlarmAllowed) {
            lastExactAlarmAllowed = state.exactAlarmAllowed
            toast(
                if (state.exactAlarmAllowed) {
                    "精确闹钟已允许，定时提醒会准点触发"
                } else {
                    "精确闹钟未允许，定时提醒可能延迟"
                }
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 运行时权限

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun syncPermissionState() {
        viewModel.onCameraPermission(hasPermission(Manifest.permission.CAMERA))
        viewModel.onAudioPermission(hasPermission(Manifest.permission.RECORD_AUDIO))
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) {
            needed += Manifest.permission.CAMERA
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
