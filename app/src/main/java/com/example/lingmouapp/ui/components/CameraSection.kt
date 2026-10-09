package com.example.lingmouapp.ui.components

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState

/**
 * 相机画面。
 *
 * 相机的「所有权」在前台服务手里，这里只负责把 PreviewView 的 SurfaceProvider
 * 挂到服务创建好的 Preview 用例上；服务没起来（勿扰模式）时就显示占位。
 */
@Composable
fun CameraSection(
    state: AppUiState,
    preview: Preview?
) {
    SectionCard(title = "摄像头画面 · CameraX + ML Kit 人脸检测") {
        when {
            state.mode == AppMode.DO_NOT_DISTURB -> Placeholder("勿扰模式：摄像头已释放，不做任何 AI 检测")
            preview == null -> Placeholder("摄像头启动中…（若长时间停在这里，请看下方日志）")
            else -> {
                var previewView by remember { mutableStateOf<PreviewView?>(null) }

                DisposableEffect(preview, previewView) {
                    val view = previewView
                    if (view != null) {
                        preview.setSurfaceProvider(view.surfaceProvider)
                    }
                    onDispose {
                        preview.setSurfaceProvider(null)
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    factory = { context ->
                        PreviewView(context).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }.also { previewView = it }
                    }
                )
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
