package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.designsystem.PaperScrollColumn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
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
import io.aequicor.magicpaper.backend.BackendAgentDescriptor
import io.aequicor.magicpaper.backend.BackendAgentCapability
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import io.aequicor.magicpaper.ui.NativeSettingsComponent
import io.aequicor.magicpaper.ui.SettingsState

import io.aequicor.magicpaper.ui.components.subscriptionAccountAction

@Composable
internal fun NativeEngineChoices(engines: List<BackendAgentDescriptor>, selected: CodingEngine, enabled: Boolean = true, onSelect: (CodingEngine) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        engines.forEach { engine -> PaperChoice(selected == engine.engine, { onSelect(engine.engine) }, engine.adapterName, enabled = enabled) }
    }
}

@Composable
fun EnginesSettings(vm: NativeSettingsComponent, state: SettingsState) {
    val coding by vm.coding.state.collectAsState()
    PaperScrollColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PaperButton("‹ Настройки", vm::closeEnginesSettings, kind = PaperButtonKind.QUIET)
        PaperText("Движки", role = PaperTextRole.HEADLINE)
        PaperText("Движок новых сессий", role = PaperTextRole.TITLE)
        NativeEngineChoices(vm.engines, state.settings.defaultCodingEngine) { vm.saveSettings(state.settings.copy(defaultCodingEngine = it)) }
        PaperText("Этот вариант предлагается при создании сессии. Движки существующих сессий сохраняются.", role = PaperTextRole.LABEL)
        PaperButton("Управление компьютером", vm::openComputerSettings, kind = PaperButtonKind.QUIET)
        vm.engines.forEach { descriptor ->
            val engine = descriptor.engine
            EngineStatusCard(descriptor, coding.coding.engines[engine] ?: RuntimeStatus(RuntimePhase.UNKNOWN), engine in coding.coding.preparingEngines,
                coding.coding.sessions.none { it.running } && coding.coding.preparingEngines.isEmpty(), { vm.prepareCodingRuntime(engine) }, { vm.uninstallCodingRuntime(engine) })
        }
        PaperDivider()
        PaperText("Подписка ChatGPT", role = PaperTextRole.TITLE)
        PaperText("Один вход MagicPaper используется подключёнными движками.", role = PaperTextRole.LABEL)
        vm.subscription.Content(state.openAiSubscription, vm::subscriptionAccountAction)
        PaperButton("Настроить подключения и модели", { vm.openModelsSettings() }, kind = PaperButtonKind.QUIET)
    }
}

@Composable
internal fun EngineStatusCard(descriptor: BackendAgentDescriptor, status: RuntimeStatus, preparing: Boolean, canRemove: Boolean, onPrepare: () -> Unit, onRemove: () -> Unit) {
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PaperText(descriptor.adapterName, role = PaperTextRole.TITLE)
                PaperText(when (status.phase) {
                    RuntimePhase.READY -> "Готов"; RuntimePhase.CHECKING -> "Проверка…"; RuntimePhase.INSTALLING -> "Подготовка…"
                    RuntimePhase.ERROR -> "Требует внимания"; RuntimePhase.UNSUPPORTED -> "Недоступен на этой платформе"; RuntimePhase.UNKNOWN -> "Не подготовлен"
                }, color = if (status.phase == RuntimePhase.ERROR) LocalPaperColors.current.error else LocalPaperColors.current.secondaryText)
                if (status.version.isNotBlank()) PaperText("v${status.version}", role = PaperTextRole.LABEL)
            }
            if (status.detail.isNotBlank()) PaperText(status.detail, role = PaperTextRole.LABEL)
            PaperText(descriptor.summary, role = PaperTextRole.LABEL)
            PaperText(descriptor.providerSummary, role = PaperTextRole.LABEL)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preparing) PaperProgress(Modifier.size(20.dp), kind = PaperProgressKind.CIRCULAR, label = "Подготовка")
                PaperButton(if (BackendAgentCapability.EXTERNAL_INSTALLATION in descriptor.capabilities) "Проверить" else "Подготовить", onPrepare, kind = PaperButtonKind.QUIET, enabled = !preparing && status.phase != RuntimePhase.UNSUPPORTED)
                if (BackendAgentCapability.MANAGED_INSTALLATION in descriptor.capabilities) PaperButton("Удалить зависимости", onRemove, kind = PaperButtonKind.QUIET, enabled = canRemove && !preparing && status.phase != RuntimePhase.UNSUPPORTED)
            }
        }
    }
}
