package com.example.lingmouapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppUiState

/**
 * 视频播放占位区。
 *
 * 它不只是个摆设：每一条媒体指令都会同步反映到这里，
 * 所以即使手边没有 B 站/华为视频在播放，也能一眼看出「暂停/播放/回退」链路是否通了。
 *
 * 会话状态区分成三态，避免「还没下发过指令」被误读成「下发失败」。
 */
@Composable
fun PlayerSection(
    state: AppUiState,
    onTogglePlayer: () -> Unit,
    onPauseMedia: () -> Unit,
    onPlayMedia: () -> Unit,
    onRewindMedia: () -> Unit,
    onRefreshMediaSession: () -> Unit
) {
    SectionCard(title = "视频播放占位区") {
        Text(text = state.player.title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = "${state.player.positionText()} / ${state.player.durationText()}" +
                "  ·  ${if (state.player.isPlaying) "播放中" else "已暂停"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(state.player.progress)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onTogglePlayer, modifier = Modifier.weight(1f)) {
                Text(if (state.player.isPlaying) "暂停（本地）" else "播放（本地）")
            }
            OutlinedButton(onClick = onRewindMedia, modifier = Modifier.weight(1f)) {
                Text("回退 10 秒")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onPauseMedia, modifier = Modifier.weight(1f)) {
                Text("下发暂停")
            }
            OutlinedButton(onClick = onPlayMedia, modifier = Modifier.weight(1f)) {
                Text("下发播放")
            }
        }

        HorizontalDivider()

        BlockRow("系统媒体会话", state.mediaSessionSummary)
        BlockRow(
            "上一次指令",
            state.lastMediaAction.ifBlank { "尚未下发过指令" }
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = onRefreshMediaSession,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("刷新媒体会话并写入日志")
            }
            Text(
                text = "会话数量为 0 时：先在 B 站 / 华为视频里播一个视频，" +
                    "再回到这里点刷新。日志会列出实际拿到的所有会话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
