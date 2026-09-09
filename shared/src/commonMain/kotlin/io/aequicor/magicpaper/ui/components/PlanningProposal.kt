package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.*

/** A pending proposal stays reachable even when its original chat message has scrolled away. */
@Composable
internal fun PlanningProposalCard(plan: Plan, awaitingAnswers: Boolean, onConfirm: (String) -> Unit) {
    val proposal = plan.proposal ?: return
    var open by rememberSaveable(proposal.id) { mutableStateOf(false) }
    PaperPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp), kind = PaperSurfaceKind.RAISED) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                PaperText("Предложение доработки", role = PaperTextRole.LABEL)
                PaperText(proposalConfirmationHint(plan, awaitingAnswers))
            }
            PaperAction({ open = true }) { PaperText("Посмотреть") }
        }
    }
    if (open) PaperDialog(title = "Предложение доработки", onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = 650.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanningProposalDetails(plan, awaitingAnswers, onConfirm)
                }
                PaperAction({ open = false }, Modifier.align(Alignment.End)) { PaperText("Закрыть") }
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
    PaperText("Предложение доработки", role = PaperTextRole.TITLE)
    ChatMarkdown(proposal.explanation)
    val current = plan.milestones.specification().associateBy { it.id }
    proposal.milestones.specification().filter { current[it.id] != it }.forEach { stage ->
        PaperText(stage.stageLabel(), fontWeight = FontWeight.SemiBold)
        PaperText(stage.description)
        PaperText("Критерии: ${stage.acceptance}")
        stage.acceptanceCriteria.forEach { criterion ->
            PaperText("${if (criterion.required) "Обязательно" else "Необязательно"}: ${criterion.description} · ${criterion.environment.label()}")
        }
        stage.assignment?.let { assignment ->
            PaperText("Исполнитель: ${assignment.displayName.ifBlank { assignment.modelId }} · ${assignment.effort.shortLabel}")
        }
    }
    PaperText(proposalConfirmationHint(plan, awaitingAnswers))
    PaperButton("Подтвердить доработку", { onConfirm(proposal.id) }, enabled = plan.proposalReadyForConfirmation && !awaitingAnswers)
}
