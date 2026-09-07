package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/** One flat list of favorites across connections; effort belongs to the current selection. */
@Composable
fun FavoriteModelPicker(
    profiles: List<LlmProfile>,
    selection: ModelSelection?,
    onSelect: (ModelSelection) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Выбрать модель",
    footer: @Composable () -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.widthIn(max = 480.dp).heightIn(max = 600.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                val choices = profiles.filter { it.connectionConfigured }.flatMap { p -> p.displayModels.map { p to it } }
                if (choices.isEmpty()) Text("Добавьте избранные модели в настройках.", style = MaterialTheme.typography.bodyMedium)
                choices.forEach { (p, key) ->
                    val selected = selection?.profileId == p.id && selection.modelId == key
                    TextButton(onClick = { onSelect(if (selected) selection!! else ModelSelection(p.id, key)) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("${if (selected) "● " else ""}${p.modelName(key)}", style = MaterialTheme.typography.bodyMedium)
                            Text(p.name + if (p.variants.any { it.id == key }) " · свои параметры" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val profile = profiles.firstOrNull { it.id == selection?.profileId }
                if (profile != null && selection != null) {
                    HorizontalDivider()
                    EffortControl(ModelDefaults.capability(profile, selection.modelId), selection.effort,
                        { onSelect(selection.copy(effort = it)) })
                }
                footer()
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
            }
        }
    }
}

@Composable
fun ModelSwitcherDialog(vm: MagicPaperViewModel, profiles: List<LlmProfile>, activeProfileId: String, sessionProfileId: String?, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val resolved = ProfileResolver.resolve(state.current, state.settings, profiles)
    val selected = state.current?.modelSelection ?: resolved?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) }
    FavoriteModelPicker(profiles, selected, vm::selectChatModel, onDismiss, "Модель чата", footer = {
        TextButton(onClick = { vm.openModelsSettings(); onDismiss() }) { Text("Настроить модели") }
    })
}
