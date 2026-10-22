package com.example.lingmouapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.lingmouapp.model.AppMode
import com.example.lingmouapp.model.AppUiState

/** 底部导航的两个 Tab */
private enum class MainTab { MODE, USER }

/**
 * 应用外壳：底部导航栏 + 两个 Tab。
 *
 *   - 模式：三模式切换、状态显示、视频占位区
 *   - 用户：个人信息占位、我的笔记/食谱（列表→详情）、权限管理
 *
 * Tab 状态与详情页状态是纯 UI 状态，用 remember 保存在这里，
 * 不写进 AppUiState（那是业务状态，不该混入导航状态）。
 */
@Composable
fun AppShell(
    state: AppUiState,
    previewSlot: (@Composable () -> Unit)? = null,
    onSelectMode: (AppMode) -> Unit,
    onRefreshKnowledge: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onSeedDemoData: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(MainTab.MODE) }

    // 每次进入「用户」Tab 都拉一次最新数据（笔记/菜谱可能刚由会话结算产生）
    LaunchedEffect(selectedTab) {
        if (selectedTab == MainTab.USER) onRefreshKnowledge()
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == MainTab.MODE,
                    onClick = { selectedTab = MainTab.MODE },
                    icon = { Icon(HomeIcon, contentDescription = "模式") },
                    label = { Text("模式") },
                    colors = navColors()
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.USER,
                    onClick = { selectedTab = MainTab.USER },
                    icon = { Icon(PersonIcon, contentDescription = "用户") },
                    label = { Text("用户") },
                    colors = navColors()
                )
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (selectedTab) {
            MainTab.MODE -> ModeTab(
                state = state,
                modifier = contentModifier,
                previewSlot = previewSlot,
                onSelectMode = onSelectMode
            )
            MainTab.USER -> UserTab(
                state = state,
                modifier = contentModifier,
                onRequestRuntimePermissions = onRequestRuntimePermissions,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                onSeedDemoData = onSeedDemoData
            )
        }
    }
}

/** 选中项高亮：深蓝底 + 白图标（Material 风格指示器） */
@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    indicatorColor = MaterialTheme.colorScheme.primary
)
