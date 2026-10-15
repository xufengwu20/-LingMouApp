package com.example.lingmouapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppUiState

/**
 * AI 字幕（仅烹饪模式显示）。
 *
 * 保留：开启/关闭按钮 + 字幕显示区 + 失败原因的简短提示。
 * 移除：识别后端名、字幕数据来源、长篇说明等调试信息。
 */
@Composable
fun CaptionSection(
    state: AppUiState,
    onToggleCaption: (Boolean) -> Unit
) {
    SectionCard(title = "AI 字幕") {
        Button(
            onClick = { onToggleCaption(!state.captionEnabled) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.captionEnabled) "关闭字幕" else "开启字幕")
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            val showingLive = state.captionEnabled && state.captionText.isNotBlank()
            Text(
                text = when {
                    !state.captionEnabled -> "字幕未开启"
                    showingLive -> state.captionText
                    else -> "字幕已开启 · 正在聆听…"
                },
                style = if (showingLive) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (state.captionEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )
        }

        if (state.captionNote.isNotBlank()) {
            Text(
                text = state.captionNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
