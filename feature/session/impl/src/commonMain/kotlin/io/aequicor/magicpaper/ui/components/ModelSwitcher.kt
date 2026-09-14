package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.DefaultChatComponent
import io.aequicor.magicpaper.designsystem.*

@Composable
fun ModelSwitcherDialog(vm: DefaultChatComponent, profiles: List<LlmProfile>, activeProfileId: String, sessionProfileId: String?, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val resolved = ProfileResolver.resolve(state.current, state.settings, profiles)
    val selected = state.current?.modelSelection ?: resolved?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) }
    FavoriteModelPicker(profiles, selected, vm::selectChatModel, onDismiss, "Модель чата", footer = {
        PaperAction(onClick = { vm.openModelsSettings(); onDismiss() }) { PaperText("Настроить модели") }
    })
}
