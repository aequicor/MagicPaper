package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.UiState

@Composable
internal fun NewCodingSessionDialog(defaultEngine: CodingEngine, onDismiss: () -> Unit, onCreate: (CodingEngine) -> Unit) {
    var engine by remember { mutableStateOf(defaultEngine) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новая сессия") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Выберите движок для этой сессии. После создания его изменить нельзя; модель можно менять.")
            EngineChoices(engine) { engine = it }
            Text(if (engine == CodingEngine.PI) "pi · работа с файлами и командами проекта" else "Codex · работа с файлами, командами и подтверждениями доступа",
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = { onCreate(engine) }) { Text("Создать сессию") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
internal fun EngineChoices(selected: CodingEngine, onSelect: (CodingEngine) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CodingEngine.entries.forEach { engine ->
            FilterChip(selected = selected == engine, onClick = { onSelect(engine) }, label = { Text(engine.title) })
        }
    }
}

@Composable
internal fun EnginesSettings(vm: MagicPaperViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = vm::closeEnginesSettings) { Text("‹ Настройки") }
        Text("Движки", style = MaterialTheme.typography.headlineSmall)
        Text("Движок новых сессий", style = MaterialTheme.typography.titleMedium)
        EngineChoices(state.settings.defaultCodingEngine) { vm.saveSettings(state.settings.copy(defaultCodingEngine = it)) }
        Text("Этот вариант предлагается при создании сессии. Движки существующих сессий сохраняются.", style = MaterialTheme.typography.bodySmall)
        CodingEngine.entries.forEach { engine ->
            EngineStatusCard(engine, state.coding.engines[engine] ?: RuntimeStatus(if (state.openAiSubscription.available) RuntimePhase.UNKNOWN else RuntimePhase.UNSUPPORTED),
                preparing = engine in state.coding.preparingEngines,
                canRemove = state.coding.sessions.none { it.running } && state.coding.preparingEngines.isEmpty(),
                onPrepare = { vm.prepareCodingRuntime(engine) }, onRemove = { vm.uninstallCodingRuntime(engine) })
        }
        HorizontalDivider()
        Text("Подписка ChatGPT", style = MaterialTheme.typography.titleMedium)
        Text("Один вход MagicPaper используется обоими движками. Для работы pi с подпиской также нужен установленный Codex для управления входом.", style = MaterialTheme.typography.bodySmall)
        SubscriptionAccount(vm, state)
        TextButton(onClick = { vm.closeEnginesSettings(); vm.openModelsSettings() }) { Text("Настроить подключения и модели") }
    }
}

@Composable
internal fun EngineStatusCard(engine: CodingEngine, status: RuntimeStatus, preparing: Boolean, canRemove: Boolean, onPrepare: () -> Unit, onRemove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(engine.title, style = MaterialTheme.typography.titleLarge)
                Text(when (status.phase) {
                    RuntimePhase.READY -> "Готов"
                    RuntimePhase.CHECKING -> "Проверка…"
                    RuntimePhase.INSTALLING -> "Подготовка…"
                    RuntimePhase.ERROR -> "Требует внимания"
                    RuntimePhase.UNSUPPORTED -> "Недоступен на этой платформе"
                    RuntimePhase.UNKNOWN -> "Не подготовлен"
                }, color = if (status.phase == RuntimePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.version.isNotBlank()) Text("v${status.version}", style = MaterialTheme.typography.labelMedium)
            }
            if (status.detail.isNotBlank()) Text(status.detail, style = MaterialTheme.typography.bodySmall)
            Text(if (engine == CodingEngine.PI) "Подписка ChatGPT, OpenAI-совместимые серверы, OpenRouter, Anthropic и Google."
                else "Подписка ChatGPT и API-провайдеры. Для сторонних API используется общий адаптер подключений из зависимостей pi.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                TextButton(onClick = onPrepare, enabled = !preparing && status.phase != RuntimePhase.UNSUPPORTED) { Text(if (engine == CodingEngine.PI) "Подготовить" else "Проверить и подготовить") }
                if (engine == CodingEngine.PI) TextButton(onClick = onRemove, enabled = canRemove && !preparing && status.phase != RuntimePhase.UNSUPPORTED) { Text("Удалить зависимости") }
            }
        }
    }
}
