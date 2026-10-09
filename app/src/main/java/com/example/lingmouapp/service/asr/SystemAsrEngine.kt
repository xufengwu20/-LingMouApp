package com.example.lingmouapp.service.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            if (!isListening) return
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
            if (best.isNotEmpty()) onTextCallback?.invoke(best)
            if (isListening) scheduleRestart()
        }
    }

    override fun start(onText: (String) -> Unit, onError: (String) -> Unit) {
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
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        }
    }

    private fun startListeningInternal() {
        if (!isListening) return
        val engine = recognizer ?: return
        try {
            engine.startListening(buildIntent())
        } catch (e: Exception) {
            scheduleRestart()
        }
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
        private const val RESTART_DELAY_MS = 400L
    }
}
