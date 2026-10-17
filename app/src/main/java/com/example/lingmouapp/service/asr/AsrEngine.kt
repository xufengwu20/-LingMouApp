package com.example.lingmouapp.service.asr

/**
 * 语音识别后端的统一接口。
 *
 * 目前有三个实现：
 *  - [VoskSpeechService]     本地离线识别（默认），无网络要求、延迟低
 *  - [SystemAsrEngine]       设备内置 SpeechRecognizer，离线可用、无需 Key
 *  - [SiliconFlowAsrEngine]  硅基流动云端 ASR，需要 API Key（可选的高精度模式）
 *
 * 换成 Whisper.cpp / sherpa-onnx 时，只要再实现一个 AsrEngine 即可，
 * 上层（LingMouForegroundService / LingMouEngine）不用改。
 */
interface AsrEngine {

    /** 展示在界面上的后端名称 */
    val displayName: String

    val isListening: Boolean

    /**
     * 开始持续监听。
     *
     * @param onText    每识别出**完整一句话**调用一次（喂给指令解析）
     * @param onError   把问题显示到界面上
     * @param onPartial 部分识别结果（正在说的半句话），只用于实时显示，
     *                  **不喂给指令解析** —— 避免半句话触发控制动作。
     *                  系统/云端实现可以不支持（忽略即可）。
     */
    fun start(
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        onPartial: ((String) -> Unit)? = null
    )

    fun stop()

    fun release()
}
