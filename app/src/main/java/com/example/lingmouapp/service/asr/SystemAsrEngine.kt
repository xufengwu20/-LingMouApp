package com.example.lingmouapp.service.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * 设备内置语音识别。
 *
 * SpeechRecognizer 一次只能识别一句话，所以这里用「识别结束/超时后自动重启」的方式
 * 模拟持续监听。大多数错误（没听清、超时、引擎忙）都是常态，直接重启而不是报错。
 */
class SystemAsrEngine(private val context: Context) : AsrEngine {

    override val displayName: String = "系统内置 SpeechRecognizer"

    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var onTextCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    @Volatile
    override var isListening: Boolean = false
        private set

    private val restartRunnable = Runnable { startListeningInternal() }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            Log.i(TAG, "检测到说话开始")
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            Log.i(TAG, "说话结束，等待识别结果")
        }
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            if (!isListening) return
            Log.w(TAG, "onError 错误码=$error（${errorName(error)}）")
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    isListening = false
                    onErrorCallback?.invoke("系统语音识别缺少麦克风权限")
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    isListening = false
                    onErrorCallback?.invoke("系统语音识别客户端错误（错误码 $error）")
                }
                else -> scheduleRestart()
            }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = texts?.firstOrNull()?.trim().orEmpty()
            // 关键节点：识别文本 → 上抛（不管识别出什么都要能看见）
            Log.i(TAG, "识别结果（${texts?.size ?: 0} 条）：「$best」")
            if (best.isNotEmpty()) onTextCallback?.invoke(best)
            if (isListening) scheduleRestart()
        }
    }

    override fun start(
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        onPartial: ((String) -> Unit)?
    ) {
        onTextCallback = onText
        onErrorCallback = onError

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("这台设备没有可用的系统语音识别服务，请在 local.properties 配置硅基流动 API Key 后改用云端 ASR")
            return
        }

        isListening = true
        handler.post {
            ensureRecognizer()
            startListeningInternal()
        }
    }

    private fun ensureRecognizer() {
        if (recognizer == null) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(listener)
                }
            } catch (t: Throwable) {
                // 防御：系统语音服务缺失/损坏时 createSpeechRecognizer 可能抛异常，
                // 绝不能让 App 崩溃 —— 复位标志并上报，由上层决定下一步。
                Log.w(TAG, "创建 SpeechRecognizer 失败：${t.javaClass.simpleName} ${t.message}")
                isListening = false
                onErrorCallback?.invoke("系统语音识别初始化失败：${t.javaClass.simpleName} ${t.message ?: ""}")
            }
        }
    }

    private fun startListeningInternal() {
        if (!isListening) return
        val engine = recognizer ?: return
        try {
            Log.i(TAG, "startListening（第 N 轮）")
            engine.startListening(buildIntent())
        } catch (e: Exception) {
            Log.w(TAG, "startListening 异常：${e.javaClass.simpleName} ${e.message}")
            scheduleRestart()
        }
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO(音频错误)"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH(没听清)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY(引擎忙)"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT(说话超时)"
        else -> "未知($code)"
    }

    private fun scheduleRestart() {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, RESTART_DELAY_MS)
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    override fun stop() {
        isListening = false
        handler.removeCallbacks(restartRunnable)
        val engine = recognizer ?: return
        handler.post {
            try {
                engine.stopListening()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun release() {
        isListening = false
        handler.removeCallbacks(restartRunnable)
        val engine = recognizer
        recognizer = null
        handler.post {
            try {
                engine?.destroy()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    companion object {
        /** logcat 过滤：adb logcat -s LingMouSysAsr */
        const val TAG = "LingMouSysAsr"
        private const val RESTART_DELAY_MS = 400L
    }
}
