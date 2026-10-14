package com.example.lingmouapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppUiState

/**
 * 平台能力说明卡片。
 *
 * 需求：「如果某些功能在 Windows 上暂时无法实现，在 UI 上明确标注『当前平台暂不支持』，不要留空白。」
 * 这就是那个「明确标注」的落点 —— 逐条列出当前平台上用不了的能力和**具体原因**，
 * 而不是让用户对着一堆永远显示「未授予」「未检测」的控件猜。
 *
 * 内容是数据驱动的（[com.example.lingmouapp.model.PlatformCapabilities]），
 * Android 端这份清单是空的，所以这张卡片在 Android 上只会显示一句话。
 */
@Composable
fun PlatformSupportSection(state: AppUiState) {
    val caps = state.capabilities

    SectionCard(title = "平台能力 · ${caps.platformName}") {
        if (caps.unsupported.isEmpty()) {
            BlockRow("说明", "当前平台上所有功能均可用（Android 端专有能力已在下方各区展示）")
        } else {
            Text(
                text = "以下能力在当前平台**暂不支持**（平台限制，不是功能留白）：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                caps.unsupported.forEach { feature ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "· ${feature.title}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "  当前平台暂不支持 —— ${feature.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
