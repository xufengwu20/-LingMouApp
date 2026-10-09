package com.example.lingmouapp.service

import android.os.SystemClock

/**
 * 基于人脸姿态角的点头 / 摇头识别（纯规则，不依赖任何模型）。
 *
 *  点头 = 俯仰角 headEulerAngleX 在短时间内反复穿越阈值
 *  摇头 = 偏航角 headEulerAngleY 在短时间内反复穿越阈值
 *
 * 判定方式：把角度按正负阈值离散成 +1 / -1 / 0，
 * 在时间窗内统计「方向翻转」次数，达到 2 次即认为完成一次动作，
 * 之后进入冷却期，避免一次动作被重复计数。
 */
class HeadGestureDetector(
    private val onNod: () -> Unit,
    private val onShake: () -> Unit,
    private val nodThresholdDeg: Float = 12f,
    private val shakeThresholdDeg: Float = 15f,
    private val windowMs: Long = 1_400L,
    private val cooldownMs: Long = 1_800L
) {

    private var lastPitchSign = 0
    private var lastYawSign = 0

    private val pitchReversals = ArrayDeque<Long>()
    private val yawReversals = ArrayDeque<Long>()

    private var lastNodAt = 0L
    private var lastShakeAt = 0L

    fun onHeadPose(
        pitchDeg: Float,
        yawDeg: Float,
        now: Long = SystemClock.elapsedRealtime()
    ) {
        val pitchSign = sign(pitchDeg, nodThresholdDeg)
        val yawSign = sign(yawDeg, shakeThresholdDeg)

        if (pitchSign != 0) {
            if (lastPitchSign != 0 && pitchSign != lastPitchSign) {
                pitchReversals.addLast(now)
                prune(pitchReversals, now)
                if (pitchReversals.size >= 2 && now - lastNodAt >= cooldownMs) {
                    lastNodAt = now
                    pitchReversals.clear()
                    onNod()
                }
            }
            lastPitchSign = pitchSign
        }

        if (yawSign != 0) {
            if (lastYawSign != 0 && yawSign != lastYawSign) {
                yawReversals.addLast(now)
                prune(yawReversals, now)
                if (yawReversals.size >= 2 && now - lastShakeAt >= cooldownMs) {
                    lastShakeAt = now
                    yawReversals.clear()
                    onShake()
                }
            }
            lastYawSign = yawSign
        }
    }

    fun reset() {
        lastPitchSign = 0
        lastYawSign = 0
        pitchReversals.clear()
        yawReversals.clear()
    }

    private fun prune(queue: ArrayDeque<Long>, now: Long) {
        while (queue.isNotEmpty() && now - queue.first() > windowMs) {
            queue.removeFirst()
        }
    }

    private fun sign(value: Float, threshold: Float): Int = when {
        value >= threshold -> 1
        value <= -threshold -> -1
        else -> 0
    }
}
