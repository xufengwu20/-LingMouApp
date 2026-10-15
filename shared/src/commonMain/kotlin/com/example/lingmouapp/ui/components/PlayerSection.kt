package com.example.lingmouapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppUiState

/**
 * 视频播放区 —— 只显示播放状态与进度，不提供任何按钮。
 *
 * 暂停 / 播放 / 回退由视线、语音与头部动作自动触发；
 * 这里只把结果直观地呈现出来（进度条用橙色强调色）。
 */
@Composable
fun PlayerSection(state: AppUiState) {
    val player = state.player

    SectionCard(title = "视频播放") {
        Text(
            text = player.title,
            style = MaterialTheme.typography.titleSmall
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
                    .fillMaxWidth(player.progress)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.tertiary)
            )
        }

        InfoRow(
            label = "${player.positionText()} / ${player.durationText()}",
            value = if (player.isPlaying) "播放中" else "已暂停"
        )
    }
}
