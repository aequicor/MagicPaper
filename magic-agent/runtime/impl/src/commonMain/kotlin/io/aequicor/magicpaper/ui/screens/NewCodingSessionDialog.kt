package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.coding.backendProtocols
import io.aequicor.magicpaper.data.coding.backendCatalog
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.CodingEngine

@Composable
fun NewCodingSessionDialog(engine: CodingEngine, onEngineChange: (CodingEngine) -> Unit,
    onDismiss: () -> Unit, onCancel: () -> Unit, onCreate: () -> Unit,
    loaded: Boolean = true, busy: Boolean = false, error: String? = null, onRetry: (() -> Unit)? = null,
) {
    PaperDialog("Новая сессия", onDismiss, confirmLabel = "Создать сессию", onConfirm = onCreate,
        confirmEnabled = loaded && !busy, dismissLabel = "Отмена", onDismissAction = onCancel, dismissEnabled = !busy) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperText("Выберите движок для этой сессии. После создания его изменить нельзя; модель можно менять.")
            NativeEngineChoices(backendCatalog.descriptors, engine, enabled = loaded && !busy, onSelect = onEngineChange)
            backendCatalog.descriptors.firstOrNull { it.engine == engine }?.let { PaperText(it.summary, role = PaperTextRole.LABEL) }
            if (!loaded && error == null) PaperText("Загрузка…", role = PaperTextRole.LABEL)
            if (error != null) {
                PaperText(error, color = LocalPaperColors.current.error)
                if (onRetry != null) PaperButton("Повторить", onRetry, kind = PaperButtonKind.QUIET, enabled = !busy)
            }
        }
    }
}
