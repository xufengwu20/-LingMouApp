package com.example.lingmouapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.lingmouapp.model.AppUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun formatTrigger(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(timeFormatter)

@Composable
fun ReminderSection(
    state: AppUiState,
    onCancelReminder: (Int) -> Unit
) {
    SectionCard(title = "定时提醒 · AlarmManager") {
        Text(
            text = if (state.exactAlarmAllowed) {
                "精确闹钟权限：已授予，将使用 setExactAndAllowWhileIdle"
            } else {
                "精确闹钟权限：未授予，会退化为 setAndAllowWhileIdle（可能延迟几分钟）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.exactAlarmAllowed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )

        if (state.reminders.isEmpty()) {
            Text(
                text = "暂无提醒。说一句「15分钟后提醒我」或点上面的「15分钟后提醒我」试试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.reminders.forEach { reminder ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = reminder.label,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "触发于 ${formatTrigger(reminder.triggerAtMillis)}" +
                                "（${if (reminder.exact) "精确" else "非精确"}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onCancelReminder(reminder.id) }) {
                        Text("取消")
                    }
                }
            }
        }
    }
}
