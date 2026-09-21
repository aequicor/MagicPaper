package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.planning.command
import io.aequicor.magicpaper.data.planning.PlanInputCommit
import io.aequicor.magicpaper.data.planning.JournalPlanSnapshot
import io.aequicor.magicpaper.data.planning.unsettledPlanIntents
import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A one-use view of the exact operations the person is asked to inspect. No execution grant. */
data class PlanningRecoveryInspection(val id: String, val planId: String, val operations: List<String>)

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
    private data class Snapshot(val snapshot: JournalPlanSnapshot, val pending: List<JournalRecord>, val selected: Set<Long>,
        val native: List<PlanningNativeEvidence>) { val plan get() = snapshot.plan }
    private data class Confirmation(val snapshots: List<Snapshot>, val quarantines: Set<String>)
    private val confirmations = mutableMapOf<String, Confirmation>()
    private data class PlanConfirmation(val inspection: PlanningRecoveryInspection, val snapshot: JournalPlanSnapshot,
        val pending: List<JournalRecord>, val sessions: List<CodingSession>, val absentAuxiliarySessions: Set<String>,
        val quarantines: Map<String, Set<String>>, val native: List<PlanningNativeEvidence>)
    private data class NativeSettlement(val snapshot: JournalPlanSnapshot, val evidence: List<PlanningNativeEvidence>, val aliases: List<String>)
    private val planConfirmations = mutableMapOf<String, PlanConfirmation>()
    private val native = PlanningNativeRecoveryInterpreter(store, runtime)

    /** All of a plan's owners are inspected together; confirming one must not stale another. */
    suspend fun inspectPlan(planId: String): PlanningRecoveryInspection {
        planConfirmations.remove(planId)
        val snapshot = checkNotNull(store.journalPlan(planId)) { "План недоступен" }
        val pending = unsettledPlanIntents(snapshot.journal.records)
        require(pending.isNotEmpty()) { "Нет операций для сверки" }
        val sessions = projects?.sessions(snapshot.plan.projectId).orEmpty()
        val absentAuxiliarySessions = mutableSetOf<String>()
        val ids = pending.mapNotNull { record ->
            if (requiresNativeOwner(record)) {
                val subject = PlanJournalSubject.decode(record.detail)
                val finalOwned = subject.stage.isBlank() && snapshot.plan.finalAttempt?.id == subject.attempt &&
                    PlanJournalOperation.of(record.operation) in setOf(PlanJournalOperation.FINAL_VERIFICATION_INTENT,
                        PlanJournalOperation.DELIVERY_CONFLICT_INTENT)
                val attempt = (snapshot.plan.milestones.firstOrNull { it.id == subject.stage }?.attempts.orEmpty() +
                    listOfNotNull(snapshot.plan.finalAttempt).filter { finalOwned })
                    .singleOrNull { it.id == subject.attempt }
                require(attempt != null && attempt.sessionId.isNotBlank()) { "Попытка предыдущей работы недоступна" }
                val session = sessions.singleOrNull { it.id == attempt.sessionId }
                // Final verification/delivery sessions are auxiliary: the plan's saved final attempt
                // owns their identity even when there is no separate project-session row.
                require((session == null && finalOwned) || (session != null && session.planId == planId &&
                    (attempt.sessionGeneration == 0L || session.runtimeGeneration == attempt.sessionGeneration))) {
                    "Сессия предыдущей работы недоступна"
                }
                if (session == null) absentAuxiliarySessions += attempt.sessionId
                session?.id
            } else owner(snapshot.plan, record, sessions).takeIf { id -> id.isNotBlank() && sessions.any { it.id == id } }
        }.toSet()
        quarantine(snapshot.plan, pending)
        // Quarantine can add organism metadata to a session; capture its final owned identity.
        val owned = projects?.sessions(snapshot.plan.projectId).orEmpty().filter { it.id in ids }.sortedBy { it.id }
        require(owned.size == ids.size && store.journalPlan(planId) == snapshot) { "Состояние изменилось; повторите сверку" }
        val inspection = PlanningRecoveryInspection(Id.new(), planId, pending.map { record ->
            val stage = snapshot.plan.milestones.firstOrNull { it.id == PlanJournalSubject.decode(record.detail).stage }
            operationLabel(record).replaceFirstChar { it.titlecase() } + stage?.let { " · ${it.title}" }.orEmpty()
        })
        planConfirmations[planId] = PlanConfirmation(inspection, snapshot, pending, owned, absentAuxiliarySessions.toSet(),
            owned.associate { it.id to quarantineIds(it) }, native.inspect(planId, pending.map { it.seq }.toSet()))
        return inspection
    }

    suspend fun confirmPlan(inspection: PlanningRecoveryInspection) {
        // A failed or partially persisted confirmation requires a fresh inspection, never replay.
        val shown = planConfirmations.remove(inspection.planId)
        require(shown?.inspection == inspection) { "Повторите сверку перед подтверждением" }
        checkNotNull(shown)
        suspend fun validate() {
            require(store.journalPlan(inspection.planId) == shown.snapshot) { "Состояние изменилось; повторите сверку" }
            val sessions = projects?.sessions(shown.snapshot.plan.projectId).orEmpty()
            require(sessions.none { it.id in shown.absentAuxiliarySessions } &&
                shown.sessions.all { expected -> sessions.singleOrNull { it.id == expected.id } == expected } &&
                shown.sessions.all { quarantineIds(it) == shown.quarantines[it.id] }) { "Сессии изменились; повторите сверку" }
        }
        validate()
        val attempts = shown.snapshot.plan.milestones.flatMap { it.attempts } + listOfNotNull(shown.snapshot.plan.finalAttempt)
        val aliases = attempts.filter { attempt -> shown.pending.any { PlanJournalSubject.decode(it.detail).attempt == attempt.id } }
            .flatMap { listOf(it.sessionId, "${it.sessionId}-merge", "${it.sessionId}-delivery") }
        val updated = settleNative(shown.snapshot, shown.pending, shown.pending.map { it.seq }.toSet(), shown.native,
            (aliases + shown.sessions.map { it.id }).distinct())
        currentCoroutineContext().ensureActive()
        val sessions = projects?.sessions(shown.snapshot.plan.projectId).orEmpty()
        require(sessions.none { it.id in shown.absentAuxiliarySessions } &&
            shown.sessions.all { expected -> sessions.singleOrNull { it.id == expected.id } == expected } &&
            shown.sessions.all { quarantineIds(it) == shown.quarantines[it.id] }) { "Сессии изменились; повторите сверку" }
        for (session in shown.sessions) reconcilePlanningOwner(listOf(NativeSettlement(updated, shown.native, aliases)), session, shown.quarantines.getValue(session.id))
        store.reconcileIntents(shown.snapshot.plan, shown.pending, shown.pending.map { it.seq }.toSet(),
            PlanRecoveryAuthority.USER, expectedJournal = updated.journal)
        store.command(inspection.planId,
            PlanningMachine.Fact.RecoveryConfirmed(PlanningMachine.Stamp(Id.new(), Id.now())))
        AppLog.info("planning.recovery", "plan.confirmed", mapOf("planId" to inspection.planId,
            "inspectionId" to inspection.id, "count" to shown.pending.size.toString()))
    }

    fun clearInspections() { confirmations.clear(); planConfirmations.clear() }

    private fun requiresNativeOwner(record: JournalRecord): Boolean = when (PlanJournalOperation.of(record.operation)) {
        PlanJournalOperation.PREPARE_INTENT, PlanJournalOperation.STAGE_WORKSPACE_INTENT,
        PlanJournalOperation.CAPTURE_INTENT, PlanJournalOperation.MERGE_INTENT,
        PlanJournalOperation.APPLY_INTENT, PlanJournalOperation.STOP_INTENT -> false
        else -> true
    }

    private suspend fun quarantineIds(session: CodingSession): Set<String> {
        val service = organisms ?: return emptySet()
        val organism = service.ensure(session)
        return organism.unresolvedQuarantines(session.id).map { it.operationId }.toSet()
    }

    private suspend fun reconcilePlanningOwner(settlements: List<NativeSettlement>, expected: CodingSession, operations: Set<String>) {
        val service = organisms ?: return
        if (settlements.all { it.evidence.isEmpty() }) {
            service.reconcileQuarantine(expected, userConfirmed = true, expectedQuarantineOperationIds = operations)
            return
        }
        service.reconcilePlanningQuarantine(expected, PlanningQuarantineRecoveryAuthority { current, generation, actualOperations ->
            require(current.id == expected.id && current.projectId == expected.projectId && current.organismId == expected.organismId &&
                current.planId == expected.planId && current.stageId == expected.stageId && current.piSessionId == expected.piSessionId &&
                current.pendingRun == expected.pendingRun && current.runtimeGeneration == expected.runtimeGeneration &&
                generation == expected.runtimeGeneration && actualOperations == operations) { "Сессия восстановления изменилась" }
            for (settled in settlements) {
                val snapshot = settled.snapshot
                require(store.journalPlan(snapshot.plan.id) == snapshot) { "Журнал изменился; повторите сверку" }
                native.verifyAuthorizedCleanup(snapshot.plan.id, settled.evidence, settled.aliases + expected.id)
            }
            require(settlements.all { store.journalPlan(it.snapshot.plan.id) == it.snapshot }) { "Журнал изменился; повторите сверку" }
        })
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
            val snapshot = checkNotNull(store.journalPlan(plan.id))
            val pending = unsettledPlanIntents(snapshot.journal.records)
            val selected = pending.filter { owner(plan, it, sessions) == session.id }.map { it.seq }.toSet()
            if (selected.isEmpty()) null else Snapshot(snapshot, pending, selected, native.inspect(plan.id, selected))
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
        val settled = snapshots.map { snapshot ->
            val aliases = snapshot.plan.milestones.flatMap { it.attempts }.plus(listOfNotNull(snapshot.plan.finalAttempt))
                .filter { attempt -> snapshot.pending.any { it.seq in snapshot.selected && PlanJournalSubject.decode(it.detail).attempt == attempt.id } }
                .flatMap { listOf(it.sessionId, "${it.sessionId}-merge", "${it.sessionId}-delivery") }
            val updated = settleNative(snapshot.snapshot, snapshot.pending, snapshot.selected, snapshot.native,
                (aliases + session.id).distinct())
            snapshot to NativeSettlement(updated, snapshot.native, aliases)
        }
        // One session owns one quarantine set even when the inspected batch spans several plans.
        reconcilePlanningOwner(settled.map { it.second }, session, checkNotNull(shown).quarantines)
        for ((snapshot, completed) in settled) {
            store.reconcileIntents(snapshot.plan, snapshot.pending, snapshot.selected, PlanRecoveryAuthority.USER,
                expectedJournal = completed.snapshot.journal)
            if (store.unsettled(snapshot.plan.id).isEmpty()) {
                store.command(snapshot.plan.id, PlanningMachine.Fact.RecoveryConfirmed(
                    PlanningMachine.Stamp(io.aequicor.magicpaper.util.Id.new(), io.aequicor.magicpaper.util.Id.now())))
            }
            AppLog.info("planning.recovery", "journal.confirmed", mapOf("planId" to snapshot.plan.id,
                "sessionId" to session.id, "count" to snapshot.selected.size.toString()))
        }
        return QuarantineRecoveryOutcome.RESOLVED
    }

    /** Persist the explicit decision before native ACK. A lost reply is reconciled using this same identity. */
    private suspend fun settleNative(snapshot: JournalPlanSnapshot, pending: List<JournalRecord>, selected: Set<Long>,
        evidence: List<PlanningNativeEvidence>, aliases: List<String>): JournalPlanSnapshot {
        require(store.journalPlan(snapshot.plan.id) == snapshot) { "Журнал изменился; повторите сверку" }
        val state = checkNotNull(store.machineStates.value[snapshot.plan.id]).nativeRecovery
        if(runtime.recovery != null) {
            val correlated = state.requests.filter { it.intentSeq in selected }.map { it.intentSeq }.toSet()
            require(pending.filter { it.seq in selected && requiresNativeOwner(it) }.all { it.seq in correlated }) {
                "Идентичность предыдущего запроса не сохранена; безопасное продолжение недоступно"
            }
        }
        val missing = evidence.filter { expected ->
            state.decisions[expected.requestId]?.let { saved ->
                require(saved.evidence.single { it.requestId == expected.requestId } == expected) { "Решение изменилось" }
                false
            } ?: true
        }
        val decision = missing.takeIf { it.isNotEmpty() }?.let { PlanningNativeRecoveryDecision(Id.new(), snapshot.plan.revision,
            snapshot.journal.revision.seq, snapshot.journal.revision.resetEpoch, pending.map { it.seq }.toSet(), it) }
        decision?.let { store.command(snapshot.plan.id, PlanningMachine.Intent.ConfirmNativeRecovery(it,
            PlanningMachine.Stamp(Id.new(), Id.now()))) }
        native.acknowledge(snapshot.plan.id, evidence)
        aliases.forEach { native.stopResources(it) }
        native.authorizeReleases(snapshot.plan.id, evidence)
        currentCoroutineContext().ensureActive()
        val after = checkNotNull(store.journalPlan(snapshot.plan.id))
        // Only our saved native decision and its exact returned ACKs may have advanced the inspected prefix.
        require(after.plan == snapshot.plan && after.journal.revision.resetEpoch == snapshot.journal.revision.resetEpoch &&
            after.journal.records.take(snapshot.journal.records.size) == snapshot.journal.records &&
            unsettledPlanIntents(after.journal.records) == pending) { "Состояние изменилось; повторите сверку" }
        require(after.journal.records.drop(snapshot.journal.records.size).all { record ->
            when(val input = PlanInputCommit.from(record)?.input) {
                is PlanningMachine.Intent.ConfirmNativeRecovery -> input.value == decision
                is PlanningMachine.Fact.NativeObserved -> when(val fact = input.value) {
                    is PlanningNativeFact.Acknowledged -> evidence.any { it.requestId == fact.requestId } &&
                        store.machineStates.value[snapshot.plan.id]?.nativeRecovery?.decisions?.get(fact.requestId)?.id == fact.decisionId
                    is PlanningNativeFact.ConsumptionObserved -> evidence.any { it.requestId == fact.requestId }
                    is PlanningNativeFact.ReleaseAuthorized -> fact.releases.all { release ->
                        evidence.any { it.requestId == release.requestId } &&
                            store.machineStates.value[snapshot.plan.id]?.nativeRecovery?.releaseAuthorizations?.get(release.requestId) == release
                    }
                    else -> false
                }
                else -> false
            }
        }) { "Журнал изменился; повторите сверку" }
        return after
    }

    private fun owner(plan: Plan, intent: JournalRecord, sessions: List<CodingSession>): String {
        val subject = PlanJournalSubject.decode(intent.detail)
        val worker = (plan.milestones.firstOrNull { it.id == subject.stage }?.attempts.orEmpty() + listOfNotNull(plan.finalAttempt))
            .firstOrNull { it.id == subject.attempt }?.sessionId
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
        val uncertainty = PlanningRecoveryIssues.journalUncertainty
    }
}
