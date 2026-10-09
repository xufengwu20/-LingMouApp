package com.example.lingmouapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppUiState

/**
 * 运行日志。这是验证一切功能最直接的窗口：
 * 每条媒体指令都写明了「有没有真的下发到系统媒体会话」，
 * 每次视线超时、每次人脸数量变化、每次语音识别结果都会留痕。
 */
@Composable
fun LogSection(
    state: AppUiState,
    onClearLog: () -> Unit
) {
    SectionCard(title = "运行日志（最新在最上面）") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onClearLog) { Text("清空") }
        }

        if (state.log.isEmpty()) {
            Text(
                text = "暂无日志",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.log.take(MAX_VISIBLE_LINES).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private const val MAX_VISIBLE_LINES = 40
