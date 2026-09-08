package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*

@Composable
internal fun PlanningBlockerDock(
    session: CodingSession, history: List<CodingMessage>, service: PlanningChatService,
    busy: Boolean, modifier: Modifier = Modifier,
) {
    val plans by service.store.plans.collectAsState()
    val plan = plans.firstOrNull { it.id == session.planId }
        ?: plans.firstOrNull { it.parentSessionId == session.id && it.phase != ExecutionPhase.COMPLETE }
        ?: return
    val blockers = plan.blockingIssues(history)
    if (blockers.isEmpty()) return
    PlanningBlockerCard(blockers, busy, { service.control(plan.id, "retry") }, modifier)
}

/** Pinned above the composer, so a stop reason cannot be buried in the old plan card. */
@Composable
internal fun PlanningBlockerCard(
    blockers: List<PlanningBlocker>, busy: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier,
) {
    if (blockers.isEmpty()) return
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Выполнение остановлено", style = MaterialTheme.typography.titleSmall)
            SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    blockers.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Button(onClick = onRetry, enabled = !busy) {
                Text(blockers.recoveryActionLabel())
            }
        }
    }
}
