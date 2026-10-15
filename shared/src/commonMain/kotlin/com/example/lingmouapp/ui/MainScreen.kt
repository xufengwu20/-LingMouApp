package com.example.lingmouapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
 * 主页面（面向最终用户的精简版）。
 *
 * 保留：标题 + 一句说明、三个模式按钮、当前模式状态、视频播放区、
 *       烹饪模式的 AI 字幕、右上角的「笔记 / 菜谱」入口。
 * 已移除：权限体检、本地指令测试、手动模拟、媒体会话诊断与按钮、
 *       日志区、语音识别后端设置、API Key 提示、定时提醒调试入口、
 *       平台能力卡片等一切开发测试内容。
 *
 * 功能不变：模式切换仍然触发原有的引擎逻辑（专注=摄像头视线检测、
 * 烹饪=语音+头部控制、勿扰=静默），状态实时更新。
 */
@Composable
fun MainScreen(
    state: AppUiState,
    previewSlot: (@Composable () -> Unit)? = null,
    onSelectMode: (AppMode) -> Unit,
    onOpenKnowledgeBase: () -> Unit,
    onToggleCaption: (Boolean) -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------------- 顶部标题 ----------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                }
                TextButton(onClick = onOpenKnowledgeBase) {
                    Text("笔记 / 菜谱")
                }
            }

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
}
