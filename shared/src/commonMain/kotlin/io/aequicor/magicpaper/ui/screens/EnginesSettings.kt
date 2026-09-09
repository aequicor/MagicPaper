package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperChoice
import io.aequicor.magicpaper.designsystem.PaperDialog
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperPanel
import io.aequicor.magicpaper.designsystem.PaperProgress
import io.aequicor.magicpaper.designsystem.PaperProgressKind
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.UiState

@Composable
internal fun NewCodingSessionDialog(defaultEngine: CodingEngine, onDismiss: () -> Unit, onCreate: (CodingEngine) -> Unit) {
    var engine by remember { mutableStateOf(defaultEngine) }
    PaperDialog("Новая сессия", onDismiss, confirmLabel = "Создать сессию", onConfirm = { onCreate(engine) }, dismissLabel = "Отмена") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperText("Выберите движок для этой сессии. После создания его изменить нельзя; модель можно менять.")
            EngineChoices(engine) { engine = it }
            PaperText(if (engine == CodingEngine.PI) "pi · работа с файлами и командами проекта" else "Codex · работа с файлами, командами и подтверждениями доступа", role = PaperTextRole.LABEL)
        }
    }
}

@Composable
internal fun EngineChoices(selected: CodingEngine, onSelect: (CodingEngine) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CodingEngine.entries.forEach { engine -> PaperChoice(selected == engine, { onSelect(engine) }, engine.title) }
    }
}

@Composable
internal fun EnginesSettings(vm: MagicPaperViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PaperButton("‹ Настройки", vm::closeEnginesSettings, kind = PaperButtonKind.QUIET)
        PaperText("Движки", role = PaperTextRole.HEADLINE)
        PaperText("Движок новых сессий", role = PaperTextRole.TITLE)
        EngineChoices(state.settings.defaultCodingEngine) { vm.saveSettings(state.settings.copy(defaultCodingEngine = it)) }
        PaperText("Этот вариант предлагается при создании сессии. Движки существующих сессий сохраняются.", role = PaperTextRole.LABEL)
        CodingEngine.entries.forEach { engine ->
            EngineStatusCard(engine, state.coding.engines[engine] ?: RuntimeStatus(if (state.openAiSubscription.available) RuntimePhase.UNKNOWN else RuntimePhase.UNSUPPORTED), engine in state.coding.preparingEngines,
                state.coding.sessions.none { it.running } && state.coding.preparingEngines.isEmpty(), { vm.prepareCodingRuntime(engine) }, { vm.uninstallCodingRuntime(engine) })
        }
        PaperDivider()
        PaperText("Подписка ChatGPT", role = PaperTextRole.TITLE)
        PaperText("Один вход MagicPaper используется обоими движками. Для работы pi с подпиской также нужен установленный Codex для управления входом.", role = PaperTextRole.LABEL)
        SubscriptionAccount(vm, state)
        PaperButton("Настроить подключения и модели", { vm.closeEnginesSettings(); vm.openModelsSettings() }, kind = PaperButtonKind.QUIET)
    }
}

@Composable
internal fun EngineStatusCard(engine: CodingEngine, status: RuntimeStatus, preparing: Boolean, canRemove: Boolean, onPrepare: () -> Unit, onRemove: () -> Unit) {
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PaperText(engine.title, role = PaperTextRole.TITLE)
                PaperText(when (status.phase) {
                    RuntimePhase.READY -> "Готов"; RuntimePhase.CHECKING -> "Проверка…"; RuntimePhase.INSTALLING -> "Подготовка…"
                    RuntimePhase.ERROR -> "Требует внимания"; RuntimePhase.UNSUPPORTED -> "Недоступен на этой платформе"; RuntimePhase.UNKNOWN -> "Не подготовлен"
                }, color = if (status.phase == RuntimePhase.ERROR) LocalPaperColors.current.error else LocalPaperColors.current.secondaryText)
                if (status.version.isNotBlank()) PaperText("v${status.version}", role = PaperTextRole.LABEL)
            }
            if (status.detail.isNotBlank()) PaperText(status.detail, role = PaperTextRole.LABEL)
            PaperText(if (engine == CodingEngine.PI) "Подписка ChatGPT, OpenAI-совместимые серверы, OpenRouter, Anthropic и Google." else "Подписка ChatGPT и API-провайдеры. Для сторонних API используется общий адаптер подключений из зависимостей pi.", role = PaperTextRole.LABEL)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (preparing) PaperProgress(Modifier.size(20.dp), kind = PaperProgressKind.CIRCULAR, label = "Подготовка")
                PaperButton(if (engine == CodingEngine.PI) "Подготовить" else "Проверить и подготовить", onPrepare, kind = PaperButtonKind.QUIET, enabled = !preparing && status.phase != RuntimePhase.UNSUPPORTED)
                if (engine == CodingEngine.PI) PaperButton("Удалить зависимости", onRemove, kind = PaperButtonKind.QUIET, enabled = canRemove && !preparing && status.phase != RuntimePhase.UNSUPPORTED)
            }
        }
    }
}
