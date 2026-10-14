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
 * AI 字幕。
 *
 * 显示区刻意做成「一块板子」的样子，紧跟在视频占位区下方：
 *   - 未开启 → 「字幕未开启」
 *   - 已开启无文本 → 聆听提示
 *   - 已开启有文本 → 实时识别原文
 *
 * 字幕的数据来源就是语音识别的原文（见 LingMouEngine.executeLocal），
 * 不额外开 AudioRecord，所以不会和识别器抢麦克风。
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

        // ---- 字幕显示区 ----
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
                    else -> "字幕已开启 · 正在聆听…\n（说完一句后停顿一下，识别完成才会显示）"
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

        BlockRow("字幕状态", if (state.captionEnabled) "已开启" else "已关闭")
        BlockRow("字幕数据来源", state.asrBackend)

        if (state.captionNote.isNotBlank()) {
            Text(
                text = state.captionNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Text(
            text = "字幕复用语音识别的原文，只在识别运行时才有内容；" +
                "切换模式或关闭识别会自动关闭字幕并清空文本。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
