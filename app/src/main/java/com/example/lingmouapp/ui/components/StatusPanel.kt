package com.example.lingmouapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
        if (state.mode == AppMode.COOKING) {
            InfoRow(
                "头部俯仰 / 偏航",
                String.format(Locale.US, "%.1f° / %.1f°", state.headPitch, state.headYaw)
            )
        }

        InfoRow("系统媒体会话", state.mediaSessionSummary)
    }
}

@Composable
fun PermissionPanel(
    state: AppUiState,
    onRequestPermissions: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit
) {
    SectionCard(title = "权限体检") {
        PermissionLine("摄像头 CAMERA", state.cameraPermission)
        PermissionLine("麦克风 RECORD_AUDIO", state.audioPermission)
        PermissionLine("通知 POST_NOTIFICATIONS", state.notificationsEnabled)
        PermissionLine(
            label = "通知使用权（跨应用媒体控制）",
            granted = state.notificationAccess,
            hint = "设置 → 通知 → 通知使用权 → 灵眸"
        )
        PermissionLine(
            label = "精确闹钟 SCHEDULE_EXACT_ALARM",
            granted = state.exactAlarmAllowed,
            hint = "设置 → 应用 → 灵眸 → 闹钟和提醒"
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("申请运行时权限（摄像头 / 麦克风 / 通知）")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenNotificationAccess,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("去开启通知使用权")
                }
                Button(
                    onClick = onOpenExactAlarmSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("去允许精确闹钟")
                }
            }
            Text(
                text = "这两个开关系统不允许应用自己申请，只能跳到设置页由你手动打开。" +
                    "点完按钮会提示实际落在了哪一页。",
                style = MaterialTheme.typography.bodySmall,
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
