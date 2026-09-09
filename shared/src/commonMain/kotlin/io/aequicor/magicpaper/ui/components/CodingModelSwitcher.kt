package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.designsystem.*

@Composable
fun CodingModelChip(profile: LlmProfile?, overridden: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PaperAction(onClick, modifier.heightIn(min = 32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            PaperText(profile?.let { it.modelName(it.selectionKey).ifBlank { it.name } } ?: "Выбрать модель",
                role = PaperTextRole.CHROME, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (profile != null) {
                PaperText(profile.effortLabel(ModelDefaults.capability(profile)),
                    role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun CodingModelSwitcherDialog(vm: MagicPaperViewModel, sessionId: String, profiles: List<LlmProfile>, activeProfileId: String, sessionProfileId: String?, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val session = state.coding.sessions.firstOrNull { it.session.id == sessionId }?.session
    val plans = vm.planningChat?.store?.plans?.collectAsState()?.value.orEmpty()
    val resolved = session?.let { vm.codingProfileOf(it, plans.firstOrNull { plan -> plan.id == it.planId }) }
    val selected = resolved?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) } ?: session?.modelSelection
    FavoriteModelPicker(profiles.filter { session?.planningMode == true || it.supportsCoding }, selected,
        { vm.selectCodingModel(sessionId, it) }, onDismiss, "Модель сессии проекта", footer = {
            if (selected != null && resolved?.supportsCoding == true) PaperAction(onClick = { vm.selectCodingModel(sessionId, selected, forProject = true) }) { PaperText("Использовать в новых сессиях проекта") }
            PaperAction(onClick = { vm.openModelsSettings(); onDismiss() }) { PaperText("Настроить модели") }
        })
}
