package com.example.lingmouapp.service.gesture

/**
 * 头部动作分类器（**纯 Kotlin**，不 import 任何 Android 类型）。
 *
 * 单独抽一层的理由：点头/摇头的判定规则最容易误触发，也最需要能脱离真机反复验证。
 * 时间通过 `nowMs` 注入，所以 `HeadGestureClassifierTest` 可以在 JVM 上跑完整用例。
 *
 * ── 判定规则 ───────────────────────────────────────────────
 * 一次动作 = 头部先后到达**两个方向相反的极值**，例如
 *   点头：先俯到 -20°（极值 A），再仰到 +20°（极值 B）
 *   摇头：先左转到 -25°（极值 A），再右转到 +25°（极值 B）
 *
 * 这样单次点头/摇头就能识别 —— 之前那版要求「2 次方向翻转」，
 * 等价于需要「下-上-下」三个极值，单次动作永远不会触发。
 *
 * ── 三重防误触 ─────────────────────────────────────────────
 *  1. 必须**越过阈值**才算极值（点头 ±12°，摇头 ±15°），轻微晃动直接忽略；
 *  2. 相邻两个极值的间隔必须 ≥ 250ms，滤掉单帧抖动造成的假翻转；
 *  3. 两个极值必须落在 1400ms 窗口内，且**两种动作共享 1800ms 冷却**，
 *     避免一次大幅度的斜向摆头同时判成点头 + 摇头。
 */
class HeadGestureClassifier(
    private val nodThresholdDeg: Float = DEFAULT_NOD_THRESHOLD_DEG,
    private val shakeThresholdDeg: Float = DEFAULT_SHAKE_THRESHOLD_DEG,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minExtremeGapMs: Long = DEFAULT_MIN_EXTREME_GAP_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {

    private data class Extreme(val sign: Int, val atMs: Long)

    private val pitchExtremes = ArrayDeque<Extreme>()
    private val yawExtremes = ArrayDeque<Extreme>()

    /** -1 表示「还没有产生过任何动作」，避免用 Long.MIN_VALUE 触发减法溢出 */
    private var lastGestureAtMs = -1L

    /**
     * @param pitch 俯仰角（ML Kit headEulerAngleX）
     * @param yaw   偏航角（ML Kit headEulerAngleY）
     * @param nowMs 单调时钟毫秒值（生产环境传 SystemClock.elapsedRealtime()）
     * @return "nod" / "shake" / "none"
     */
    fun detectHeadGesture(pitch: Float, yaw: Float, nowMs: Long): String {
        if (lastGestureAtMs >= 0 && nowMs - lastGestureAtMs < cooldownMs) {
            return HeadGesture.NONE.code
        }

        if (reachExtremes(pitchExtremes, thresholdSign(pitch, nodThresholdDeg), nowMs)) {
            lastGestureAtMs = nowMs
            reset()
            return HeadGesture.NOD.code
        }
        if (reachExtremes(yawExtremes, thresholdSign(yaw, shakeThresholdDeg), nowMs)) {
            lastGestureAtMs = nowMs
            reset()
            return HeadGesture.SHAKE.code
        }
        return HeadGesture.NONE.code
    }

    /** 清空累积中的极值（切换模式、重新绑定摄像头时调用）。不影响冷却计时。 */
    fun reset() {
        pitchExtremes.clear()
        yawExtremes.clear()
    }

    /** 供界面/日志诊断用：当前累积了几个极值 */
    fun pendingExtremes(): Int = pitchExtremes.size + yawExtremes.size

    private fun reachExtremes(queue: ArrayDeque<Extreme>, sign: Int, nowMs: Long): Boolean {
        if (sign == 0) return false

        val last = queue.lastOrNull()
        if (last != null) {
            if (last.sign == sign) return false                       // 同向重复，不是新极值
            if (nowMs - last.atMs < minExtremeGapMs) return false      // 太快 = 抖动
        }

        queue.addLast(Extreme(sign, nowMs))

        // 只保留窗口内的极值
        while (queue.isNotEmpty() && nowMs - queue.first().atMs > windowMs) {
            queue.removeFirst()
        }

        return queue.size >= REQUIRED_EXTREMES
    }

    private fun thresholdSign(value: Float, threshold: Float): Int = when {
        value >= threshold -> 1
        value <= -threshold -> -1
        else -> 0
    }

    companion object {
        const val DEFAULT_NOD_THRESHOLD_DEG = 12f
        const val DEFAULT_SHAKE_THRESHOLD_DEG = 15f
        const val DEFAULT_WINDOW_MS = 1_400L
        const val DEFAULT_MIN_EXTREME_GAP_MS = 250L
        const val DEFAULT_COOLDOWN_MS = 1_800L
        const val REQUIRED_EXTREMES = 2
    }
}
