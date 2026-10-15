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

    /** 刷新笔记 / 菜谱列表（进入「用户」Tab 时由共享 UI 调用） */
    fun refreshKnowledge() = LingMouEngine.refreshKnowledge()

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

    /**
     * 打开云端识别（从麦克风录音并上传，识别文本通过 [LingMouEngine] 回调进状态）。
     *
     * **没有配置 SILICONFLOW_API_KEY 时不会报错、不会崩、不会卡住** ——
     * [LingMouEngine.startAsr] 会自动改用系统内置识别，并在界面上写明降级原因。
     * 云端连续失败时同样会自动降级。
     */
    fun startCloudAsr() {
        LingMouEngine.allowCloudAsrRetry()
        LingMouEngine.setPreferCloudAsr(true)
        LingMouEngine.startAsr(preferCloud = true, source = "云端识别开关")
    }

    /** 关闭云端识别：当前模式还需要麦克风就切回系统内置识别，否则直接释放 */
    fun stopCloudAsr() {
        LingMouEngine.setPreferCloudAsr(false)
        if (state.value.mode.usesMicrophone) {
            LingMouEngine.startAsr(preferCloud = false, source = "切回系统内置识别")
        } else {
            LingMouEngine.stopAsr()
        }
    }

    /** 界面上那个开关的统一入口 */
    fun setPreferCloudAsr(enabled: Boolean) {
        if (enabled) startCloudAsr() else stopCloudAsr()
    }

    // ---- 云端内容分析 + 知识库 ----

    /** 用当前会话的文字稿生成学习笔记并落库 */
    fun generateSummary() = LingMouEngine.generateSummary()

    /** 用当前会话的文字稿抽取菜谱并落库 */
    fun generateRecipe() = LingMouEngine.generateRecipe()

    fun openKnowledgeBase() = LingMouEngine.setShowKnowledgeBase(true)

    fun closeKnowledgeBase() = LingMouEngine.setShowKnowledgeBase(false)

    fun deleteNote(id: Long) = LingMouEngine.deleteNote(id)

    fun deleteRecipe(id: Long) = LingMouEngine.deleteRecipe(id)

    fun onCameraPermission(granted: Boolean) = LingMouEngine.setCameraPermission(granted)

    fun onAudioPermission(granted: Boolean) = LingMouEngine.setAudioPermission(granted)

    // ---- 下面这些是「不依赖摄像头/麦克风」的手动验证入口 ----

    fun togglePlayer() = LingMouEngine.playerToggle()

    fun pauseMedia() = LingMouEngine.performMediaAction(MediaAction.PAUSE, "界面手动触发")

    fun playMedia() = LingMouEngine.performMediaAction(MediaAction.PLAY, "界面手动触发")

    fun rewindMedia() = LingMouEngine.performMediaAction(MediaAction.REWIND, "界面手动触发", 10)

    /**
     * 手动模拟一句话。
     *
     * 走的是 [LingMouEngine.runManualCommand]，也就是**纯本地指令通道**：
     * 不经过麦克风、不经过云端 ASR、不读 API Key。识别后端没启动也照样能用。
     */
    fun simulateSpeech(text: String) = LingMouEngine.runManualCommand(text)

    fun runCommandSelfTest() = LingMouEngine.runCommandSelfTest()

    fun refreshMediaSession() = LingMouEngine.diagnoseMediaSession()

    fun cancelReminder(id: Int) = LingMouEngine.cancelReminder(id)

    fun clearLog() = LingMouEngine.clearLog()
}
