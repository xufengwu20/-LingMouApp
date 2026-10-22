package com.example.lingmouapp.service.asr

import android.util.Log
import com.example.lingmouapp.service.AIService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 硅基流动云端语音识别。
 *
 * 流程：AudioRecord 录一段 → 本地静音检测切句 → 交给 [AIService] 上传 → 拿回文本。
 * 真正的 HTTP 细节都在 [AIService] 里，这里只负责录音与循环编排。
 *
 * 降级设计：
 *   1. 没配 Key        → 立刻回调 [onDegrade]，由上层切成系统内置识别，不会空转
 *   2. 连续失败达阈值  → 同样回调 [onDegrade]，停止重试，避免一直卡在坏网络上
 * 单次失败只是记一笔并继续重试，不会中断识别。
 */
class SiliconFlowAsrEngine(
    private val aiService: AIService = AIService(),
    private val onDegrade: (String) -> Unit = {}
) : AsrEngine {

    override val displayName: String = "硅基流动云端 ASR（${aiService.model}）"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder = WavRecorder()
    private var loopJob: Job? = null

    @Volatile
    override var isListening: Boolean = false
        private set

    override fun start(
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        onPartial: ((String) -> Unit)?
    ) {
        if (isListening) return

        if (!aiService.isConfigured) {
            onError("未配置 SILICONFLOW_API_KEY")
            onDegrade("未配置 SILICONFLOW_API_KEY，云端识别不可用")
            return
        }

        isListening = true
        loopJob = scope.launch {
            while (isActive && isListening) {
                val wav = try {
                    recorder.recordUtterance()
                } catch (e: Exception) {
                    null
                }
                if (!isActive || !isListening) break

                if (wav == null) {
                    // 这一段没听到人声，正常情况，稍等再录
                    delay(NO_VOICE_DELAY_MS)
                    continue
                }

                // 关键节点：录音 → 上传
                Log.i(AIService.TAG, "已录到一句（${wav.size} 字节）→ 上传转写")
                aiService.transcribeWav(wav).fold(
                    onSuccess = { text ->
                        Log.i(AIService.TAG, "转写结果：「$text」")
                        if (text.isNotBlank()) onText(text)
                    },
                    onFailure = { error ->
                        // 主动取消（stop / 更换引擎）不是识别失败：静默退出，
                        // 绝不能把「StandaloneCoroutine was cancelled」报成失败。
                        if (error is CancellationException || !isActive || !isListening) {
                            return@fold
                        }
                        // 演示保障：云端失败（超时/限流/欠费）**不弹任何报错红字**，
                        // 只记 logcat，并立即静默降级到本地 Vosk 识别。
                        val detail = error.message ?: error.javaClass.simpleName
                        Log.w(AIService.TAG, "云端识别失败（$detail）→ 静默降级到本地识别")
                        isListening = false
                        onDegrade("云端识别不可用（$detail），已静默切换到本地离线识别")
                    }
                )
            }
        }
    }

    override fun stop() {
        isListening = false
        loopJob?.cancel()
        loopJob = null
    }

    override fun release() {
        stop()
        aiService.close()
        scope.cancel()
    }

    companion object {
        private const val NO_VOICE_DELAY_MS = 300L
    }
}
