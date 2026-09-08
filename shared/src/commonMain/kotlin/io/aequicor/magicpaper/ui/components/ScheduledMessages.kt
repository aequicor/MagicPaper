package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*

@Composable
internal fun ScheduledMessages(plan: Plan, onCancel: (String) -> Unit, onEdit: (String, String) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf("") }
    if (plan.scheduledMessages.isEmpty()) return
    Text("Будущие сообщения", style = MaterialTheme.typography.titleSmall)
    plan.scheduledMessages.forEach { rule -> key(rule.id) {
        var details by rememberSaveable { mutableStateOf(false) }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(rule.trigger.describe(plan), style = MaterialTheme.typography.bodyMedium)
                Text("Кому: " + (rule.targetTaskId?.let { id -> plan.milestones.firstOrNull { it.id == id }?.stageLabel() } ?: "Оркестратор"),
                    style = MaterialTheme.typography.labelMedium)
                Text(rule.text, style = MaterialTheme.typography.bodySmall)
                Text(rule.status.label() + (if (rule.status == ScheduledMessageStatus.READY && plan.intent != ExecutionIntent.RUN) " · доставка после продолжения" else ""),
                    style = MaterialTheme.typography.labelMedium)
                if (rule.reason.isNotBlank()) Text(rule.reason, style = MaterialTheme.typography.bodySmall)
                if (rule.status in setOf(ScheduledMessageStatus.WAITING, ScheduledMessageStatus.READY, ScheduledMessageStatus.ERROR)) {
                    // Stack actions so they remain usable with a narrow pane or enlarged system font.
                    TextButton({ onCancel(rule.id) }) { Text("Отменить") }
                    TextButton({ editing = rule.id; request = "" }) { Text("Изменить через чат") }
                }
                TextButton({ details = !details }) { Text(if (details) "Скрыть подробности" else "Подробности и ID") }
                if (details) SelectionContainer {
                    Text("Правило: ${rule.id}\nПлан: ${rule.planId}\nЗапуск: ${rule.runId}\nЗадача: ${rule.trigger.taskId ?: "—"}\nАдресат: ${rule.targetSessionId}\nДоставка: ${rule.deliveryId}" +
                        rule.eventId?.let { "\nСобытие: $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } }
    editing?.let { id -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("Изменить через чат") },
        text = { OutlinedTextField(request, { request = it }, label = { Text("Что изменить в будущем сообщении?") }, minLines = 3) },
        confirmButton = { TextButton({ onEdit(id, request.trim()); editing = null }, enabled = request.isNotBlank()) { Text("Отправить") } },
        dismissButton = { TextButton({ editing = null }) { Text("Отмена") } }) }
}

@Composable
internal fun HandoffDetails(info: HandoffInfo) {
    var expanded by rememberSaveable(info.eventId) { mutableStateOf(false) }
    Text(when (info.status) {
        HandoffStatus.QUEUED -> "Ожидает обработки оркестратором"
        HandoffStatus.PROCESSING -> "Оркестратор обрабатывает"
        HandoffStatus.RESOLVED -> "Обращение обработано"
        HandoffStatus.FAILED -> "Ошибка обработки оркестратором"
    }, style = MaterialTheme.typography.labelMedium,
        color = if (info.status == HandoffStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (info.nextStep.isNotBlank()) Text(info.nextStep, style = MaterialTheme.typography.bodySmall)
    TextButton({ expanded = !expanded }) { Text(if (expanded) "Скрыть ID" else "Идентификаторы передачи") }
    if (expanded) SelectionContainer { Text("Событие: ${info.eventId}\nЗадача: ${info.taskId}\nЗапуск: ${info.runId}", style = MaterialTheme.typography.bodySmall) }
}
