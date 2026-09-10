package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperAction
import io.aequicor.magicpaper.designsystem.PaperDialog
import io.aequicor.magicpaper.designsystem.PaperPanel
import io.aequicor.magicpaper.designsystem.PaperSurfaceKind
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.designsystem.PaperComposerField
import io.aequicor.magicpaper.domain.*

@Composable
internal fun ScheduledMessages(plan: Plan, onCancel: (String) -> Unit, onEdit: (String, String) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf("") }
    if (plan.scheduledMessages.isEmpty()) return
    PaperText("Будущие сообщения", role = PaperTextRole.TITLE)
    plan.scheduledMessages.forEach { rule -> key(rule.id) {
        var details by rememberSaveable { mutableStateOf(false) }
        PaperPanel(Modifier.fillMaxWidth(), kind = PaperSurfaceKind.PANEL) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                PaperText(rule.trigger.describe(plan))
                PaperText("Кому: " + (rule.targetTaskId?.let { id -> plan.milestones.firstOrNull { it.id == id }?.stageLabel() } ?: "Родительская сессия"), role = PaperTextRole.LABEL)
                PaperText(rule.text, role = PaperTextRole.LABEL)
                PaperText(rule.status.label() + (if (rule.status == ScheduledMessageStatus.READY && plan.intent != ExecutionIntent.RUN) " · доставка после продолжения" else ""), role = PaperTextRole.LABEL)
                if (rule.reason.isNotBlank()) PaperText(rule.reason, role = PaperTextRole.LABEL)
                if (rule.status in setOf(ScheduledMessageStatus.WAITING, ScheduledMessageStatus.READY, ScheduledMessageStatus.ERROR)) {
                    // Stack actions so they remain usable with a narrow pane or enlarged system font.
                    PaperAction({ onCancel(rule.id) }) { PaperText("Отменить", role = PaperTextRole.LABEL) }
                    PaperAction({ editing = rule.id; request = "" }) { PaperText("Изменить через чат", role = PaperTextRole.LABEL) }
                }
                PaperAction({ details = !details }) { PaperText(if (details) "Скрыть подробности" else "Подробности и ID", role = PaperTextRole.LABEL) }
                if (details) SelectionContainer {
                    PaperText("Правило: ${rule.id}\nПлан: ${rule.planId}\nЗапуск: ${rule.runId}\nЗадача: ${rule.trigger.taskId ?: "—"}\nАдресат: ${rule.targetSessionId}\nДоставка: ${rule.deliveryId}" +
                        rule.eventId?.let { "\nСобытие: $it" }.orEmpty(), role = PaperTextRole.LABEL)
                }
            }
        }
    } }
    editing?.let { id -> PaperDialog(title = "Изменить через чат", onDismissRequest = { editing = null },
        confirmLabel = "Отправить", onConfirm = { onEdit(id, request.trim()); editing = null }) {
        PaperComposerField(request, { request = it }, label = { PaperText("Что изменить в будущем сообщении?") }, minLines = 3)
    } }
}

@Composable
internal fun HandoffDetails(info: HandoffInfo) {
    var expanded by rememberSaveable(info.eventId) { mutableStateOf(false) }
    PaperText(when (info.status) {
        HandoffStatus.QUEUED -> "Ожидает обработки родителем"
        HandoffStatus.PROCESSING -> "Родитель обрабатывает"
        HandoffStatus.RESOLVED -> "Обращение обработано"
        HandoffStatus.FAILED -> "Ошибка обработки родителем"
    }, role = PaperTextRole.LABEL,
        color = if (info.status == HandoffStatus.FAILED) LocalPaperColors.current.error else LocalPaperColors.current.secondaryText)
    if (info.nextStep.isNotBlank()) PaperText(info.nextStep, role = PaperTextRole.LABEL)
    PaperAction({ expanded = !expanded }) { PaperText(if (expanded) "Скрыть ID" else "Идентификаторы передачи", role = PaperTextRole.LABEL) }
    if (expanded) SelectionContainer { PaperText("Событие: ${info.eventId}\nЗадача: ${info.taskId}\nЗапуск: ${info.runId}", role = PaperTextRole.LABEL) }
}
