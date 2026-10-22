package com.example.lingmouapp.service

import android.os.SystemClock

/**
 * 「视线离开屏幕持续 N 秒 → 暂停；视线回归 → 播放」的判定状态机。
 *
 * onFrame() 由相机分析回调高频调用（只更新布尔量，不加锁、不耗时），
 * tick() 由 200ms 的定时器调用负责计时与触发。
 * 两者分离的好处：即使相机某一帧卡住，计时依然准确。
 *
 * 演示保障：任何一次「暂停/恢复」动作触发后，[actionCooldownMs]（默认 2 秒）内
 * 不会再触发反向动作 —— 防止人脸在屏幕边缘反复进出导致暂停/恢复来回闪烁，
 * 答辩演示时画面才稳。
 */
class GazeTracker(
    private val awayThresholdMs: Long = DEFAULT_AWAY_THRESHOLD_MS,
    private val actionCooldownMs: Long = DEFAULT_ACTION_COOLDOWN_MS,
    private val onAwayTimeout: () -> Unit,
    private val onAttentionBack: () -> Unit
) {

    @Volatile
    private var attentive: Boolean = false

    @Volatile
    private var awaySince: Long = 0L

    @Volatile
    private var pausedByGaze: Boolean = false

    /** 必须至少看到过一次「在注视」，才允许触发自动暂停，避免一开始就误暂停 */
    @Volatile
    private var armed: Boolean = false

    /** 上一次动作（暂停或恢复）的时刻，用于防抖冷却 */
    @Volatile
    private var lastActionAtMs: Long = 0L

    /**
     * 每一帧人脸检测结果。
     *
     * @param hasFace 是否检测到人脸
     * @param attentiveNow 是否「正在注视」（按头部角度/睁眼判定）
     *
     * arm 条件从「必须注视过一次」改为「检测到过人脸」：
     * 之前的判定要求 |yaw|≤20° ∧ |pitch|≤20° ∧ 睁眼概率≥0.35，
     * 手机拿得稍斜或 ML Kit 欧拉角有噪声时就永远无法 arm，
     * 结果「人脸离开画面 3 秒」永远不会触发暂停 —— 这正是真机上观察到的现象。
     * 「检测到人脸」这个条件宽松得多，先保证功能能触发。
     */
    fun onFrame(hasFace: Boolean, attentiveNow: Boolean) {
        attentive = attentiveNow
        if (hasFace) armed = true
    }

    /** @return 已经离开屏幕的毫秒数；正在注视时返回 0 */
    fun tick(): Long {
        if (attentive) {
            awaySince = 0L
            if (pausedByGaze) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastActionAtMs < actionCooldownMs) {
                    // 防抖：距上次动作不足冷却时间，先不恢复播放
                    return 0L
                }
                lastActionAtMs = now
                pausedByGaze = false
                onAttentionBack()
            }
            return 0L
        }

        if (awaySince == 0L) {
            awaySince = SystemClock.elapsedRealtime()
            return 0L
        }

        val away = SystemClock.elapsedRealtime() - awaySince
        if (armed && away >= awayThresholdMs && !pausedByGaze) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastActionAtMs < actionCooldownMs) {
                // 防抖：刚触发过动作，这次先不暂停（离开计时继续累积）
                return away
            }
            lastActionAtMs = now
            pausedByGaze = true
            onAwayTimeout()
        }
        return away
    }

    /** 只读地看一眼当前离开时长，不产生副作用 */
    fun currentAwayMillis(): Long {
        val since = awaySince
        return if (attentive || since == 0L) 0L else SystemClock.elapsedRealtime() - since
    }

    /** 是否已见过人脸（= 视线离开自动暂停是否可能触发） */
    fun isArmed(): Boolean = armed

    fun reset() {
        attentive = false
        awaySince = 0L
        pausedByGaze = false
        armed = false
        lastActionAtMs = 0L
    }

    companion object {
        const val DEFAULT_AWAY_THRESHOLD_MS = 3_000L

        /** 暂停/恢复动作之间的最小间隔（演示防抖） */
        const val DEFAULT_ACTION_COOLDOWN_MS = 2_000L
    }
}
