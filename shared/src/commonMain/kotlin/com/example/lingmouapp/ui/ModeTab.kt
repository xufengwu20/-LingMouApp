package com.example.lingmouapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState
import com.example.lingmouapp.ui.components.CameraSection
import com.example.lingmouapp.ui.components.CaptionSection
import com.example.lingmouapp.ui.components.ModeSelector
import com.example.lingmouapp.ui.components.PlayerSection
import com.example.lingmouapp.ui.components.StatusPanel

/**
 * 「模式」Tab 的内容：标题、三模式切换、状态、视频占位区、字幕。
 * 自身不带 Scaffold —— 外壳由 [AppShell] 提供（底部导航栏）。
 */
@Composable
fun ModeTab(
    state: AppUiState,
    modifier: Modifier = Modifier,
    previewSlot: (@Composable () -> Unit)? = null,
    onSelectMode: (AppMode) -> Unit,
    onToggleCaption: (Boolean) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------------- 顶部标题 ----------------
        Text(
            text = "灵眸 LingMou",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "用视线与语音，掌控你的媒体",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ---------------- 模式切换 ----------------
        ModeSelector(current = state.mode, onSelect = onSelectMode)

        // ---------------- 当前模式状态 ----------------
        StatusPanel(state = state)

        // ---------------- 摄像头（勿扰模式下不显示） ----------------
        if (state.mode != AppMode.DO_NOT_DISTURB) {
            CameraSection(state = state, previewSlot = previewSlot)
        }

        // ---------------- 视频播放区 ----------------
        PlayerSection(state = state)

        // ---------------- AI 字幕（仅烹饪模式） ----------------
        if (state.mode == AppMode.COOKING) {
            CaptionSection(state = state, onToggleCaption = onToggleCaption)
        }
    }
}
