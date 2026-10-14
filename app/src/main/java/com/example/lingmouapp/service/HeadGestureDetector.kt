package com.example.lingmouapp.service

import android.os.SystemClock
import com.example.lingmouapp.service.gesture.HeadGesture
import com.example.lingmouapp.service.gesture.HeadGestureClassifier

/**
 * 头部动作检测的 Android 侧封装。
 *
 *   - 真正的判定逻辑在纯 Kotlin 的 [HeadGestureClassifier]（可 JVM 单测）
 *   - 这里只负责提供时间源（SystemClock）和把结果转成回调
 *
 * 输入来自 ML Kit Face Detection 的头部姿态角：
 *   headEulerAngleX → pitch → 点头
 *   headEulerAngleY → yaw   → 摇头
 *   headEulerAngleZ → roll  → 只用于界面显示，不参与判定
 */
class HeadGestureDetector(
    private val onNod: () -> Unit,
    private val onShake: () -> Unit,
    nodThresholdDeg: Float = HeadGestureClassifier.DEFAULT_NOD_THRESHOLD_DEG,
    shakeThresholdDeg: Float = HeadGestureClassifier.DEFAULT_SHAKE_THRESHOLD_DEG
) {

    private val classifier = HeadGestureClassifier(
        nodThresholdDeg = nodThresholdDeg,
        shakeThresholdDeg = shakeThresholdDeg
    )

    /**
     * 与需求签名一致：返回 "nod" / "shake" / "none"。
     * 只做判定，不触发任何副作用 —— 需要回调时用 [onHeadPose]。
     */
    fun detectHeadGesture(pitch: Float, yaw: Float): String =
        classifier.detectHeadGesture(pitch, yaw, SystemClock.elapsedRealtime())

    /** 判定并触发对应回调（前台服务的分析回调走这里） */
    fun onHeadPose(pitchDeg: Float, yawDeg: Float) {
        when (HeadGesture.fromCode(detectHeadGesture(pitchDeg, yawDeg))) {
            HeadGesture.NOD -> onNod()
            HeadGesture.SHAKE -> onShake()
            HeadGesture.NONE -> Unit
        }
    }

    fun reset() = classifier.reset()
}
