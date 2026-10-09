package com.example.lingmouapp.model

/** 界面唯一的数据源，由 service 层写入、viewmodel 层暴露给 ui。 */
data class AppUiState(
    val mode: AppMode = AppMode.DO_NOT_DISTURB,

    // 引擎 / 设备状态
    val engineRunning: Boolean = false,
    val cameraState: String = "未启动",
    val microphoneState: String = "未启动",

    // 人脸与视线
    val faceCount: Int = 0,
    val attention: AttentionState = AttentionState.NO_FACE,
    val awayMillis: Long = 0L,
    val headPitch: Float = 0f,
    val headYaw: Float = 0f,

    // 语音
    val asrBackend: String = "未启动",
    val lastTranscript: String = "",
    val lastCommand: String = "",
    val preferCloudAsr: Boolean = false,
    val siliconFlowKeyConfigured: Boolean = false,

    // 媒体
    val lastMediaAction: String = "",
    val mediaSessionSummary: String = "未检测",
    val mediaSessionCount: Int = 0,
    val player: PlayerState = PlayerState(),

    // 本地指令自检（完全不经过麦克风与 API Key）
    val commandSelfTests: List<CommandSelfTest> = emptyList(),

    // 定时
    val reminders: List<Reminder> = emptyList(),

    // 权限体检
    val cameraPermission: Boolean = false,
    val audioPermission: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val notificationAccess: Boolean = false,
    val exactAlarmAllowed: Boolean = false,

    // 运行日志（最新在前）
    val log: List<String> = emptyList()
) {
    val awaySeconds: Float get() = awayMillis / 1000f

    val selfTestPassed: Int get() = commandSelfTests.count { it.passed }

    val statusTitle: String
        get() = when {
            mode == AppMode.DO_NOT_DISTURB -> "勿扰模式已生效"
            !engineRunning -> "${mode.label}：引擎启动中…"
            else -> "${mode.label}运行中"
        }
}
