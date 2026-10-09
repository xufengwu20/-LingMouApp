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
        Text(
            text = "当前：${state.asrBackend}",
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Switch(
                checked = state.preferCloudAsr,
                onCheckedChange = onToggleCloudAsr,
                enabled = state.siliconFlowKeyConfigured
            )
            Text(
                text = if (state.siliconFlowKeyConfigured) {
                    "使用硅基流动云端 ASR"
                } else {
                    "未配置 API Key：用系统内置识别即可；\n" +
                        "不想配也完全没问题，本地指令测试照样可用。"
                },
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider()

        // ---------- B. 本地指令测试 ----------
        SubHeader(
            text = "B. 本地指令测试（不依赖麦克风 / 不依赖 API Key）",
            hint = "直接调用指令解析，只要送入文字就会立刻触发对应的控制行为。"
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
            Text("运行本地指令自检（11 条用例）")
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
