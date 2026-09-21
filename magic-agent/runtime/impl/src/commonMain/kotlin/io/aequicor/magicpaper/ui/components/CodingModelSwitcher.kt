package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.DefaultCodingComponent
import io.aequicor.magicpaper.designsystem.*

@Composable
fun CodingModelSwitcherDialog(vm: DefaultCodingComponent, sessionId: String, profiles: List<LlmProfile>, activeProfileId: String, sessionProfileId: String?, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val session = state.coding.sessions.firstOrNull { it.session.id == sessionId }?.session
    val nativeEngine = session?.engine?.takeIf {
        state.coding.usesNativeModels(session, session.featureFlags.resolve(state.settings.featureFlags))
    }
    if (session != null && nativeEngine != null) {
        // The catalog is asked when the dialog opens; the last good snapshot is shown meanwhile.
        LaunchedEffect(nativeEngine) { vm.refreshCodingModels(nativeEngine) }
        NativeCodingModelDialog(nativeEngine, state.coding.modelCatalogs[nativeEngine], session.codingModel,
            refreshing = nativeEngine in state.coding.refreshingModels,
            onSelect = { vm.selectNativeCodingModel(sessionId, it) },
            onRefresh = { vm.refreshCodingModels(nativeEngine) }, onDismiss = onDismiss,
            footer = {
                session.codingModel?.let { chosen ->
                    PaperAction(onClick = { vm.selectNativeCodingModel(sessionId, chosen, forProject = true) }) { PaperText("Использовать в новых сессиях проекта") }
                }
            })
        return
    }
    val plans = vm.planningChat?.store?.plans?.collectAsState()?.value.orEmpty()
    val resolved = session?.let { vm.codingProfileOf(it, plans.firstOrNull { plan -> plan.id == it.planId }) }
    val selected = resolved?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) } ?: session?.modelSelection
    FavoriteModelPicker(profiles.filter { session?.planningMode == true || it.supportsCoding }, selected,
        { vm.selectCodingModel(sessionId, it) }, onDismiss, "Модель сессии проекта", footer = {
            if (selected != null && resolved?.supportsCoding == true) PaperAction(onClick = { vm.selectCodingModel(sessionId, selected, forProject = true) }) { PaperText("Использовать в новых сессиях проекта") }
            PaperAction(onClick = { vm.openModelsSettings(); onDismiss() }) { PaperText("Настроить модели") }
        })
}
