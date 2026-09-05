package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.ReasoningCapability
import io.aequicor.magicpaper.domain.selectableLevels

/**
 * Управление усилием по возможностям конкретной модели: чипы «по умолчанию»
 * и только те уровни, которые модель объявила ([selectableLevels]).
 * Слайдера нет: провайдеры принимают дискретные значения, а не числа.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EffortControl(
    capability: ReasoningCapability,
    selection: EffortSelection,
    onSelect: (EffortSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controls = capability as? ReasoningCapability.Controls
    if (controls == null || controls.values.isEmpty()) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                "У модели нет нативной ручки усилия — запрос уйдёт без поля усилия.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Усилие: ${selection.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            EffortChip("умолч", EffortSelection.Default, selection, onSelect)
            capability.selectableLevels.forEach { level ->
                val value = EffortSelection.of(level)
                EffortChip(level.shortLabel, value, selection, onSelect)
            }
        }
    }
}

@Composable
private fun EffortChip(
    title: String,
    value: EffortSelection,
    current: EffortSelection,
    onSelect: (EffortSelection) -> Unit,
) {
    TextButton(onClick = { onSelect(value) }, modifier = Modifier.padding(0.dp)) {
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
