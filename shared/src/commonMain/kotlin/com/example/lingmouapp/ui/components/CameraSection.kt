package com.example.lingmouapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState

/**
 * 摄像头画面区域。
 *
 * 这个组件**不认识任何相机 API**：需要显示实时画面时，它只是渲染调用方传进来的
 * [previewSlot]。真正的 CameraX + PreviewView 实现留在 Android 模块
 * （`AndroidCameraPreview`）；Windows 端不传槽位，就会显示「当前平台暂不支持」。
 *
 * 之所以这么设计：`androidx.camera.core.Preview` 是 Android 专有类型，
 * 直接写在 commonMain 里整个模块就没法跨平台编译了。
 * （顺带也解掉了一个本就该解的设计耦合：UI 不该知道相机框架的存在。）
 */
@Composable
fun CameraSection(
    state: AppUiState,
    /** 平台能提供实时画面时传进来；为 null 表示当前平台做不到，会显示占位说明 */
    previewSlot: (@Composable () -> Unit)? = null,
    unsupportedLabel: String = "当前平台暂不支持人脸检测",
    title: String = "摄像头画面 · CameraX + ML Kit 人脸检测"
) {
    SectionCard(title = title) {
        when {
            state.mode == AppMode.DO_NOT_DISTURB ->
                Placeholder("勿扰模式：摄像头已释放，不做任何 AI 检测")

            previewSlot == null ->
                Placeholder("$unsupportedLabel\n（其余功能不受影响，可用下方本地指令测试验证闭环）")

            else -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                previewSlot()
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
