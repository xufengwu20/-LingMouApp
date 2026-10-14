package com.example.lingmouapp.model

/**
 * 三种工作模式。同一时刻只有一种模式生效，
 * 摄像头 / 麦克风的归属与开关完全由模式决定。
 */
enum class AppMode(
    val label: String,
    val detail: String,
    val usesCamera: Boolean,
    val usesMicrophone: Boolean
) {
    FOCUS(
        label = "专注模式",
        detail = "摄像头检测视线，离开屏幕 3 秒自动暂停视频；≥2 人时提醒",
        usesCamera = true,
        usesMicrophone = false
    ),
    COOKING(
        label = "烹饪模式",
        detail = "语音指令 + 点头/摇头控制 + 语音定时提醒",
        usesCamera = true,
        usesMicrophone = true
    ),
    DO_NOT_DISTURB(
        label = "勿扰模式",
        detail = "关闭摄像头与麦克风，不做任何 AI 检测",
        usesCamera = false,
        usesMicrophone = false
    );

    companion object {
        fun fromName(value: String?): AppMode =
            entries.firstOrNull { it.name == value } ?: DO_NOT_DISTURB
    }
}
