package com.example.lingmouapp.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState

/**
 * 当前模式状态卡片 —— 只保留面向最终用户的实时状态。
 *
 * 已移除：权限体检、本地指令闭环测试区、头部姿态数值、
 * 媒体会话诊断、API Key / 识别后端等一切调试内容。
 */
@Composable
fun StatusPanel(state: AppUiState) {
    val caps = state.capabilities

    val statusLine = when (state.mode) {
        AppMode.FOCUS -> when {
            !caps.faceDetection -> "专注模式运行中"
            state.engineRunning -> "专注模式运行中｜摄像头检测视线"
            else -> "专注模式｜摄像头启动中…"
        }
        AppMode.COOKING -> when {
            !caps.cloudAsr && !caps.headGesture -> "烹饪模式运行中"
            state.engineRunning -> "烹饪模式运行中｜聆听语音指令 · 头部动作控制"
            else -> "烹饪模式｜启动中…"
        }
        AppMode.DO_NOT_DISTURB -> "勿扰模式｜已静默"
    }

    SectionCard(title = "当前模式") {
        Text(
            text = statusLine,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        when (state.mode) {
            AppMode.FOCUS -> {
                if (caps.faceDetection) {
                    // 调试信息：用于确认 ML Kit 人脸检测是否真的在工作（真机排查用）
                    InfoRow("当前检测到的人脸数", "${state.faceCount} 张")
                    InfoRow("摄像头", state.cameraState)
                    if (state.engineRunning) {
                        InfoRow("视线状态", state.attention.label)
                    }
                }
            }
            AppMode.COOKING -> {
                if (caps.headGesture) {
                    InfoRow("头部控制", if (state.headControlEnabled) "已启用" else "未启用")
                }
                if (caps.cloudAsr) {
                    InfoRow("语音识别", if (state.asrRunning) "聆听中" else "未运行")
                }
            }
            AppMode.DO_NOT_DISTURB -> InfoRow("摄像头 / 麦克风", "均已释放")
        }

        if (state.reminders.isNotEmpty()) {
            InfoRow("待触发提醒", "${state.reminders.size} 个")
        }
    }
}
