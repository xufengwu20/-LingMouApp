package com.example.lingmouapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppUiState

/**
 * 知识库入口 + 生成按钮。
 *
 * 放在状态区下面：用户看完状态就能顺手把这一场会话「沉淀」下来。
 */
@Composable
fun KnowledgeSection(
    state: AppUiState,
    onOpenKnowledgeBase: () -> Unit,
    onGenerateSummary: () -> Unit,
    onGenerateRecipe: () -> Unit
) {
    SectionCard(title = "云端内容分析 · 笔记 / 菜谱") {
        BlockRow(
            "本会话文字稿",
            if (state.transcriptLines == 0) {
                "— 还没有文字稿（需要语音识别产出内容）"
            } else {
                "${state.transcriptLines} 句（切换模式或停止引擎时结算）"
            }
        )
        BlockRow(
            "数据库",
            if (state.databaseReady) "就绪（SQLDelight / SQLite）" else "不可用"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onGenerateSummary,
                modifier = Modifier.weight(1f),
                enabled = !state.isGenerating
            ) {
                Text("生成学习笔记")
            }
            Button(
                onClick = onGenerateRecipe,
                modifier = Modifier.weight(1f),
                enabled = !state.isGenerating
            ) {
                Text("生成菜谱")
            }
        }

        if (state.generationNote.isNotBlank()) {
            Text(
                text = state.generationNote,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.isGenerating) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }

        OutlinedButton(
            onClick = onOpenKnowledgeBase,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("查看笔记 / 菜谱（笔记 ${state.notes.size} · 菜谱 ${state.recipes.size}）")
        }

        Text(
            text = "生成走硅基流动的对话接口（JSON 约束输出）。未配置 API Key 时按钮不会崩，" +
                "只会在上面写出原因。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
