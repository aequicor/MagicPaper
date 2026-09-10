package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*

/** Domain composition: the durable proposal owns authority; the dialog owns only UI selection. */
@Composable
internal fun ImmunityInterventions(
    organism: SessionOrganism,
    busyProposalIds: Set<String> = emptySet(),
    onApprove: (ImmunityIntervention, ImmunityAction, Boolean) -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (organism.interventions.isEmpty() || organism.deletedAt != null) return
    val spacing = LocalPaperSpacing.current
    var open by rememberSaveable(organism.id) { mutableStateOf(false) }
    var deletingId by rememberSaveable(organism.id) { mutableStateOf<String?>(null) }
    var deletingGeneration by rememberSaveable(organism.id) { mutableLongStateOf(-1L) }
    val focus = remember(organism.id) { PaperFocusRestorer() }
    val pending = organism.interventions.count { it.state == ImmunityInterventionState.PROPOSED }
    PaperPanel(Modifier.fillMaxWidth().padding(spacing.xs), kind = PaperSurfaceKind.RAISED) {
        PaperFocusAnchor(focus, organism.id) {
            PaperButton("Решения · $pending", { open = true },
                Modifier.fillMaxWidth(), kind = PaperButtonKind.QUIET,
                accessibilityLabel = "Решения иммунитета. Ожидают ответа: $pending")
        }
    }
    if (!open) return
    val deleting = organism.interventions.firstOrNull { it.id == deletingId }
    if (deletingId != null) {
        val valid = deleting != null && deleting.state == ImmunityInterventionState.PROPOSED &&
            deleting.generation == deletingGeneration && organism.sessions[deleting.target]?.generation == deletingGeneration &&
            ImmunityAction.DELETE_HISTORY in deleting.actions && deleting.id !in busyProposalIds
        PaperDialog("Удалить историю сессий?", { deletingId = null },
            modifier = Modifier.widthIn(max = 640.dp).heightIn(max = 650.dp), dismissLabel = "Отмена",
            focusRestorer = focus) {
            PaperScrollArea(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    if (deleting != null) {
                        val ids = if (deleting.target == organism.zygoteId) organism.sessions.keys else deleting.affected
                        PaperText("Будут удалены диалоги и закреплённые запросы этих сессий:")
                        ids.sorted().forEach { id -> PaperText(organism.sessions[id]?.name ?: id) }
                        PaperText("Вернуть удалённые диалоги нельзя.")
                    }
                    if (!valid) PaperText("Предложение изменилось. Вернитесь к списку решений.", color = LocalPaperColors.current.error)
                    PaperButton("Удалить историю", {
                        if (valid && deleting != null) {
                            onApprove(deleting, ImmunityAction.DELETE_HISTORY, true)
                            deletingId = null
                        }
                    }, Modifier.fillMaxWidth(), kind = PaperButtonKind.DESTRUCTIVE, enabled = valid)
                }
            }
        }
    } else {
        PaperDialog("Решения иммунитета", { open = false },
            modifier = Modifier.widthIn(max = 680.dp).heightIn(max = 650.dp), focusRestorer = focus) {
            PaperScrollArea(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    organism.interventions.sortedByDescending { it.createdAt }.forEach { proposal ->
                        key(proposal.id) {
                            ImmunityProposalDetails(organism, proposal, proposal.id in busyProposalIds,
                                onApprove = { action, confirmed -> onApprove(proposal, action, confirmed) },
                                onDelete = { deletingId = proposal.id; deletingGeneration = proposal.generation },
                                onDismiss = { onDismiss(proposal.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImmunityProposalDetails(
    organism: SessionOrganism,
    proposal: ImmunityIntervention,
    busy: Boolean,
    onApprove: (ImmunityAction, Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalPaperSpacing.current
    val current = organism.sessions[proposal.target]
    val valid = current?.generation == proposal.generation && proposal.target !in organism.historyDeletedIds
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        PaperText(current?.name ?: proposal.target, role = PaperTextRole.TITLE)
        PaperText(when (proposal.state) {
            ImmunityInterventionState.PROPOSED -> "Ожидает решения"
            ImmunityInterventionState.ACCEPTED -> "Действие принято; результат проверяется"
            ImmunityInterventionState.COMPLETED -> "Выполнено"
            ImmunityInterventionState.REJECTED -> "Отклонено"
            ImmunityInterventionState.UNKNOWN -> "Результат не подтверждён"
        }, role = PaperTextRole.LABEL)
        proposal.action?.let { PaperText(it.immunityLabel()) }
        proposal.evidence.forEach { PaperText(it) }
        if (proposal.affected.size > 1) {
            PaperText("Затронутые сессии:", role = PaperTextRole.LABEL)
            proposal.affected.sorted().forEach { PaperText(organism.sessions[it]?.name ?: it) }
        }
        if (proposal.error.isNotBlank()) PaperText(proposal.error, color = LocalPaperColors.current.error)
        if (proposal.state == ImmunityInterventionState.PROPOSED) {
            if (!valid) PaperText("Состояние сессии изменилось. Требуется новое предложение.")
            proposal.actions.sortedBy { it.ordinal }.forEach { action ->
                PaperButton(action.immunityLabel(), {
                    if (action == ImmunityAction.DELETE_HISTORY) onDelete() else onApprove(action, false)
                }, Modifier.fillMaxWidth(), kind = if (action == ImmunityAction.DELETE_HISTORY) PaperButtonKind.DESTRUCTIVE else PaperButtonKind.SECONDARY,
                    enabled = valid && !busy, busy = busy)
            }
            PaperButton("Отклонить", onDismiss, Modifier.fillMaxWidth(), kind = PaperButtonKind.QUIET, enabled = !busy,
                accessibilityLabel = "Отклонить предложение")
        } else if (proposal.state in setOf(ImmunityInterventionState.ACCEPTED, ImmunityInterventionState.UNKNOWN) && proposal.action != null) {
            PaperButton("Проверить", { onApprove(proposal.action, proposal.confirmedAt != null) },
                Modifier.fillMaxWidth(), kind = PaperButtonKind.SECONDARY, enabled = !busy, busy = busy,
                accessibilityLabel = "Проверить результат")
        }
        PaperDivider()
    }
}

internal fun ImmunityAction.immunityLabel(): String = when (this) {
    ImmunityAction.PAUSE -> "Приостановить"
    ImmunityAction.QUARANTINE -> "Изолировать"
    ImmunityAction.STOP -> "Остановить"
    ImmunityAction.ARCHIVE -> "В архив"
    ImmunityAction.RECREATE -> "Пересоздать"
    ImmunityAction.DELETE_HISTORY -> "Удалить историю…"
}
