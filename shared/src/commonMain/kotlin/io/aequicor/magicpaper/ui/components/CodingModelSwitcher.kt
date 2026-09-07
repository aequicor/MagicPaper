package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.MagicPaperViewModel

@Composable
fun CodingModelChip(profile: LlmProfile?, overridden: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick, modifier.heightIn(min = 40.dp)) {
        Text(profile?.let { "${it.shortLabel} · ${it.effortLabel(ModelDefaults.capability(it))} ▾" } ?: "Выбрать модель",
            style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
fun CodingModelSwitcherDialog(vm: MagicPaperViewModel, sessionId: String, profiles: List<LlmProfile>, activeProfileId: String, sessionProfileId: String?, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val session = state.coding.sessions.firstOrNull { it.session.id == sessionId }?.session
    val resolved = session?.let(vm::codingProfileOf)
    val selected = session?.modelSelection ?: resolved?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) }
    FavoriteModelPicker(profiles.filter { it.supportsCoding }, selected,
        { vm.selectCodingModel(sessionId, it) }, onDismiss, "Модель сессии проекта", footer = {
            if (selected != null) TextButton(onClick = { vm.selectCodingModel(sessionId, selected, forProject = true) }) { Text("Использовать в новых сессиях проекта") }
            TextButton(onClick = { vm.openModelsSettings(); onDismiss() }) { Text("Настроить модели") }
        })
}
