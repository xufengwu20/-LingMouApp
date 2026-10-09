package com.example.lingmouapp.model

/** 语音指令解析结果。当前是关键词匹配的简单实现。 */
sealed interface SpeechCommand {

    /** 原始识别文本，便于界面回显与排查 */
    val raw: String

    data class Pause(override val raw: String) : SpeechCommand

    data class Resume(override val raw: String) : SpeechCommand

    data class Rewind(val seconds: Int, override val raw: String) : SpeechCommand

    /** 从文本里抽出的定时提醒，例如「15分钟」「半小时」 */
    data class SetTimer(
        val durationMs: Long,
        val label: String,
        override val raw: String
    ) : SpeechCommand

    data class Unknown(override val raw: String) : SpeechCommand

    fun describe(): String = when (this) {
        is Pause -> "暂停播放"
        is Resume -> "继续播放"
        is Rewind -> "回退 ${seconds} 秒"
        is SetTimer -> "定时提醒：$label（${durationMs / 1000} 秒后）"
        is Unknown -> "未匹配到指令"
    }
}
