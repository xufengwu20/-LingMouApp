package com.example.lingmouapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lingmouapp.model.AppMode

/** 三个模式按钮，当前选中的用实心 Button 高亮。 */
@Composable
fun ModeSelector(
    current: AppMode,
    onSelect: (AppMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppMode.entries.forEach { mode ->
                val selected = mode == current
                if (selected) {
                    Button(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        Text(
            text = current.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
