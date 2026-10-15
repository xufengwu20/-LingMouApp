package com.example.lingmouapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppUiState
import com.example.lingmouapp.model.NoteItem
import com.example.lingmouapp.model.RecipeItem
import com.example.lingmouapp.ui.components.SectionCard
import com.example.lingmouapp.ui.components.SubHeader

/**
 * 「用户」Tab：个人中心。
 *
 *   - 用户信息占位（灵眸用户，无登录）
 *   - 权限管理：可自动申请的运行时权限（点击重新申请）
 *               + 需手动跳转的特殊权限（去开启）
 *   - 我的笔记/食谱：列表（标题/类型/时间）→ 点击进入详情
 *
 * 权限状态来自 AppUiState，由 MainActivity.onResume 在从设置页返回后自动刷新；
 * 进入本 Tab 时由 AppShell 触发一次知识库刷新。
 */
@Composable
fun UserTab(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onRequestRuntimePermissions: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit
) {
    // 详情页选择状态：纯 UI 状态，不进 AppUiState
    var selectedNote by remember { mutableStateOf<NoteItem?>(null) }
    var selectedRecipe by remember { mutableStateOf<RecipeItem?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------------- 用户信息占位 ----------------
        UserHeader()

        // ---------------- 权限管理 ----------------
        PermissionCard(
            state = state,
            onRequestRuntimePermissions = onRequestRuntimePermissions,
            onOpenNotificationAccess = onOpenNotificationAccess,
            onOpenExactAlarmSettings = onOpenExactAlarmSettings
        )

        // ---------------- 我的笔记 / 食谱 ----------------
        when {
            selectedNote != null -> NoteDetail(
                note = selectedNote!!,
                onBack = { selectedNote = null }
            )
            selectedRecipe != null -> RecipeDetail(
                recipe = selectedRecipe!!,
                onBack = { selectedRecipe = null }
            )
            else -> KnowledgeList(
                state = state,
                onNoteClick = { selectedNote = it },
                onRecipeClick = { selectedRecipe = it }
            )
        }
    }
}

// ------------------------------------------------------------------ 用户信息

@Composable
private fun UserHeader() {
    SectionCard(title = "我的") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 头像占位：深蓝圆底 + 人形图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = PersonIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column {
                Text(
                    text = "灵眸用户",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "本地使用 · 无需登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 权限管理

@Composable
private fun PermissionCard(
    state: AppUiState,
    onRequestRuntimePermissions: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit
) {
    SectionCard(title = "权限管理") {
        if (!state.capabilities.runtimePermissions) {
            // 桌面端：没有 Android 权限体系，明确说明而不是留空白
            Text(
                text = "当前平台暂不支持 Android 的权限体系（桌面端无需任何权限）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        Text(
            text = "部分权限需手动开启，点击按钮跳转至系统设置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SubHeader("可自动申请的运行时权限")
        RuntimePermissionRow(
            name = "摄像头",
            granted = state.cameraPermission,
            onRequest = onRequestRuntimePermissions
        )
        RuntimePermissionRow(
            name = "麦克风",
            granted = state.audioPermission,
            onRequest = onRequestRuntimePermissions
        )
        RuntimePermissionRow(
            name = "通知",
            granted = state.notificationsEnabled,
            onRequest = onRequestRuntimePermissions
        )

        SubHeader("需手动跳转设置的特殊权限")
        SpecialPermissionRow(
            name = "通知使用权",
            granted = state.notificationAccess,
            onOpen = onOpenNotificationAccess
        )
        SpecialPermissionRow(
            name = "精确闹钟",
            granted = state.exactAlarmAllowed,
            onOpen = onOpenExactAlarmSettings
        )
    }
}

@Composable
private fun RuntimePermissionRow(
    name: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (granted) "已授予" else "未授予",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            if (!granted) {
                TextButton(onClick = onRequest) { Text("申请") }
            }
        }
    }
}

@Composable
private fun SpecialPermissionRow(
    name: String,
    granted: Boolean,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onOpen, enabled = !granted) {
            Text(if (granted) "已开启" else "去开启")
        }
    }
}

// ------------------------------------------------------------------ 我的笔记 / 食谱

@Composable
private fun KnowledgeList(
    state: AppUiState,
    onNoteClick: (NoteItem) -> Unit,
    onRecipeClick: (RecipeItem) -> Unit
) {
    SectionCard(title = "我的笔记 / 食谱") {
        if (state.notes.isEmpty() && state.recipes.isEmpty()) {
            Text(
                text = "还没有内容",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "观看视频后生成的笔记与菜谱会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        if (state.notes.isNotEmpty()) {
            SubHeader("学习笔记（${state.notes.size}）")
            state.notes.forEach { note ->
                KnowledgeListItem(
                    title = note.title,
                    type = "笔记",
                    createdText = note.createdText,
                    onClick = { onNoteClick(note) }
                )
            }
        }

        if (state.recipes.isNotEmpty()) {
            SubHeader("菜谱（${state.recipes.size}）")
            state.recipes.forEach { recipe ->
                KnowledgeListItem(
                    title = recipe.title,
                    type = "食谱",
                    createdText = recipe.createdText,
                    onClick = { onRecipeClick(recipe) }
                )
            }
        }
    }
}

@Composable
private fun KnowledgeListItem(
    title: String,
    type: String,
    createdText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "$type · $createdText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

// ------------------------------------------------------------------ 详情

@Composable
private fun NoteDetail(note: NoteItem, onBack: () -> Unit) {
    SectionCard(title = "笔记详情") {
        OutlinedButton(onClick = onBack) { Text("← 返回") }
        Text(
            text = note.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "${note.modeLabel} · ${note.createdText}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        Text(text = note.content, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecipeDetail(recipe: RecipeItem, onBack: () -> Unit) {
    SectionCard(title = "菜谱详情") {
        OutlinedButton(onClick = onBack) { Text("← 返回") }
        Text(
            text = recipe.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = recipe.createdText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (recipe.summary.isNotBlank()) {
            Text(
                text = recipe.summary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        HorizontalDivider()
        recipe.steps.forEach { step ->
            Text(text = step, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
