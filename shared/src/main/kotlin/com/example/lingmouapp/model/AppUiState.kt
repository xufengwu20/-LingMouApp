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
    val headRoll: Float = 0f,

    // 头部动作控制（烹饪模式）
    /** 点头/摇头识别是否处于启用状态（烹饪模式 + 摄像头在跑时为 true） */
    val headControlEnabled: Boolean = false,
    /** 最近一次识别到的头部动作，供状态区显示 */
    val lastHeadGesture: String = "",

    // 语音
    val asrBackend: String = "未启动",
    val asrRunning: Boolean = false,
    val asrModel: String = "",
    val asrModelIsDefault: Boolean = false,
    /** 是否发生过自动降级（缺 Key 或云端连续失败），用于界面高亮提示 */
    val asrDegraded: Boolean = false,
    /** 最近一条识别相关的提示 / 错误，直接显示给用户 */
    val asrNote: String = "",
    val lastTranscript: String = "",
    val lastCommand: String = "",
    val preferCloudAsr: Boolean = false,
    val siliconFlowKeyConfigured: Boolean = false,

    // 媒体
    val lastMediaAction: String = "",
    val mediaSessionSummary: String = "未检测",
    val mediaSessionCount: Int = 0,
    val player: PlayerState = PlayerState(),

    // AI 字幕（数据来源就是语音识别的原文，不额外占用麦克风）
    val captionEnabled: Boolean = false,
    /** 实时字幕文本；未开启时恒为空 */
    val captionText: String = "",
    /** 字幕相关的提示（为什么开启失败 / 为什么被关掉） */
    val captionNote: String = "",

    // 本地指令自检（完全不经过麦克风与 API Key）
    val commandSelfTests: List<CommandSelfTest> = emptyList(),

    // 会话文字稿与知识库
    /** 当前会话累计到的文字稿条数（语音识别每出一句 +1） */
    val transcriptLines: Int = 0,
    /** 是否正在调用 LLM 生成内容 */
    val isGenerating: Boolean = false,
    /** 生成结果 / 失败原因，直接显示给用户 */
    val generationNote: String = "",
    /** 是否正在展示「笔记/菜谱」页面 */
    val showKnowledgeBase: Boolean = false,
    /** 数据库是否可用 */
    val databaseReady: Boolean = false,
    val notes: List<NoteItem> = emptyList(),
    val recipes: List<RecipeItem> = emptyList(),

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
