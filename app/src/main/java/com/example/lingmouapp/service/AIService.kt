package com.example.lingmouapp.service

import com.example.lingmouapp.BuildConfig
import com.example.lingmouapp.shared.RecipeDraft
import com.example.lingmouapp.shared.RecipeStepDraft
import com.example.lingmouapp.shared.VideoSummary
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class TranscriptionResponse(val text: String = "")

/**
 * 硅基流动（SiliconFlow）语音识别 API 封装。
 *
 * 职责边界（刻意与录音/编排分开）：
 *   - 这里只负责「把一段 WAV 发出去、把文本拿回来」
 *   - 录音、静音切句、循环重试在 [com.example.lingmouapp.service.asr.SiliconFlowAsrEngine]
 *
 * 设计原则：**任何异常都收敛成返回值，绝不抛给调用方**。
 * 这是「不允许因为 API Key 缺失或网络问题导致 App 崩溃或功能阻塞」的落点之一 ——
 * [transcribeWav] 返回 `Result`，`isConfigured` 为 false 时连 HTTP 客户端都不会创建。
 */
class AIService(
    private val apiKey: String = BuildConfig.SILICONFLOW_API_KEY,
    private val endpoint: String = BuildConfig.SILICONFLOW_ENDPOINT,
    val model: String = BuildConfig.SILICONFLOW_ASR_MODEL,
    val llmModel: String = BuildConfig.SILICONFLOW_LLM_MODEL,
    private val llmEndpoint: String = BuildConfig.SILICONFLOW_LLM_ENDPOINT,
    private val requestTimeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * 懒加载：没配 Key 时根本不会构造 OkHttp 客户端，
     * 也就不会产生任何线程池 / 连接池开销。
     */
    private val lazyClient: Lazy<HttpClient> = lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = requestTimeoutMs
                socketTimeoutMillis = requestTimeoutMs
            }
        }
    }

    private val client: HttpClient get() = lazyClient.value

    /**
     * 上传一段 WAV/PCM（16kHz 单声道），返回识别文本。
     *
     * @return 成功时为去掉首尾空白的文本；失败时为 `Result.failure`，调用方据此决定是否降级。
     */
    suspend fun transcribeWav(wav: ByteArray): Result<String> {
        if (wav.isEmpty()) {
            return Result.failure(IllegalArgumentException("音频为空"))
        }
        if (!isConfigured) {
            return Result.failure(IllegalStateException("未配置 SILICONFLOW_API_KEY"))
        }
        return try {
            val response = client.submitFormWithBinaryData(
                url = endpoint,
                formData = formData {
                    append("model", model)
                    append(
                        "file",
                        wav,
                        Headers.build {
                            append(HttpHeaders.ContentType, "audio/wav")
                            append(
                                HttpHeaders.ContentDisposition,
                                "filename=\"utterance.wav\""
                            )
                        }
                    )
                }
            ) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }

            if (response.status.value !in 200..299) {
                Log.i(TAG, "transcribe HTTP ${response.status.value} model=$model size=${wav.size}B")
                Result.failure(IllegalStateException("HTTP ${response.status.value}"))
            } else {
                val text = response.body<TranscriptionResponse>().text.trim()
                Log.i(TAG, "transcribe OK len=${text.length}")
                Result.success(text)
            }
        } catch (e: CancellationException) {
            // 协程被主动取消（停止识别 / 更换引擎）是**正常生命周期**，不是失败。
            // 绝不能把它转成 Result.failure —— 否则上层会误报「云端识别失败(1/3)」，
            // 连续三次还会误触发降级，把好好的云端识别弄残。
            throw e
        } catch (e: Exception) {
            // 网络超时、DNS 失败、TLS 失败、JSON 解析失败…… 全部收敛到这里
            Log.w(TAG, "transcribe 失败：${e.javaClass.simpleName} ${e.message}")
            Result.failure(e)
        }
    }

    fun close() {
        // 没被初始化过就什么都不用做，避免为了关闭而反过来创建它
        if (!lazyClient.isInitialized()) return
        try {
            lazyClient.value.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ============================================================
    // 云端内容分析（LLM）
    //
    // 与 ASR 共用同一个 Ktor 客户端与同一把 API Key，但用不同的 endpoint / 模型。
    // 同样遵守「异常全部收敛成 Result」的原则：没 Key、超时、模型乱返回都不会崩。
    // ============================================================

    /**
     * 把一段视频文字稿总结成学习笔记。
     *
     * @return 成功时是结构化结果；失败时携带原因（未配置 Key / HTTP 错误 / JSON 解析失败）
     */
    suspend fun summarizeVideo(transcript: String): Result<VideoSummary> {
        if (transcript.isBlank()) {
            return Result.failure(IllegalArgumentException("文字稿为空，无法总结"))
        }
        return runChat(SUMMARY_SYSTEM_PROMPT, transcript).mapCatching { raw ->
            val payload = lenientJson.decodeFromString<SummaryPayload>(extractJsonObject(raw))
            VideoSummary(
                title = payload.title.ifBlank { DEFAULT_SUMMARY_TITLE },
                summary = payload.summary,
                keyPoints = payload.keyPoints
            )
        }
    }

    /** 从文字稿里抽取菜谱（标题 / 简介 / 分步骤） */
    suspend fun extractRecipe(transcript: String): Result<RecipeDraft> {
        if (transcript.isBlank()) {
            return Result.failure(IllegalArgumentException("文字稿为空，无法抽取菜谱"))
        }
        return runChat(RECIPE_SYSTEM_PROMPT, transcript).mapCatching { raw ->
            val payload = lenientJson.decodeFromString<RecipePayload>(extractJsonObject(raw))
            RecipeDraft(
                title = payload.title.ifBlank { DEFAULT_RECIPE_TITLE },
                summary = payload.summary,
                steps = payload.steps
                    .filter { it.instruction.isNotBlank() }
                    .map { RecipeStepDraft(it.instruction, it.durationHint) }
            )
        }
    }

    /**
     * 一次对话补全。
     *
     * 刻意用 `response_format = json_object` 约束输出，并且**在 prompt 里再写一遍**
     * JSON 结构 —— 不是所有模型/版本都严格遵守 response_format，
     * 双保险 + [extractJsonObject] 的容错解析，才能稳定拿到可解析的结果。
     */
    private suspend fun runChat(systemPrompt: String, userContent: String): Result<String> {
        if (!isConfigured) {
            return Result.failure(IllegalStateException("未配置 SILICONFLOW_API_KEY"))
        }
        return try {
            val response = client.post(llmEndpoint) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(
                    ChatRequest(
                        model = llmModel,
                        messages = listOf(
                            ChatMessage("system", systemPrompt),
                            ChatMessage("user", userContent.take(MAX_TRANSCRIPT_CHARS))
                        ),
                        temperature = 0.3,
                        maxTokens = 1024,
                        responseFormat = ResponseFormat("json_object")
                    )
                )
            }

            if (response.status.value !in 200..299) {
                return Result.failure(IllegalStateException("HTTP ${response.status.value}"))
            }
            val content = response.body<ChatResponse>()
                .choices.firstOrNull()?.message?.content
            if (content.isNullOrBlank()) {
                Result.failure(IllegalStateException("模型返回内容为空"))
            } else {
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从模型回复里抠出 JSON 对象。
     *
     * 即使要求了 json_object，模型仍可能包一层 ```json 代码块或加前后缀说明文字，
     * 所以这里做「去围栏 + 取第一个 { 到最后一个 }」的容错。
     */
    private fun extractJsonObject(raw: String): String {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /** logcat 过滤：adb logcat -s LingMouAsr */
        const val TAG = "LingMouAsr"

        /** 文字稿太长会拖慢响应也更容易超时，截断到合理长度 */
        private const val MAX_TRANSCRIPT_CHARS = 8_000

        private const val DEFAULT_SUMMARY_TITLE = "视频学习笔记"
        private const val DEFAULT_RECIPE_TITLE = "未命名菜谱"

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        private val SUMMARY_SYSTEM_PROMPT = """
            你是学习笔记助手。用户会给你一段视频的语音转写文字稿（可能有错别字、断句混乱）。
            请提炼成一份中文学习笔记。
            只输出一个 JSON 对象，不要输出任何解释、不要用 Markdown 代码块，格式严格如下：
            {"title":"不超过20字的标题","summary":"150字以内的一段总结","key_points":["要点1","要点2","要点3"]}
            如果文字稿内容太少或没有实质信息，就把 summary 写成「文字稿信息不足，未能提炼有效内容」，key_points 用空数组。
        """.trimIndent()

        private val RECIPE_SYSTEM_PROMPT = """
            你是菜谱整理助手。用户会给你一段做菜视频的语音转写文字稿（可能有错别字）。
            请抽取成一份中文菜谱，步骤要按视频里讲的先后顺序。
            只输出一个 JSON 对象，不要输出任何解释、不要用 Markdown 代码块，格式严格如下：
            {"title":"菜名","summary":"一句话简介","steps":[{"instruction":"这一步做什么","duration_hint":"15分钟"}]}
            duration_hint 只填文字稿里明确提到的时长，没提到就填空字符串。
            如果文字稿里没有做菜内容，title 填空字符串，steps 用空数组。
        """.trimIndent()
    }
}

// ---------------------------------------------------------------- LLM 报文

@Serializable
private data class ChatMessage(val role: String, val content: String)

/**
 * 注意：**所有字段都不带默认值**。
 * kotlinx.serialization 默认不编码「等于默认值」的字段，
 * 如果给 maxTokens / responseFormat 设默认值，它们就不会出现在请求体里，
 * JSON 模式也就失效了。
 */
@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("response_format") val responseFormat: ResponseFormat
)

@Serializable
private data class ResponseFormat(val type: String)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
private data class ChatChoice(val message: ChatMessage? = null)

@Serializable
private data class SummaryPayload(
    val title: String = "",
    val summary: String = "",
    @SerialName("key_points") val keyPoints: List<String> = emptyList()
)

@Serializable
private data class RecipePayload(
    val title: String = "",
    val summary: String = "",
    val steps: List<RecipeStepPayload> = emptyList()
)

@Serializable
private data class RecipeStepPayload(
    val instruction: String = "",
    @SerialName("duration_hint") val durationHint: String = ""
)
