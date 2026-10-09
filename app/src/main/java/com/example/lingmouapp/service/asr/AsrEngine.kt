package com.example.lingmouapp.service.asr

/**
 * 语音识别后端的统一接口。
 *
 * 目前有两个实现：
 *  - [SystemAsrEngine]      设备内置 SpeechRecognizer，离线可用、无需 Key
 *  - [SiliconFlowAsrEngine] 硅基流动云端 ASR，需要 API Key
 *
 * 换成 Whisper.cpp / sherpa-onnx 时，只要再实现一个 AsrEngine 即可，
 * 上层（LingMouForegroundService）不用改。
 */
interface AsrEngine {

    /** 展示在界面上的后端名称 */
    val displayName: String

    val isListening: Boolean

    /**
     * 开始持续监听。
     * [onText] 每识别出一句话调用一次；[onError] 用于把问题显示到界面上。
     */
    fun start(onText: (String) -> Unit, onError: (String) -> Unit)

    fun stop()

    fun release()
}
