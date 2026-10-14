package com.example.lingmouapp.model

/**
 * 「是否在看屏幕」的判定结果。
 *
 * 注意：ML Kit Face Detection 不提供真正的视线（gaze）向量，
 * 这里用「头部朝向 + 双眼睁开概率」作为近似。
 * 想换成真正的虹膜视线估计时，只需要替换 FaceAnalyzer 里的判定函数，
 * GazeTracker 及以上的逻辑都不用改。
 */
enum class AttentionState(val label: String) {
    NO_FACE("未检测到人脸"),
    AWAY("视线离开屏幕"),
    ATTENTIVE("正在注视屏幕");

    companion object {
        fun of(faceCount: Int, attentive: Boolean): AttentionState = when {
            faceCount <= 0 -> NO_FACE
            attentive -> ATTENTIVE
            else -> AWAY
        }
    }
}

/** 单帧人脸检测结果，由 CameraFaceAnalyzer 产出。 */
data class FaceFrame(
    val faceCount: Int,
    val attentive: Boolean,
    val headEulerX: Float,
    val headEulerY: Float,
    val headEulerZ: Float,
    val eyeOpenProbability: Float,
    val timestampMs: Long
)
