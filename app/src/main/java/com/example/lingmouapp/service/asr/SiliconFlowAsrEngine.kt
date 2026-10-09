package com.example.lingmouapp.service.asr

import com.example.lingmouapp.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class TranscriptionResponse(val text: String = "")

/**
 * 硅基流动（SiliconFlow）云端语音识别，走 Ktor + OkHttp。
 *
 * 流程：AudioRecord 录一段 → 本地静音检测切句 → multipart 上传 → 拿回 text。
 * API Key 从 local.properties 的 SILICONFLOW_API_KEY 经 BuildConfig 注入，
 * 不写死在源码里。
 */
class SiliconFlowAsrEngine(
    private val apiKey: String,
    private val endpoint: String = BuildConfig.SILICONFLOW_ENDPOINT,
    private val model: String = BuildConfig.SILICONFLOW_MODEL
) : AsrEngine {

    override val displayName: String = "硅基流动云端 ASR（$model）"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder = WavRecorder()

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var loopJob: Job? = null

    @Volatile
    override var isListening: Boolean = false
        private set

    override fun start(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (isListening) return
        if (apiKey.isBlank()) {
            onError("未配置硅基流动 API Key（local.properties 里的 SILICONFLOW_API_KEY）")
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
                    delay(NO_VOICE_DELAY_MS)
                    continue
                }
                try {
                    val text = transcribe(wav)
                    if (text.isNotBlank()) onText(text.trim())
                } catch (e: Exception) {
                    onError("云端识别失败：${e.message ?: e.javaClass.simpleName}")
                    delay(ERROR_RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun transcribe(wav: ByteArray): String {
        val response = client.submitFormWithBinaryData(
            url = endpoint,
            formData = formData {
                append("model", model)
                append(
                    "file",
                    wav,
                    Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"utterance.wav\"")
                    }
                )
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }

        if (response.status.value !in 200..299) {
            throw IllegalStateException("HTTP ${response.status.value}")
        }
        return response.body<TranscriptionResponse>().text
    }

    override fun stop() {
        isListening = false
        loopJob?.cancel()
        loopJob = null
    }

    override fun release() {
        stop()
        try {
            client.close()
        } catch (e: Exception) {
            // ignore
        }
        scope.cancel()
    }

    companion object {
        private const val NO_VOICE_DELAY_MS = 300L
        private const val ERROR_RETRY_DELAY_MS = 1_500L
    }
}
