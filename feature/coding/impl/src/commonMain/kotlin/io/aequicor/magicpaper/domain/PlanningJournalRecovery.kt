package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.logging.AppLog

/**
 * Application-owned bridge from independent journal evidence to the existing quarantine UI.
 * Callers hold the execution admission lock: neither discovery nor confirmation races a new
 * controller. A completed tool receipt alone cannot prove completion of an entire worker or
 * Git operation, so an open aggregate intent requires a person's explicit confirmation.
 * NativeReceiptRecovery continues to prove exact tool receipts in SessionQuarantineRecovery.
 */
class PlanningJournalRecovery(
    private val store: PlanningStore,
    private val runtime: CodingRuntime,
    private val projects: CodingProjectRepository?,
    private val organisms: SessionOrganismService? = null,
) {
    private data class Snapshot(val plan: Plan, val pending: List<JournalRecord>, val selected: Set<Long>)
    private data class Confirmation(val snapshots: List<Snapshot>, val quarantines: Set<String>)
    private val confirmations = mutableMapOf<String, Confirmation>()

    private suspend fun quarantineIds(session: CodingSession): Set<String> {
        val service = organisms ?: return emptySet()
        val organism = service.ensure(session)
        return organism.unresolvedQuarantines(session.id).map { it.operationId }.toSet()
    }

    suspend fun quarantine(plan: Plan, pending: List<JournalRecord>) {
        val service = organisms ?: return
        val sessions = projects?.sessions(plan.projectId).orEmpty()
        for (intent in pending) {
            val session = sessions.firstOrNull { it.id == owner(plan, intent, sessions) } ?: continue
            val organism = service.ensure(session)
            val node = organism.sessions[session.id] ?: continue
            service.project(service.store.quarantine(organism.id, node.id, node.generation,
                "plan-journal-${plan.id}-${intent.seq}", "Не подтверждён результат: ${operationLabel(intent)}"))
        }
    }

    /** A confirmation applies only to the exact evidence shown by the preceding inspection. */
    suspend fun reconcile(session: CodingSession, confirmed: Boolean): QuarantineRecoveryOutcome {
        val sessions = projects?.sessions(session.projectId).orEmpty()
        val snapshots = store.plans().filter { it.projectId == session.projectId }.mapNotNull { plan ->
            val pending = store.unsettled(plan.id)
            val selected = pending.filter { owner(plan, it, sessions) == session.id }.map { it.seq }.toSet()
            if (selected.isEmpty()) null else Snapshot(plan, pending, selected)
        }
        if (snapshots.isEmpty()) {
            confirmations.remove(session.id)
            return QuarantineRecoveryOutcome.NO_QUARANTINE
        }
        if (!confirmed) {
            snapshots.forEach { quarantine(it.plan, it.pending.filter { record -> record.seq in it.selected }) }
            // Quarantine projection does not alter Plan, but read once more to fence competing edits.
            confirmations[session.id] = Confirmation(snapshots, quarantineIds(session))
            return QuarantineRecoveryOutcome.NEEDS_CONFIRMATION
        }
        val shown = confirmations.remove(session.id)
        require(shown?.snapshots == snapshots && shown.quarantines == quarantineIds(session)) { "Состояние изменилось; повторите сверку перед подтверждением" }
        // Reconciliation proves process termination, never success of its external effects.
        for (snapshot in snapshots) {
            val aliases = snapshot.plan.milestones.flatMap { it.attempts }.plus(listOfNotNull(snapshot.plan.finalAttempt))
                .filter { attempt -> snapshot.pending.any { it.seq in snapshot.selected && PlanJournalSubject.decode(it.detail).attempt == attempt.id } }
                .flatMap { listOf(it.sessionId, "${it.sessionId}-merge", "${it.sessionId}-delivery") }
            (aliases + session.id).distinct().forEach { runtime.reconcile(it) }
            store.reconcileIntents(snapshot.plan, snapshot.pending, snapshot.selected, PlanRecoveryAuthority.USER)
            if (snapshot.plan.phase == ExecutionPhase.COMPLETE && store.unsettled(snapshot.plan.id).isEmpty()) {
                store.update(snapshot.plan.id) {
                    if (it.phase == ExecutionPhase.COMPLETE && it.issue == uncertainty) it.copy(issue = null, status = PlanStatus.DONE) else it
                }
            }
            AppLog.info("planning.recovery", "journal.confirmed", mapOf("planId" to snapshot.plan.id,
                "sessionId" to session.id, "count" to snapshot.selected.size.toString()))
        }
        organisms?.reconcileQuarantine(session, userConfirmed = true, expectedQuarantineOperationIds = checkNotNull(shown).quarantines)
        return QuarantineRecoveryOutcome.RESOLVED
    }

    private fun owner(plan: Plan, intent: JournalRecord, sessions: List<CodingSession>): String {
        val subject = PlanJournalSubject.decode(intent.detail)
        val worker = plan.milestones.firstOrNull { it.id == subject.stage }?.attempts
            ?.firstOrNull { it.id == subject.attempt }?.sessionId
        return worker?.takeIf { id -> sessions.any { it.id == id } } ?: plan.parentSessionId
    }

    private fun operationLabel(record: JournalRecord): String = when (PlanJournalOperation.of(record.operation)) {
        PlanJournalOperation.PREPARE_INTENT -> "подготовка рабочей папки"
        PlanJournalOperation.STAGE_WORKSPACE_INTENT -> "подготовка папки этапа"
        PlanJournalOperation.AGENT_INTENT -> "работа исполнителя"
        PlanJournalOperation.CAPTURE_INTENT -> "сохранение результата этапа"
        PlanJournalOperation.MERGE_INTENT -> "объединение результатов"
        PlanJournalOperation.CONFLICT_AGENT_INTENT -> "разрешение конфликта объединения"
        PlanJournalOperation.DELIVERY_CONFLICT_INTENT -> "разрешение конфликта переноса"
        PlanJournalOperation.FINAL_VERIFICATION_INTENT -> "итоговая проверка"
        PlanJournalOperation.APPLY_INTENT -> "применение результата"
        PlanJournalOperation.STOP_INTENT -> "остановка выполнения"
        else -> "операция плана"
    }

    companion object {
        val uncertainty = PlanningIssue(IssueKind.UNCERTAIN,
            "Результат операции не подтверждён. Проверьте запрос восстановления сессии перед продолжением.", requiresUser = true)
    }
}
