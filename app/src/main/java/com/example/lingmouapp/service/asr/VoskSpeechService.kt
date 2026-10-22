package com.example.lingmouapp.service.asr

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors

/**
 * Vosk 本地离线语音识别（Android 端）。
 *
 * 为什么放在 :app 而不是 :shared：
 *   - `vosk-android` 是 Android AAR（内含各 ABI 的 libvosk.so），**不是 KMP 构件**；
 *   - 只有 Android 端用语音（桌面端维持「暂不支持」）；
 *   - 放在 :app 完全绕开「KMP 共享模块加载原生库」这一整类问题。
 *
 * 流程：
 *   1. 首次启动时把 assets/models/ 里的中文小模型**自行解包**到内部存储（一次性）。
 *      特意不用 vosk 的 StorageService：它的回调跑在它自己起的线程里，
 *      回调一旦抛异常会直接崩掉整个进程；自己实现解包，全程可控、可 try-catch；
 *   2. AudioRecord 采集 16kHz / 单声道 / PCM16；
 *   3. 单线程循环 `acceptWaveForm(buffer, len)`；
 *   4. 每句话的最终结果（recognizer.result 的 JSON `text`）回调 [AsrEngine] 的 onText，
 *      部分结果（partialResult 的 JSON `partial`）回调 onPartial —— 界面上的
 *      「最近识别」用 partial 实时刷新（说完到显示 ≈ 一个读缓冲周期，百毫秒级），
 *      指令解析只消费 final，避免半句话触发控制动作。
 *
 * 注意：`start()` 内部的异常**包括 Error（如原生库加载失败的 UnsatisfiedLinkError）**
 * 全部收敛成 onError，绝不外抛、绝不让 App 崩溃；
 * stop/release 幂等，资源在识别线程的 finally 里释放。
 */
class VoskSpeechService(
    private val context: Context,
    private val assetModelPath: String = DEFAULT_ASSET_MODEL_PATH,
    private val sampleRate: Float = DEFAULT_SAMPLE_RATE
) : AsrEngine {

    override val displayName: String = "本地离线识别（Vosk 中文小模型）"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vosk-asr").apply { isDaemon = true }
    }

    @Volatile
    private var model: Model? = null

    @Volatile
    private var recognizer: Recognizer? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    override var isListening: Boolean = false
        private set

    private var onTextCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onPartialCallback: ((String) -> Unit)? = null

    // ------------------------------------------------------------------ 生命周期

    override fun start(
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        onPartial: ((String) -> Unit)?
    ) {
        onTextCallback = onText
        onErrorCallback = onError
        onPartialCallback = onPartial
        if (isListening) return
        isListening = true
        try {
            executor.execute { runLoop() }
        } catch (t: Throwable) {
            // 线程池拒绝执行等极端情况也不允许外抛
            Log.w(TAG, "启动识别线程失败：${t.javaClass.simpleName} ${t.message}")
            isListening = false
            onErrorCallback?.invoke("本地识别启动失败：${t.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        try {
            ensureModel()
            val rec = recognizer ?: throw IllegalStateException("Vosk Recognizer 初始化失败")

            // 100ms 一个读周期：partial 刷新界面和「说完→出字」的延迟都取决于它
            val bufferBytes = DEFAULT_SAMPLE_RATE.toInt() / 10 * 2
            val minBuffer = AudioRecord.getMinBufferSize(
                DEFAULT_SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val buffer = ByteArray(maxOf(minBuffer * 2, bufferBytes))

            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                DEFAULT_SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer.size
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                throw IllegalStateException("AudioRecord 初始化失败（state=${audio.state}）")
            }
            record = audio
            audio.startRecording()
            Log.i(TAG, "开始监听（16kHz 单声道 PCM16，读缓冲 ${buffer.size}B）")

            while (isListening) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                if (rec.acceptWaveForm(buffer, read)) {
                    rec.result?.let { emitFinal(it) }
                }
                rec.partialResult?.let { emitPartial(it) }
            }
        } catch (t: Throwable) {
            // 防御：连 UnsatisfiedLinkError（原生库加载失败）、OutOfMemoryError 这类
            // Error 也要收敛 —— 模型/原生库出任何问题都不能让 App 崩溃，只静默降级。
            Log.w(TAG, "识别循环异常：${t.javaClass.simpleName} ${t.message}")
            // 关键：异常退出**必须**复位 isListening —— 否则引擎侧的幂等守卫会认为
            // 「仍在监听」，永远挡掉重启，语音彻底失声（这是「完全没有反应」的根因之一）。
            val wasListening = isListening
            isListening = false
            if (wasListening) {
                onErrorCallback?.invoke("本地识别异常：${t.javaClass.simpleName} ${t.message ?: ""}")
            }
        } finally {
            stopInternal()
        }
    }

    override fun stop() {
        isListening = false
        try {
            record?.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun release() {
        stop()
        // 识别线程的 finally 会完成 recognizer/model/record 的释放
    }

    // ------------------------------------------------------------------ 结果

    private fun emitFinal(json: String) {
        val text = try {
            JSONObject(json).optString("text", "").trim()
        } catch (e: Exception) {
            ""
        }
        if (text.isNotEmpty()) {
            Log.i(TAG, "最终识别结果：「$text」")
            onTextCallback?.invoke(text)
            // 关键节点：证明交付指令后**没有**关掉监听 —— 循环继续，下一句照常识别
            Log.i(TAG, "已交付指令解析 —— 识别循环未退出，继续监听下一句")
        }
    }

    private fun emitPartial(json: String) {
        val text = try {
            JSONObject(json).optString("partial", "").trim()
        } catch (e: Exception) {
            ""
        }
        if (text.isNotEmpty()) {
            onPartialCallback?.invoke(text)
        }
    }

    // ------------------------------------------------------------------ 资源

    /**
     * 自行把 assets 里的模型解包到内部存储（一次性，约几十 MB）并创建 Recognizer。
     *
     * 全部步骤都在 try/catch 内：解包失败、Model/Recognizer 构造失败、
     * 原生库加载失败（UnsatisfiedLinkError）一律包装成 IllegalStateException 抛出，
     * 由 runLoop 的 catch(Throwable) 收敛 → onError → 上层静默降级到系统识别。
     * 绝不向上抛 Error、绝不让进程崩。
     */
    private fun ensureModel() {
        if (model != null) return
        try {
            val targetDir = File(context.filesDir, UNPACKED_NAME)
            if (!targetDir.isDirectory || !File(targetDir, "am").isDirectory) {
                copyAssetsDir(context.assets, assetModelPath, targetDir)
            }
            model = Model(targetDir.absolutePath)
            recognizer = Recognizer(model!!, sampleRate)
            Log.i(TAG, "模型加载完成（$UNPACKED_NAME）")
        } catch (t: Throwable) {
            throw IllegalStateException("模型加载失败：${t.javaClass.simpleName} ${t.message ?: ""}")
        }
    }

    /** 递归复制 assets 目录（文件 + 子目录）到目标位置 */
    private fun copyAssetsDir(assets: AssetManager, srcPath: String, destDir: File) {
        val children = assets.list(srcPath) ?: return
        if (children.isEmpty()) return
        destDir.mkdirs()
        for (child in children) {
            val childPath = "$srcPath/$child"
            val childDest = File(destDir, child)
            val sub = assets.list(childPath)
            if (sub != null && sub.isNotEmpty()) {
                copyAssetsDir(assets, childPath, childDest)
            } else {
                assets.open(childPath).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun stopInternal() {
        // 无论什么原因走到这里，监听标志都必须复位（幂等，重复置 false 无害）
        isListening = false
        // 停之前把残余音频的最终结果也吐出来，避免丢掉半句话
        val rec = recognizer
        try {
            rec?.finalResult?.let { json ->
                val text = try {
                    JSONObject(json).optString("text", "").trim()
                } catch (e: Exception) {
                    ""
                }
                if (text.isNotEmpty()) onTextCallback?.invoke(text)
            }
        } catch (e: Exception) {
            // ignore
        }
        try {
            record?.release()
        } catch (e: Exception) {
            // ignore
        }
        record = null
        try {
            recognizer?.close()
        } catch (e: Exception) {
            // ignore
        }
        recognizer = null
        try {
            model?.close()
        } catch (e: Exception) {
            // ignore
        }
        model = null
        Log.i(TAG, "已停止监听，资源已释放")
    }

    companion object {
        /** logcat 过滤：adb logcat -s LingMouVosk */
        const val TAG = "LingMouVosk"

        const val DEFAULT_SAMPLE_RATE = 16_000f

        /** assets 里的模型路径（相对 assets/） */
        const val DEFAULT_ASSET_MODEL_PATH = "models/vosk-model-small-cn-0.22"

        /** 解包到内部存储后的目录名 */
        const val UNPACKED_NAME = "vosk-model-small-cn-0.22"

        /** 模型是否已经解包可用（不加载，只看文件，可在主线程调用） */
        fun modelReady(context: Context): Boolean {
            val dir = File(context.filesDir, UNPACKED_NAME)
            return dir.isDirectory && File(dir, "am").isDirectory
        }
    }
}
