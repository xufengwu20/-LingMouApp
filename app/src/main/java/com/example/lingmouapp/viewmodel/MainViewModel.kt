package com.example.lingmouapp.viewmodel

import android.app.Application
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState
import com.example.lingmouapp.model.MediaAction
import com.example.lingmouapp.service.LingMouEngine
import com.example.lingmouapp.service.LingMouForegroundService
import kotlinx.coroutines.flow.StateFlow

/**
 * ui 层唯一能看到的入口。
 *
 * 所有真正的动作都转交给 [LingMouEngine] / 前台服务，
 * ViewModel 自己不持有任何设备资源，也不做生命周期管理。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<AppUiState> = LingMouEngine.state

    /** 相机归前台服务所有，这里只是把它的 Preview 用例转交给界面挂 SurfaceProvider */
    val preview: StateFlow<Preview?> = LingMouEngine.preview

    init {
        LingMouEngine.init(application)
        LingMouEngine.refreshPermissions()
    }

    fun refreshPermissions() = LingMouEngine.refreshPermissions()

    /** 让界面把「跳转系统设置」这类动作也记进应用内日志 */
    fun log(message: String) = LingMouEngine.log(message)

    fun selectMode(mode: AppMode) {
        LingMouEngine.setMode(mode)
        val context = getApplication<Application>()
        if (mode == AppMode.DO_NOT_DISTURB) {
            LingMouForegroundService.stop(context)
            LingMouEngine.setEngineRunning(false)
            LingMouEngine.log("切换到勿扰模式：摄像头与麦克风全部释放")
        } else {
            LingMouForegroundService.start(context, mode)
        }
    }

    /** 让前台服务按当前模式重新装配一次（切换 ASR 后端时用） */
    private fun restartEngine() {
        val mode = state.value.mode
        if (mode != AppMode.DO_NOT_DISTURB) {
            LingMouForegroundService.start(getApplication(), mode)
        }
    }

    fun setPreferCloudAsr(enabled: Boolean) {
        LingMouEngine.setPreferCloudAsr(enabled)
        LingMouEngine.log(if (enabled) "语音后端切换为云端 ASR" else "语音后端切换为系统内置")
        restartEngine()
    }

    fun onCameraPermission(granted: Boolean) = LingMouEngine.setCameraPermission(granted)

    fun onAudioPermission(granted: Boolean) = LingMouEngine.setAudioPermission(granted)

    // ---- 下面这些是「不依赖摄像头/麦克风」的手动验证入口 ----

    fun togglePlayer() = LingMouEngine.playerToggle()

    fun pauseMedia() = LingMouEngine.performMediaAction(MediaAction.PAUSE, "界面手动触发")

    fun playMedia() = LingMouEngine.performMediaAction(MediaAction.PLAY, "界面手动触发")

    fun rewindMedia() = LingMouEngine.performMediaAction(MediaAction.REWIND, "界面手动触发", 10)

    fun simulateSpeech(text: String) = LingMouEngine.onSpeechText(text)

    fun runCommandSelfTest() = LingMouEngine.runCommandSelfTest()

    fun refreshMediaSession() = LingMouEngine.diagnoseMediaSession()

    fun cancelReminder(id: Int) = LingMouEngine.cancelReminder(id)

    fun clearLog() = LingMouEngine.clearLog()
}
