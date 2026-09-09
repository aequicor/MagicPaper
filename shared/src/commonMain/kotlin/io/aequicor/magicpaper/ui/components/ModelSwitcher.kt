package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.designsystem.*

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
    PaperDialog(title = title, onDismissRequest = onDismiss, modifier = Modifier.widthIn(max = 480.dp).heightIn(max = 600.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val choices = profiles.filter { it.connectionConfigured }.flatMap { p -> p.displayModels.map { p to it } }
                if (choices.isEmpty()) PaperText("Добавьте избранные модели в настройках.")
                choices.forEach { (p, key) ->
                    val selected = selection?.profileId == p.id && selection.modelId == key
                    PaperAction(onClick = { onSelect(if (selected) selection!! else ModelSelection(p.id, key)) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            PaperText("${if (selected) "● " else ""}${p.modelName(key)}")
                            PaperText(p.name + if (p.variants.any { it.id == key }) " · свои параметры" else "", role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
                        }
                    }
                }
                val profile = profiles.firstOrNull { it.id == selection?.profileId }
                if (profile != null && selection != null) {
                    PaperDivider()
                    EffortControl(ModelDefaults.capability(profile, selection.modelId), selection.effort,
                        { onSelect(selection.copy(effort = it)) })
                }
                footer()
                PaperAction(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { PaperText("Готово") }
            }
    }
}

@Composable
fun ModelSwitcherDialog(vm: MagicPaperViewModel, profiles: List<LlmProfile>, activeProfileId: String, sessionProfileId: String?, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val resolved = ProfileResolver.resolve(state.current, state.settings, profiles)
    val selected = state.current?.modelSelection ?: resolved?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) }
    FavoriteModelPicker(profiles, selected, vm::selectChatModel, onDismiss, "Модель чата", footer = {
        PaperAction(onClick = { vm.openModelsSettings(); onDismiss() }) { PaperText("Настроить модели") }
    })
}
