package com.example.lingmouapp.ui

import androidx.camera.core.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState
import com.example.lingmouapp.ui.components.CameraSection
import com.example.lingmouapp.ui.components.LogSection
import com.example.lingmouapp.ui.components.ModeSelector
import com.example.lingmouapp.ui.components.PermissionPanel
import com.example.lingmouapp.ui.components.PlayerSection
import com.example.lingmouapp.ui.components.ReminderSection
import com.example.lingmouapp.ui.components.SpeechSection
import com.example.lingmouapp.ui.components.StatusPanel

@Composable
fun MainScreen(
    state: AppUiState,
    preview: Preview?,
    onSelectMode: (AppMode) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onToggleCloudAsr: (Boolean) -> Unit,
    onTogglePlayer: () -> Unit,
    onPauseMedia: () -> Unit,
    onPlayMedia: () -> Unit,
    onRewindMedia: () -> Unit,
    onSimulateSpeech: (String) -> Unit,
    onRunSelfTest: () -> Unit,
    onRefreshMediaSession: () -> Unit,
    onCancelReminder: (Int) -> Unit,
    onClearLog: () -> Unit
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
            Text(
                text = "灵眸 LingMou",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "实时交互原型：人脸视线 · 语音指令 · 头部动作 · 跨应用媒体控制",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ModeSelector(current = state.mode, onSelect = onSelectMode)

            PermissionPanel(
                state = state,
                onRequestPermissions = onRequestPermissions,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onOpenExactAlarmSettings = onOpenExactAlarmSettings
            )

            StatusPanel(state = state)

            CameraSection(state = state, preview = preview)

            PlayerSection(
                state = state,
                onTogglePlayer = onTogglePlayer,
                onPauseMedia = onPauseMedia,
                onPlayMedia = onPlayMedia,
                onRewindMedia = onRewindMedia,
                onRefreshMediaSession = onRefreshMediaSession
            )

            SpeechSection(
                state = state,
                onSimulateSpeech = onSimulateSpeech,
                onToggleCloudAsr = onToggleCloudAsr,
                onRunSelfTest = onRunSelfTest
            )

            ReminderSection(state = state, onCancelReminder = onCancelReminder)

            LogSection(state = state, onClearLog = onClearLog)
        }
    }
}
