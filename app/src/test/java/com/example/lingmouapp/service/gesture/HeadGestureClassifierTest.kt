package com.example.lingmouapp.service.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 头部动作判定的 JVM 单元测试 —— 不需要真机、不需要摄像头。
 *
 * 时间通过 `nowMs` 注入，所以「抖动」「保持不动」「窗口过期」「冷却」这类
 * 最容易误触发、又最难在真机上复现的场景，可以在这里精确验证。
 */
class HeadGestureClassifierTest {

    private fun classifier() = HeadGestureClassifier()

    @Test
    fun singleNodIsDetected() {
        val c = classifier()
        assertEquals("none", c.detectHeadGesture(0f, 0f, 0))
        assertEquals("none", c.detectHeadGesture(-20f, 0f, 300))
        // 单次点头（下 → 上）就应该识别，不需要来回两趟
        assertEquals("nod", c.detectHeadGesture(20f, 0f, 700))
    }

    @Test
    fun singleShakeIsDetected() {
        val c = classifier()
        assertEquals("none", c.detectHeadGesture(0f, -25f, 300))
        assertEquals("shake", c.detectHeadGesture(0f, 25f, 700))
    }

    @Test
    fun oneGesturePerWindowEvenIfBothAxesMove() {
        val c = classifier()
        // 同一帧里俯仰和偏航都越界：只应判出一个动作，另一个被丢弃
        assertEquals("none", c.detectHeadGesture(-20f, -25f, 300))
        assertEquals("nod", c.detectHeadGesture(20f, 25f, 700))

        // 摇头需要重新累积极值，且要等过冷却
        assertEquals("none", c.detectHeadGesture(0f, -25f, 3000))
        assertEquals("shake", c.detectHeadGesture(0f, 25f, 3400))
    }

    @Test
    fun holdingOneExtremeDoesNotTrigger() {
        val c = classifier()
        // 一直低头不动，只是同一个极值反复上报
        assertEquals("none", c.detectHeadGesture(-20f, 0f, 300))
        assertEquals("none", c.detectHeadGesture(-25f, 0f, 600))
        assertEquals("none", c.detectHeadGesture(-30f, 0f, 900))
    }

    @Test
    fun smallMovementsBelowThresholdAreIgnored() {
        val c = classifier()
        // 阈值是 12°，±8° 的日常晃动不该算极值
        assertEquals("none", c.detectHeadGesture(8f, 0f, 300))
        assertEquals("none", c.detectHeadGesture(-8f, 0f, 700))
        assertEquals("none", c.detectHeadGesture(0f, 10f, 1100))
    }

    @Test
    fun fastJitterIsRejected() {
        val c = classifier()
        // 每 80ms 翻一次方向：低于 250ms 的最小间隔，全部按抖动丢弃
        assertEquals("none", c.detectHeadGesture(-20f, 0f, 300))
        assertEquals("none", c.detectHeadGesture(20f, 0f, 380))
        assertEquals("none", c.detectHeadGesture(-20f, 0f, 460))
        assertEquals("none", c.detectHeadGesture(20f, 0f, 540))
        assertEquals("none", c.detectHeadGesture(-20f, 0f, 620))
    }

    @Test
    fun cooldownBlocksImmediateRepeat() {
        val c = classifier()
        assertEquals("none", c.detectHeadGesture(-20f, 0f, 300))
        assertEquals("nod", c.detectHeadGesture(20f, 0f, 700))
        // 距上次动作只有 200ms / 600ms，还在 1800ms 冷却里
        assertEquals("none", c.detectHeadGesture(-20f, 0f, 900))
        assertEquals("none", c.detectHeadGesture(20f, 0f, 1300))
    }

    @Test
    fun resetDiscardsPendingExtremes() {
        val c = classifier()
        assertEquals("none", c.detectHeadGesture(-20f, 0f, 300))
        c.reset()
        // 重置后只剩一个极值，不该凑成动作
        assertEquals("none", c.detectHeadGesture(20f, 0f, 700))
    }

    @Test
    fun windowExpiryRestartsCounting() {
        val c = classifier()
        assertEquals("none", c.detectHeadGesture(-20f, 0f, 300))
        // 隔了 1700ms > 1400ms 窗口，前一个极值作废
        assertEquals("none", c.detectHeadGesture(20f, 0f, 2000))
    }

    @Test
    fun thresholdsAndWindowAreConfigurable() {
        val strict = HeadGestureClassifier(
            nodThresholdDeg = 30f,
            windowMs = 500L,
            minExtremeGapMs = 100L,
            cooldownMs = 0L
        )
        // ±20° 达不到 30° 阈值，怎么晃都不该触发
        assertEquals("none", strict.detectHeadGesture(-20f, 0f, 300))
        assertEquals("none", strict.detectHeadGesture(20f, 0f, 700))

        val loose = HeadGestureClassifier(
            nodThresholdDeg = 5f,
            windowMs = 5_000L,
            minExtremeGapMs = 0L,
            cooldownMs = 0L
        )
        assertEquals("none", loose.detectHeadGesture(-6f, 0f, 1))
        assertEquals("nod", loose.detectHeadGesture(6f, 0f, 2))
    }

    @Test
    fun pendingExtremesIsExposedForDiagnostics() {
        val c = classifier()
        assertEquals(0, c.pendingExtremes())
        c.detectHeadGesture(-20f, 0f, 300)
        assertEquals(1, c.pendingExtremes())
        c.detectHeadGesture(20f, 0f, 700)
        assertEquals(0, c.pendingExtremes())
    }
}
