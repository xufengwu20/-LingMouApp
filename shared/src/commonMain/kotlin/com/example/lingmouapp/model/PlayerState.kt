package com.example.lingmouapp.model

/**
 * 「视频播放占位区」的状态。
 *
 * 这么设计是为了让每一条媒体指令都肉眼可见：
 * 只要发出暂停/播放/回退，占位播放器一定会跟着变，
 * 同时日志里会写清「是否真的下发给了系统媒体会话」。
 * 这样即使手边没有 B 站/华为视频在播，也能验证整条链路。
 */
data class PlayerState(
    val title: String = "视频播放占位区",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 30 * 60 * 1000L
) {
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun positionText(): String = formatMillis(positionMs)

    fun durationText(): String = formatMillis(durationMs)

    companion object {
        fun formatMillis(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val m = total / 60
            val s = total % 60
            // 不用 String.format：commonMain 里没有 JVM 的 "%02d".format(...)
            val mm = m.toString().padStart(2, '0')
            val ss = s.toString().padStart(2, '0')
            return "$mm:$ss"
        }
    }
}
