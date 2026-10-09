package com.example.lingmouapp.model

/**
 * 一次「系统当前媒体会话」的快照。
 *
 * 之前界面只显示一句「有没有目标」，用户没法判断到底卡在哪一步。
 * 拆成下面几个字段后，失败原因一眼可见：
 *   没授权 / 授权了但确实没有会话在播 / 找到了但选错目标。
 */
data class MediaSessionSnapshot(
    /** 给界面直接显示的整句描述 */
    val summary: String,
    /** 可用会话数量（已排除本应用自己） */
    val sessionCount: Int = 0,
    /** 当前会被下发指令的目标包名 */
    val targetPackage: String? = null,
    /** 目标会话的播放状态可读名 */
    val targetState: String? = null,
    /** 所有会话的清单，写日志用 */
    val detail: String = ""
) {
    val usable: Boolean get() = targetPackage != null
}
