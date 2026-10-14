package com.example.lingmouapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppUiState

/**
 * 语音指令区，刻意分成两段：
 *
 *  A. 语音识别后端 —— 只影响「对着手机说话」这条路，需要麦克风；
 *  B. 本地指令测试 —— 直接调用指令解析，**不经过麦克风、不使用任何 API Key**。
 *
 * 两段的分界线很清楚，就是为了避免「没配 API Key 就以为指令功能不可用」的误解。
 */
@Composable
fun SpeechSection(
    state: AppUiState,
    onSimulateSpeech: (String) -> Unit,
    onToggleCloudAsr: (Boolean) -> Unit,
    onRunSelfTest: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    SectionCard(title = "语音指令 · 烹饪模式") {

        // ---------- A. 识别后端 ----------
        SubHeader(
            text = "A. 语音识别后端",
            hint = "这一项只影响「对着手机说话」。没配 API Key 也不影响下面的本地指令测试。"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Switch(
                checked = state.preferCloudAsr,
                onCheckedChange = onToggleCloudAsr
            )
            Text(
                text = "云端识别（硅基流动）",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        BlockRow("当前识别后端", state.asrBackend)
        BlockRow(
            "云端模型",
            buildString {
                append(state.asrModel.ifBlank { "—" })
                append(
                    if (state.asrModelIsDefault) "（默认值，可在 local.properties 改）"
                    else "（来自 local.properties）"
                )
            }
        )
        BlockRow("麦克风", state.microphoneState)

        if (!state.siliconFlowKeyConfigured) {
            Text(
                text = "未配置 SILICONFLOW_API_KEY：打开上面的开关会立即自动降级为系统内置识别，" +
                    "不会报错，也不影响任何功能。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.asrNote.isNotBlank()) {
            Text(
                text = state.asrNote,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.asrDegraded) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Text(
            text = if (state.mode.usesMicrophone) {
                "提示：后台持续识别依赖前台服务，烹饪模式下可切后台继续；" +
                    "但录音期间麦克风被本应用占用。"
            } else {
                "提示：当前模式不使用麦克风，这里开启的识别只在前台有效；" +
                    "要在后台持续识别请先切到烹饪模式。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        // ---------- B. 本地指令测试 ----------
        SubHeader(
            text = "B. 本地指令测试（不依赖麦克风 / 不依赖 API Key）",
            hint = "直接调用本地指令路由，送入文字就立刻触发对应控制动作；" +
                "与识别后端是否可用、有没有配置 API Key 完全无关。"
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("手动模拟一句话") },
            singleLine = true
        )

        Button(
            onClick = {
                val text = input.ifBlank { "暂停" }
                onSimulateSpeech(text)
                input = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("送入指令解析")
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onSimulateSpeech("暂停") }) { Text("暂停") }
                OutlinedButton(onClick = { onSimulateSpeech("继续") }) { Text("继续") }
                OutlinedButton(onClick = { onSimulateSpeech("回退10秒") }) { Text("回退10秒") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onSimulateSpeech("15分钟后提醒我") }) {
                    Text("15分钟后提醒我")
                }
                OutlinedButton(onClick = { onSimulateSpeech("半小时") }) { Text("半小时") }
            }
        }

        OutlinedButton(
            onClick = onRunSelfTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("运行本地指令自检（11 条用例 → 校验最终动作）")
        }

        if (state.commandSelfTests.isNotEmpty()) {
            Text(
                text = "自检结果：${state.selfTestPassed}/${state.commandSelfTests.size} 通过",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.selfTestPassed == state.commandSelfTests.size) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            state.commandSelfTests.forEach { result ->
                Text(
                    text = "${if (result.passed) "✓" else "✗"} 「${result.input}」→ ${result.actual}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.passed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }

        HorizontalDivider()

        // ---------- C. 最近一次结果 ----------
        BlockRow("最近识别（原文）", state.lastTranscript.ifBlank { "—" })
        BlockRow("解析结果", state.lastCommand.ifBlank { "—" })
    }
}
