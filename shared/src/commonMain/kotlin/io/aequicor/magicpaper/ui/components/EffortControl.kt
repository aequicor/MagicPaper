package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.Effort
import kotlin.math.roundToInt

/**
 * Единое управление усилием: слайдер по шкале 0–100 плюс быстрые пресеты.
 * Используется в редакторе источника и в переключателях чата/кодинг-сессий.
 */
@Composable
fun EffortControl(effort: Int, onEffort: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Усилие: $effort из ${Effort.MAX} · ${Effort.label(effort)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = Effort.coerce(effort).toFloat(),
            onValueChange = { onEffort(it.roundToInt()) },
            valueRange = Effort.MIN.toFloat()..Effort.MAX.toFloat(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            EffortPreset("Выкл", Effort.OFF, effort, onEffort)
            EffortPreset("Низкое", Effort.LOW, effort, onEffort)
            EffortPreset("Среднее", Effort.MEDIUM, effort, onEffort)
            EffortPreset("Высокое", Effort.HIGH, effort, onEffort)
            EffortPreset("Макс.", Effort.ULTRA, effort, onEffort)
        }
    }
}

@Composable
private fun EffortPreset(title: String, value: Int, current: Int, onEffort: (Int) -> Unit) {
    TextButton(onClick = { onEffort(value) }, modifier = Modifier.padding(0.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = if (current == value) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
