package com.example.lingmouapp.ui

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lingmouapp.viewmodel.MainViewModel

/**
 * Android 侧的相机画面实现 —— 填给共享 UI 的 `CameraSection(previewSlot = ...)`。
 *
 * 这是**整个工程里最后一段认识 CameraX 类型的 Compose 代码**：
 * 共享 UI（:shared/commonMain）只认「一个能画出画面的槽位」，
 * Windows 端不提供这个槽位，CameraSection 就会显示「当前平台暂不支持人脸检测」。
 *
 * 相机的所有权在前台服务手里，这里只负责把 PreviewView 的 SurfaceProvider
 * 挂到服务创建好的 Preview 用例上。
 */
@Composable
fun AndroidCameraPreview(viewModel: MainViewModel) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val current: Preview? = preview

    if (current == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "摄像头启动中…（若长时间停在这里，请查看下方日志）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(current, previewView) {
        previewView?.let { current.setSurfaceProvider(it.surfaceProvider) }
        onDispose { current.setSurfaceProvider(null) }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }.also { previewView = it }
        }
    )
}
