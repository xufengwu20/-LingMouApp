package com.example.lingmouapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState
import java.util.Locale

@Composable
fun StatusPanel(state: AppUiState) {
    SectionCard(title = "状态显示区") {
        Text(text = state.statusTitle, style = MaterialTheme.typography.titleMedium)
        Text(
            text = state.mode.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        // 指令闭环的反馈放在最显眼的位置：送出一条指令后不用往下翻就能确认它是否被执行
        SubHeader(
            text = "本地指令闭环（不依赖 API Key）",
            hint = "手动模拟、快捷按钮、语音识别结果走的是同一套本地解析与执行逻辑"
        )
        BlockRow("本地指令通道", "始终可用 —— 纯本机计算，无需联网、无 API Key")
        BlockRow("最近识别", state.lastTranscript.ifBlank { "— 还没有收到任何指令" })
        BlockRow("解析结果", state.lastCommand.ifBlank { "—" })
        BlockRow("上次媒体指令", state.lastMediaAction.ifBlank { "— 尚未下发" })
        BlockRow(
            "待触发提醒",
            if (state.reminders.isEmpty()) "无" else "${state.reminders.size} 个"
        )

        HorizontalDivider()

        InfoRow("引擎", if (state.engineRunning) "运行中" else "未运行")
        InfoRow("摄像头", state.cameraState)
        InfoRow("麦克风", state.microphoneState)

        if (state.mode == AppMode.FOCUS) {
            InfoRow("画面中人脸", "${state.faceCount} 张")
            InfoRow("注视状态", state.attention.label)
            InfoRow(
                "已离开屏幕",
                String.format(Locale.US, "%.1f 秒", state.awaySeconds)
            )
        }
        // 头部动作控制：需求要求状态区实时显示启用状态
        InfoRow("头部控制", if (state.headControlEnabled) "已启用" else "未启用")

        if (state.mode == AppMode.COOKING) {
            InfoRow(
                "俯仰 / 偏航 / 翻滚",
                String.format(
                    Locale.US,
                    "%.1f° / %.1f° / %.1f°",
                    state.headPitch,
                    state.headYaw,
                    state.headRoll
                )
            )
            InfoRow("最近头部动作", state.lastHeadGesture.ifBlank { "— 还没识别到" })
        }

        InfoRow("系统媒体会话", state.mediaSessionSummary)
    }
}

@Composable
fun PermissionPanel(
    state: AppUiState,
    onRequestPermissions: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenAppDetails: () -> Unit
) {
    SectionCard(title = "权限体检") {
        PermissionLine("摄像头 CAMERA", state.cameraPermission)
        PermissionLine("麦克风 RECORD_AUDIO", state.audioPermission)
        PermissionLine("通知 POST_NOTIFICATIONS", state.notificationsEnabled)
        PermissionLine(
            label = "通知使用权（跨应用媒体控制）",
            granted = state.notificationAccess,
            hint = "设置 → 通知与状态栏 → 通知使用权 → 灵眸"
        )
        PermissionLine(
            label = "精确闹钟 SCHEDULE_EXACT_ALARM",
            granted = state.exactAlarmAllowed,
            hint = "设置 → 应用 → 灵眸 → 闹钟和提醒"
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 这两个特殊权限是当前功能的核心，放在最显眼的位置
            Button(
                onClick = onOpenNotificationAccess,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.notificationAccess) {
                        "通知使用权已授予 ✓（点击可查看）"
                    } else {
                        "去开启通知使用权（跨应用媒体控制）"
                    }
                )
            }
            Button(
                onClick = onOpenExactAlarmSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.exactAlarmAllowed) {
                        "精确闹钟已允许 ✓（点击可查看）"
                    } else {
                        "去允许精确闹钟（定时提醒准点）"
                    }
                )
            }
            OutlinedButton(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("申请运行时权限（摄像头 / 麦克风 / 通知）")
            }
            OutlinedButton(
                onClick = onOpenAppDetails,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("应用详情页（ColorOS：自启动 / 耗电管理）")
            }

            Text(
                text = "这两个开关系统不允许应用自己申请，只能跳到设置页手动打开；" +
                    "授权后返回 App 会自动刷新状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "提示：ColorOS 会限制后台应用。如果定时提醒不准点，" +
                    "请到「应用详情 → 耗电管理」把灵眸设为允许后台运行、并打开自启动。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionLine(
    label: String,
    granted: Boolean,
    hint: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (granted) "已授予" else "未授予",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        if (!granted && hint != null) {
            Text(
                text = "路径：$hint",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
