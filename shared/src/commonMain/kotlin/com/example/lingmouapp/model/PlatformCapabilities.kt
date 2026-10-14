package com.example.lingmouapp.model

/**
 * 当前平台支持哪些能力。
 *
 * 为什么要显式建模而不是在 UI 里写 `if (isAndroid)`：
 * 共享的 Compose 组件需要「知道自己该显示什么、该标注什么」，
 * 但它不能也不该知道有哪些平台存在。把能力做成数据之后，
 * 组件只需要读这些布尔值就能把「当前平台暂不支持」画出来，
 * 以后再加第三个平台也不用改 UI。
 */
data class PlatformCapabilities(
    val platformName: String,

    /** 实时摄像头画面 */
    val camera: Boolean,
    /** 人脸检测 / 视线追踪 */
    val faceDetection: Boolean,
    /** 头部动作控制 */
    val headGesture: Boolean,
    /** 麦克风 + 云端语音识别 */
    val cloudAsr: Boolean,
    /** 云端内容分析（笔记 / 菜谱） */
    val cloudContentAnalysis: Boolean,
    /** 跨应用媒体控制（下发给别的播放器） */
    val crossAppMediaControl: Boolean,
    /** 进程被杀后仍能触发的定时提醒 */
    val backgroundAlarm: Boolean,
    /** 读取系统媒体会话 */
    val mediaSessionQuery: Boolean,
    /** 通知使用权这类特殊权限 */
    val specialAccessSettings: Boolean,
    /** 运行时权限（相机/麦克风/通知） */
    val runtimePermissions: Boolean,

    /** 这个平台上「用不了的」能力清单，UI 直接逐条展示 */
    val unsupported: List<UnsupportedFeature>
) {
    data class UnsupportedFeature(val title: String, val reason: String)

    companion object {
        val ANDROID = PlatformCapabilities(
            platformName = "Android",
            camera = true,
            faceDetection = true,
            headGesture = true,
            cloudAsr = true,
            cloudContentAnalysis = true,
            crossAppMediaControl = true,
            backgroundAlarm = true,
            mediaSessionQuery = true,
            specialAccessSettings = true,
            runtimePermissions = true,
            unsupported = emptyList()
        )

        /**
         * Windows 桌面端。
         *
         * 这里列的都是**真实的平台差异**，不是为了省事而留白：
         *   - 人脸检测：桌面端没有 ML Kit，也没有接入 OpenCV（本轮明确不做）
         *   - 跨应用媒体控制：MediaSession 是 Android 专有 API；桌面端要控制别的
         *     播放器只能模拟系统媒体键（需 JNativeHook 这类原生库）或走 SMTC
         *   - 后台提醒：ScheduledExecutorService 只在进程存活期间有效
         */
        val WINDOWS = PlatformCapabilities(
            platformName = "Windows（桌面端）",
            camera = false,
            faceDetection = false,
            headGesture = false,
            cloudAsr = false,
            cloudContentAnalysis = false,
            crossAppMediaControl = false,
            backgroundAlarm = false,
            mediaSessionQuery = false,
            specialAccessSettings = false,
            runtimePermissions = false,
            unsupported = listOf(
                UnsupportedFeature(
                    "人脸检测 / 视线追踪",
                    "桌面端未接入 ML Kit 或 OpenCV，摄像头与 AI 检测本轮不做"
                ),
                UnsupportedFeature(
                    "头部动作控制",
                    "依赖人脸关键点，随人脸检测一并暂不支持"
                ),
                UnsupportedFeature(
                    "麦克风语音识别（含 AI 字幕）",
                    "桌面端未接入音频采集与云端 ASR"
                ),
                UnsupportedFeature(
                    "云端笔记 / 菜谱生成",
                    "桌面端暂未接入硅基流动 LLM，知识库仍可查看 Android 端写入的历史"
                ),
                UnsupportedFeature(
                    "跨应用媒体控制",
                    "MediaSession 是 Android 专有 API；桌面端只用内置占位播放器演示闭环"
                ),
                UnsupportedFeature(
                    "关机后仍生效的定时提醒",
                    "ScheduledExecutorService 仅在进程存活期间有效；"
                        + "要做到系统级需注册 Windows 任务计划（需要额外权限）"
                )
            )
        )
    }
}
