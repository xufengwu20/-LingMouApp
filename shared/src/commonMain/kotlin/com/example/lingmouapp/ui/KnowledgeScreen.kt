package com.example.lingmouapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppUiState
import com.example.lingmouapp.model.NoteItem
import com.example.lingmouapp.model.RecipeItem
import com.example.lingmouapp.ui.components.SectionCard

/**
 * 「查看笔记 / 菜谱」页面。
 *
 * 没有引入 Navigation Compose：当前只有两个页面，
 * 用 AppUiState.showKnowledgeBase 一个布尔量切换即可，
 * 省掉一个依赖和一套路由声明。
 */
@Composable
fun KnowledgeScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteNote: (Long) -> Unit,
    onDeleteRecipe: (Long) -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = onBack) { Text("← 返回") }
                OutlinedButton(onClick = onRefresh) { Text("刷新") }
            }

            Text(text = "知识库", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = if (state.databaseReady) {
                    "保存在本机 SQLite（SQLDelight），共 ${state.notes.size} 条笔记、" +
                        "${state.recipes.size} 条菜谱"
                } else {
                    "数据库不可用，暂时读不到历史记录"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---------------- 学习笔记 ----------------
            SectionCard(title = "学习笔记") {
                if (state.notes.isEmpty()) {
                    Text(
                        text = "还没有笔记。在专注/烹饪模式跑一段后，回主页面点「生成学习笔记」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.notes.forEach { note ->
                        NoteCard(note = note, onDelete = { onDeleteNote(note.id) })
                    }
                }
            }

            // ---------------- 菜谱 ----------------
            SectionCard(title = "菜谱") {
                if (state.recipes.isEmpty()) {
                    Text(
                        text = "还没有菜谱。在烹饪模式跑一段后，回主页面点「生成菜谱」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.recipes.forEach { recipe ->
                        RecipeCard(recipe = recipe, onDelete = { onDeleteRecipe(recipe.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: NoteItem, onDelete: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = note.title, style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onDelete) { Text("删除") }
        }
        Text(
            text = "${note.modeLabel} · ${note.createdText}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = note.content, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
    }
}

@Composable
private fun RecipeCard(recipe: RecipeItem, onDelete: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = recipe.title, style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onDelete) { Text("删除") }
        }
        Text(
            text = recipe.createdText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (recipe.summary.isNotBlank()) {
            Text(text = recipe.summary, style = MaterialTheme.typography.bodySmall)
        }
        recipe.steps.forEach { step ->
            Text(text = step, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}
