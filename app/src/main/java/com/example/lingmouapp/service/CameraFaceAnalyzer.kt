package com.example.lingmouapp.service

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.lingmouapp.model.FaceFrame
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs

/**
 * CameraX 的 ImageAnalysis 分析器 + ML Kit 人脸检测。
 *
 * 这里刻意使用 **bundled** 版本的 ML Kit（com.google.mlkit:face-detection），
 * 模型随 APK 一起下发，运行时不依赖 Google Play 服务，因此华为等无 GMS 设备也能用。
 *
 * 「是否在看屏幕」的近似判定（ML Kit 不提供真正的视线向量）：
 *   头部偏航 |yaw| ≤ 20° 且俯仰 |pitch| ≤ 20° 且 双眼睁开概率 ≥ 0.35
 * 想换成虹膜/视线估计模型时，只需要改 [toFrame] 这一个函数。
 */
class CameraFaceAnalyzer(
    private val onFrame: (FaceFrame) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
    )

    // 注意：CameraX 1.5.x 的 ExperimentalGetImage 并不是 @RequiresOptIn 注解，
    // 所以这里不需要（也不应该）写 @OptIn，否则会得到一个「注解无效」的警告。
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        detector.process(input)
            .addOnSuccessListener { faces -> onFrame(toFrame(faces)) }
            .addOnFailureListener { e -> onError(e.message ?: e.javaClass.simpleName) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun toFrame(faces: List<Face>): FaceFrame {
        val count = faces.size
        val primary = faces.maxByOrNull {
            it.boundingBox.width().toLong() * it.boundingBox.height().toLong()
        }
        if (primary == null) {
            return FaceFrame(
                faceCount = 0,
                attentive = false,
                headEulerX = 0f,
                headEulerY = 0f,
                headEulerZ = 0f,
                eyeOpenProbability = -1f,
                timestampMs = SystemClock.elapsedRealtime()
            )
        }

        val pitch = primary.headEulerAngleX
        val yaw = primary.headEulerAngleY
        val roll = primary.headEulerAngleZ
        val left = primary.leftEyeOpenProbability
        val right = primary.rightEyeOpenProbability
        val eye = when {
            left != null && right != null -> (left + right) / 2f
            left != null -> left
            right != null -> right
            else -> -1f
        }

        val eyesOpen = eye < 0f || eye >= EYE_OPEN_THRESHOLD
        val facingScreen = abs(yaw) <= YAW_LIMIT_DEG && abs(pitch) <= PITCH_LIMIT_DEG

        return FaceFrame(
            faceCount = count,
            attentive = eyesOpen && facingScreen,
            headEulerX = pitch,
            headEulerY = yaw,
            headEulerZ = roll,
            eyeOpenProbability = eye,
            timestampMs = SystemClock.elapsedRealtime()
        )
    }

    fun close() {
        try {
            detector.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        const val YAW_LIMIT_DEG = 20f
        const val PITCH_LIMIT_DEG = 20f
        const val EYE_OPEN_THRESHOLD = 0.35f
    }
}
