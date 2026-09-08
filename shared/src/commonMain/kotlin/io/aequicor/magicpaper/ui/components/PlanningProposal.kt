package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.aequicor.magicpaper.domain.*

/** A pending proposal stays reachable even when its original chat message has scrolled away. */
@Composable
internal fun PlanningProposalCard(plan: Plan, awaitingAnswers: Boolean, onConfirm: (String) -> Unit) {
    val proposal = plan.proposal ?: return
    var open by rememberSaveable(proposal.id) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Предложение доработки", style = MaterialTheme.typography.labelLarge)
                Text(proposalConfirmationHint(plan, awaitingAnswers), style = MaterialTheme.typography.bodySmall)
            }
            TextButton({ open = true }) { Text("Посмотреть") }
        }
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = 650.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanningProposalDetails(plan, awaitingAnswers, onConfirm)
                }
                TextButton({ open = false }, Modifier.align(Alignment.End)) { Text("Закрыть") }
            }
        }
    }
}

private fun proposalConfirmationHint(plan: Plan, awaitingAnswers: Boolean) = when {
    awaitingAnswers -> "Сначала ответьте на уточнения в карточке под диалогом."
    plan.proposalReadyForConfirmation -> "Проверьте предложение и подтвердите запуск доработки."
    else -> "Подтверждение станет доступно после завершения текущего запуска."
}

/** Shared by the chat history and the persistent proposal card. */
@Composable
internal fun PlanningProposalDetails(plan: Plan, awaitingAnswers: Boolean, onConfirm: (String) -> Unit) {
    val proposal = plan.proposal ?: return
    Text("Предложение доработки", style = MaterialTheme.typography.titleMedium)
    ChatMarkdown(proposal.explanation)
    val current = plan.milestones.specification().associateBy { it.id }
    proposal.milestones.specification().filter { current[it.id] != it }.forEach { stage ->
        Text(stage.stageLabel(), fontWeight = FontWeight.SemiBold)
        Text(stage.description, style = MaterialTheme.typography.bodyMedium)
        Text("Критерии: ${stage.acceptance}", style = MaterialTheme.typography.bodySmall)
        stage.acceptanceCriteria.forEach { criterion ->
            Text("${if (criterion.required) "Обязательно" else "Необязательно"}: ${criterion.description} · ${criterion.environment.label()}", style = MaterialTheme.typography.bodySmall)
        }
        stage.assignment?.let { assignment ->
            Text("Исполнитель: ${assignment.displayName.ifBlank { assignment.modelId }} · ${assignment.effort.shortLabel}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(proposalConfirmationHint(plan, awaitingAnswers), style = MaterialTheme.typography.bodySmall)
    Button({ onConfirm(proposal.id) }, enabled = plan.proposalReadyForConfirmation && !awaitingAnswers) {
        Text("Подтвердить доработку")
    }
}
