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
import io.aequicor.magicpaper.domain.resolveEffort
import io.aequicor.magicpaper.domain.selectableLevels

/**
 * Управление усилием по возможностям конкретной модели: чип `default` и только
 * те уровни, которые модель объявила ([selectableLevels]). Имена — привычные
 * для других агентов: `off/min/low/medium/high/xhigh/max/auto`.
 * Слайдера нет: провайдеры принимают дискретные значения, а не числа.
 *
 * Унаследованный уровень (из профиля по умолчанию или из выбора для другой
 * модели) может не входить в словарь этой модели — тогда показываем, во что он
 * превратится в запросе, чтобы выбор не выглядел действующим.
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

    val resolved = capability.resolveEffort(selection)
    val levels = capability.selectableLevels
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            if (resolved.clamped) {
                "Усилие: ${selection.label} → ${resolved.level?.label ?: "по умолчанию провайдера"}"
            } else {
                "Усилие: ${selection.label}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (resolved.clamped) {
            Text(
                "Модель не принимает уровень «${selection.label}» — в запрос уйдёт «" +
                    (resolved.level?.label ?: "ничего") + "».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            EffortChip(EffortSelection.DEFAULT_LABEL, EffortSelection.Default, selection, onSelect, effective = false)
            levels.forEach { level ->
                val value = EffortSelection.of(level)
                EffortChip(
                    title = level.shortLabel,
                    value = value,
                    current = selection,
                    onSelect = onSelect,
                    effective = resolved.clamped && level == resolved.level,
                )
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
    effective: Boolean,
) {
    TextButton(onClick = { onSelect(value) }, modifier = Modifier.padding(0.dp)) {
        Text(
            if (effective) "$title →" else title,
            style = MaterialTheme.typography.labelMedium,
            color = if (current == value) {
                MaterialTheme.colorScheme.primary
            } else if (effective) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
