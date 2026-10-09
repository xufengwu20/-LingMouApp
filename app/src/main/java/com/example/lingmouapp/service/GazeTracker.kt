package com.example.lingmouapp.service

import android.os.SystemClock

/**
 * 「视线离开屏幕持续 N 秒 → 暂停；视线回归 → 播放」的判定状态机。
 *
 * onFrame() 由相机分析回调高频调用（只更新布尔量，不加锁、不耗时），
 * tick() 由 200ms 的定时器调用负责计时与触发。
 * 两者分离的好处：即使相机某一帧卡住，计时依然准确。
 */
class GazeTracker(
    private val awayThresholdMs: Long = DEFAULT_AWAY_THRESHOLD_MS,
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

    fun onFrame(attentiveNow: Boolean) {
        attentive = attentiveNow
        if (attentiveNow) armed = true
    }

    /** @return 已经离开屏幕的毫秒数；正在注视时返回 0 */
    fun tick(): Long {
        if (attentive) {
            awaySince = 0L
            if (pausedByGaze) {
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

    fun isArmed(): Boolean = armed

    fun reset() {
        attentive = false
        awaySince = 0L
        pausedByGaze = false
        armed = false
    }

    companion object {
        const val DEFAULT_AWAY_THRESHOLD_MS = 3_000L
    }
}
