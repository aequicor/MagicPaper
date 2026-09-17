package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.*

/** One flat list of favorites across connections; effort belongs to the current selection. */
@Composable
fun renderFavoriteModelPicker(
    profiles: List<LlmProfile>,
    selection: ModelSelection?,
    onSelect: (ModelSelection) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Выбрать модель",
    footer: @Composable () -> Unit = {},
) {
    PaperDialog(title = title, onDismissRequest = onDismiss, modifier = Modifier.widthIn(max = 480.dp).heightIn(max = 600.dp)) {
            PaperScrollColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val choices = profiles.filter { it.connectionConfigured }.flatMap { p -> p.displayModels.map { p to it } }
                if (choices.isEmpty()) PaperText("Добавьте избранные модели в настройках.")
                choices.forEach { (p, key) ->
                    val selected = selection?.profileId == p.id && selection.modelId == key
                    PaperAction(onClick = { onSelect(selection.takeIf { selected } ?: ModelSelection(p.id, key)) }, enabled = p.enabled, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            PaperText("${if (selected) "● " else ""}${p.modelName(key)}", color = if (p.enabled) LocalPaperColors.current.text else LocalPaperColors.current.secondaryText)
                            PaperText(p.name + if (!p.enabled) " · поставщик отключён" else if (p.variants.any { it.id == key }) " · свои параметры" else "", role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
                        }
                    }
                }
                val profile = profiles.firstOrNull { it.id == selection?.profileId }
                if (profile?.enabled == true && selection != null) {
                    PaperDivider()
                    EffortControl(ModelDefaults.capability(profile, selection.modelId), selection.effort,
                        { onSelect(selection.copy(effort = it)) })
                }
                footer()
                PaperAction(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { PaperText("Готово") }
            }
    }
}
