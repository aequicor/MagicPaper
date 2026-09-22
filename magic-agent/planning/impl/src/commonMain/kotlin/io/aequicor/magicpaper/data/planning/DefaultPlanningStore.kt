package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Sole plan writer. The original plan-id stream accepts old commits and new machine inputs. */
class DefaultPlanningStore(
    private val repo: PlanningCheckpointStore,
    private val events: EventJournal,
    private val knownSecrets: () -> Set<String> = { emptySet() },
) : PlanningStore {
    private data class Entry(var state: PlanningMachine.State, var snapshot: JournalSnapshot)
    private val lock = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    // Capabilities are issued only after a fresh command commits in this process, never by replay.
    private val admitted = MutableStateFlow<Map<String, PlanningMachine.RunRef>>(emptyMap())
    override val admittedRuns = admitted.asStateFlow()
    private val _states = MutableStateFlow<Map<String, PlanningMachine.State>>(emptyMap())
    override val machineStates = _states.asStateFlow()
    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    override val plans = _plans.asStateFlow()
    private val _failure = MutableStateFlow<String?>(null)
    override val failure = _failure.asStateFlow()
    private var loaded = false

    override fun currentAdmission(id: String): PlanningMachine.RunRef? = admitted.value[id]?.takeIf {
        _failure.value == null && _states.value[id]?.let { state -> state.run?.ref == it &&
            state.run?.phase == PlanningMachine.RunPhase.RUNNING && state.plan?.intent == ExecutionIntent.RUN && state.plan?.stopping != true && !state.persistenceUnknown && state.pendingOperations.isEmpty() } == true
    }
    private fun publish() {
        _states.value = entries.mapValues { it.value.state }
        _plans.value = entries.values.mapNotNull { it.state.takeUnless { it.deleted }?.plan }.sortedByDescending { it.updatedAt }
    }
    private fun writable() { _failure.value?.let { throw PlanningPersistenceException(it) } }
    private fun failed(cause: Throwable, entry: Entry? = null): Nothing {
        admitted.value = emptyMap()
        if(cause is PlanningRevisionConflictException) { loaded = false; throw cause }
        entry?.let {
            val before = it.state
            val input = PlanningMachine.Fact.PersistenceUnknown(stamp())
            val next = PlanningMachine.reduce(before, input)
            MachineTransitionLog.append(PlanningMachine.id, PlanningMachine.space, before, input, next.state, next.effects)
            it.state = next.state
        }
        _failure.value = "Не удалось сохранить планирование. Проверьте доступ к данным и восстановите состояние."
        publish()
        AppLog.error("planning.storage", "operation.failed", fields = mapOf("causeType" to (cause::class.simpleName ?: "Exception")))
        if(cause is CancellationException) throw cause
        if(cause is PlanningRevisionConflictException) throw cause
        throw PlanningPersistenceException(checkNotNull(_failure.value), cause)
    }
    private fun stamp() = PlanningMachine.Stamp(Id.new(), Id.now())
    private fun canonical(input: PlanningMachine.Input): PlanningMachine.Input {
        val secrets = knownSecrets()
        fun scrub(value: JsonElement): JsonElement = when(value) {
            is JsonObject -> JsonObject(value.mapValues { scrub(it.value) })
            is JsonArray -> JsonArray(value.map(::scrub))
            is JsonPrimitive -> if(value.isString) JsonPrimitive(PlanningDiagnostics.redact(value.content, secrets)) else value
        }
        return Json.decodeFromJsonElement(PlanningMachine.Input.serializer(), scrub(Json.encodeToJsonElement(PlanningMachine.Input.serializer(), input)))
    }
    private fun validate(snapshot: JournalSnapshot, id: String): PlanningMachine.State {
        require(snapshot.revision.stream == id && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) { "Invalid plan revision" }
        require(snapshot.records.isEmpty() || snapshot.records.last().seq == snapshot.revision.seq) { "Invalid plan high-water mark" }
        val state = projectPlanningJournal(id, snapshot.records, snapshot.revision.resetEpoch)
        unsettledPlanIntents(snapshot.records)
        return state
    }
    private suspend fun commit(entry: Entry, input: PlanningMachine.Input, operation: String = PLAN_INPUT_OPERATION,
        evidence: String = "", checkpoint: Boolean = true): Pair<PlanningMachine.Transition, JournalRecord?> {
        val frozen = canonical(input)
        entry.snapshot.records.mapNotNull(PlanInputCommit::from).firstOrNull { it.input.stamp.id == frozen.stamp.id }?.let { previous ->
            return PlanningMachine.Transition(entry.state, if(previous.input == frozen) emptyList() else
                listOf(PlanningMachine.Effect.Reject("Идентификатор команды уже использован"))) to null
        }
        val previousState = entry.state
        val transition = PlanningMachine.reduce(previousState, frozen)
        if (transition.rejection != null) {
            MachineTransitionLog.append(PlanningMachine.id, PlanningMachine.space, previousState, frozen, transition.state, transition.effects)
            return transition to null
        }
        val before = entry.snapshot
        val detail = PlanInputCommit(frozen, before.revision.resetEpoch, evidence).encode()
        try {
            val observed: JournalSnapshot
            val record: JournalRecord
            try {
                val ack = events.append(before.revision, operation, frozen.stamp.at, detail) ?: throw PlanningRevisionConflictException()
                observed = events.snapshot(entry.state.id)
                record = ack
            } catch (primary: Throwable) {
                if(primary is PlanningRevisionConflictException) throw primary
                val after = try { withContext(NonCancellable) { events.snapshot(entry.state.id) } } catch (read: Throwable) {
                    primary.addSuppressed(read); throw primary
                }
                val last = after.records.lastOrNull()
                if (last == null || last.operation != operation || last.at != frozen.stamp.at || last.detail != detail ||
                    last.stream != entry.state.id || last.seq <= before.revision.seq ||
                    after.revision.resetEpoch != before.revision.resetEpoch || after.records.size != before.records.size + 1 ||
                    after.records.dropLast(1) != before.records) throw primary
                require(validate(after, entry.state.id) == transition.state) { "Plan replay differs from accepted input" }
                MachineTransitionLog.append(PlanningMachine.id, PlanningMachine.space, previousState, frozen, transition.state, transition.effects)
                entry.state = transition.state; entry.snapshot = after; publish()
                if(primary is CancellationException) throw primary
                if(checkpoint) transition.state.plan?.let { repo.save(it) }
                return transition to last
            }
            require(record.stream == entry.state.id && record.operation == operation && record.at == frozen.stamp.at && record.detail == detail && record.seq > before.revision.seq &&
                observed.revision.resetEpoch == before.revision.resetEpoch && observed.records.size == before.records.size + 1 &&
                observed.records.dropLast(1) == before.records && observed.records.last() == record) { "Invalid plan append acknowledgement" }
            require(validate(observed, entry.state.id) == transition.state) { "Plan replay differs from accepted input" }
            MachineTransitionLog.append(PlanningMachine.id, PlanningMachine.space, previousState, frozen, transition.state, transition.effects)
            entry.state = transition.state; entry.snapshot = observed; publish()
            if(checkpoint) transition.state.plan?.let { repo.save(it) }
            return transition to record
        } catch (failure: Throwable) { failed(failure, entry) }
    }
    private suspend fun initialize() {
        if(loaded) return
        try {
            val checkpoints = repo.plans().associateBy { it.id }
            val found = linkedMapOf<String, Entry>()
            for(id in (checkpoints.keys + events.streams()).sorted()) {
                val snapshot = events.snapshot(id)
                if(id !in checkpoints && snapshot.records.none { it.operation in setOf(PLAN_STATE_OPERATION, PLAN_INPUT_OPERATION) ||
                    it.detail.startsWith("plan-commit/") || PlanJournalOperation.of(it.operation) != null }) continue
                var state = validate(snapshot, id)
                val entry = Entry(state, snapshot)
                if(state.plan == null) {
                    if(snapshot.records.isEmpty() && snapshot.revision.seq != 0L) {
                        if(!events.drop(snapshot.revision)) throw PlanningRevisionConflictException()
                        repo.deletePlan(id); continue
                    }
                    val legacy = checkpoints[id] ?: error("Missing initial plan state")
                    require(legacy.id == id)
                    commit(entry, PlanningMachine.Fact.LegacyImported(legacy, unsettledPlanIntents(snapshot.records).map { it.seq }.toSet(), stamp()))
                    state = entry.state
                }
                if(entry.state.deleted) {
                    if(!events.drop(entry.snapshot.revision)) throw PlanningRevisionConflictException()
                    repo.deletePlan(id); continue
                }
                val pending = unsettledPlanIntents(entry.snapshot.records).map { it.seq }.toSet()
                if(pending != state.pendingOperations) commit(entry, PlanningMachine.Fact.EvidenceObserved(pending, stamp()))
                if(entry.state.run?.phase in setOf(PlanningMachine.RunPhase.RUNNING, PlanningMachine.RunPhase.STOPPING))
                    commit(entry, PlanningMachine.Fact.Restored(stamp()))
                found[id] = entry
            }
            entries.clear(); entries.putAll(found); admitted.value = emptyMap(); loaded = true; publish()
        } catch(failure: Throwable) { failed(failure) }
    }
    override suspend fun plans(): List<Plan> = lock.withLock { initialize(); _plans.value }
    override suspend fun planFor(projectId: String): Plan? = lock.withLock { initialize(); _plans.value.resolvePlan(projectId) }
    override suspend fun dispatch(id: String, input: PlanningMachine.Input): PlanningMachine.Transition = lock.withLock {
        writable(); initialize()
        require(input !is PlanningMachine.Intent.RefineRequested &&
            (input !is PlanningMachine.Intent.Revise || input.events.none { it is PlanRevisionEvent.RequestStarted ||
                it is PlanRevisionEvent.RequestCleared || it is PlanRevisionEvent.ProposalApplied ||
                it is PlanRevisionEvent.ProposalPrepared || it is PlanRevisionEvent.RefinementFinished ||
                it is PlanRevisionEvent.AssignmentsRecovered })) {
            "Изменение плана требует точной семантической команды"
        }
        require(input !is PlanningMachine.Fact.AttemptRecorded && input !is PlanningMachine.Fact.LegacyImported && input !is PlanningMachine.Fact.LegacyCheckpoint && input !is PlanningMachine.Fact.PersistenceUnknown &&
            input !is PlanningMachine.Fact.EvidenceObserved && input !is PlanningMachine.Fact.OperationUnknown && input !is PlanningMachine.Fact.Restored && input !is PlanningMachine.Fact.StrategySelected) { "Служебный вход журнала недоступен извне" }
        // Missing identity exists only in older accepted journal entries, never in a live command.
        require(input !is PlanningMachine.Fact.StopConfirmed || !input.expectedStopId.isNullOrBlank()) { "Не указан запрос остановки" }
        require(input !is PlanningMachine.Fact.StopUnknown || !input.expectedStopId.isNullOrBlank()) { "Не указан запрос остановки" }
        require(input !is PlanningMachine.Intent.SkipVerification || input.proofs != null) { "Не указано подтверждение проверки" }
        if(input is PlanningMachine.Fact.JournalObserved) require(input.operation.kind != JournalEntryKind.INTENT && input.operation !in setOf(
            PlanJournalOperation.INTENT_OUTCOME, PlanJournalOperation.INTENT_RECONCILED, PlanJournalOperation.STRATEGY_SELECTED,
            PlanJournalOperation.STOP_CONFIRMED, PlanJournalOperation.APPLY_COMPLETE)) { "Доказательство операции принимает только владелец журнала" }
        val resolved = _plans.value.resolvePlan(id)?.id ?: id
        val entry = entries[resolved] ?: run {
            val snapshot = events.snapshot(resolved)
            validate(snapshot, resolved)
            if(snapshot.records.isEmpty() && snapshot.revision.seq > 0) return@withLock PlanningMachine.Transition(
                PlanningMachine.initial(resolved), listOf(PlanningMachine.Effect.Reject("Идентификатор удалённого плана закрыт")))
            Entry(PlanningMachine.initial(resolved), snapshot).also { entries[resolved] = it }
        }
        if(input is PlanningMachine.Intent.ConfirmNativeRecovery)
            require(entry.snapshot.revision.seq == input.value.expectedJournalSeq && entry.snapshot.revision.resetEpoch == input.value.expectedJournalEpoch) { "Журнал изменился; повторите сверку" }
        val nativeAdmission = (input as? PlanningMachine.Fact.NativeObserved)?.value as? PlanningNativeFact.RequestAdmitted
        if(nativeAdmission != null) {
            val request = nativeAdmission.request
            require(admitted.value[resolved] == request.ref) { "Разрешение текущего процесса отсутствует" }
            val intent = unsettledPlanIntents(entry.snapshot.records).singleOrNull { it.seq == request.intentSeq }
            val admittedIntent = entry.snapshot.records.singleOrNull { it.seq == request.intentSeq }
                ?.let(PlanInputCommit::from)?.input as? PlanningMachine.Fact.JournalObserved
            require(admittedIntent?.ref == request.ref) { "Намерение принадлежит другому разрешению запуска" }
            require(entry.snapshot.revision.resetEpoch == request.journalEpoch && intent != null &&
                PlanJournalOperation.of(intent.operation) == request.operation && PlanJournalOperation.of(intent.operation) in setOf(PlanJournalOperation.AGENT_INTENT,
                PlanJournalOperation.CONFLICT_AGENT_INTENT, PlanJournalOperation.FINAL_VERIFICATION_INTENT,
                PlanJournalOperation.DELIVERY_CONFLICT_INTENT) && PlanJournalSubject.decode(intent.detail).let {
                it.stage == request.stageId && it.attempt == request.attemptId
            }) { "Запрос не принадлежит открытому намерению" }
        }
        if(input is PlanningMachine.Intent.Delete && unsettledPlanIntents(entry.snapshot.records).isNotEmpty())
            return@withLock PlanningMachine.Transition(entry.state, listOf(PlanningMachine.Effect.Reject("Сначала подтвердите исход незавершённой операции")))
        if(input is PlanningMachine.Intent.Resume && admitted.value[resolved] != input.ref)
            return@withLock PlanningMachine.Transition(entry.state, listOf(PlanningMachine.Effect.Reject("Разрешение текущего процесса отсутствует")))
        val operation = when(input) {
            is PlanningMachine.Intent.Stop -> PlanJournalOperation.STOP_INTENT.wire
            is PlanningMachine.Fact.StopConfirmed -> PlanJournalOperation.STOP_CONFIRMED.wire
            is PlanningMachine.Fact.Applied -> PlanJournalOperation.APPLY_COMPLETE.wire
            is PlanningMachine.Fact.JournalObserved -> input.operation.wire
            else -> PLAN_INPUT_OPERATION
        }
        val evidence = if(input is PlanningMachine.Fact.JournalObserved) input.detail else ""
        val result = withContext(NonCancellable) { commit(entry, input, operation, evidence).first }
        result.effects.filterIsInstance<PlanningMachine.Effect.RunRequested>().singleOrNull()?.let { admitted.value = admitted.value + (resolved to it.ref) }
        if(result.state.run?.phase !in setOf(PlanningMachine.RunPhase.RUNNING, PlanningMachine.RunPhase.PAUSED)) admitted.value = admitted.value - resolved
        result
    }
    override suspend fun recover() = lock.withLock {
        admitted.value = emptyMap(); loaded = false; _failure.value = null
        initialize()
        try { _plans.value.forEach { repo.save(it) } } catch(failure: Throwable) { failed(failure) }
    }
    override suspend fun revokeAdmissions() = lock.withLock {
        initialize(); admitted.value = emptyMap()
        entries.values.filter { it.state.run?.phase == PlanningMachine.RunPhase.RUNNING }.forEach { commit(it, PlanningMachine.Fact.Restored(stamp())) }
    }
    override suspend fun beginIntent(projectId: String, operation: PlanJournalOperation, stageId: String, attemptId: String, ref: PlanningMachine.RunRef): JournalRecord = lock.withLock {
        writable(); initialize()
        val plan = _plans.value.resolvePlan(projectId) ?: error("План не найден")
        require(admitted.value[plan.id] == ref && entries[plan.id]?.state?.run?.ref == ref) { "Разрешение запуска отсутствует или устарело" }
        require(operation.kind == JournalEntryKind.INTENT)
        val entry = entries.getValue(plan.id)
        val input = PlanningMachine.Fact.JournalObserved(operation, stageId, attemptId, ref = ref, stamp = stamp())
        val result = withContext(NonCancellable) { commit(entry, input, operation.wire, PlanJournalSubject.encode(stageId, attemptId)) }
        result.first.rejection?.let { error(it.reason) }
        checkNotNull(result.second).planEvidence()
    }
    override suspend fun finishIntent(intent: JournalRecord, status: PlanIntentStatus) = lock.withLock {
        initialize()
        val entry = refreshEntry(intent.stream) ?: error("Missing plan intent")
        require(entry.snapshot.records.any { it.planEvidence() == intent }) { "Intent belongs to another plan" }
        val latched = entry.state.persistenceUnknown
        if(latched) entry.state = validate(entry.snapshot, intent.stream)
        val strategy = planStrategyFacts(entry.snapshot.records).strategyFor(intent)
        val outcome = PlanIntentOutcome(intent.seq, status, strategy).encode()
        val subject = PlanJournalSubject.decode(intent.detail)
        withContext(NonCancellable) {
            commit(entry, PlanningMachine.Fact.JournalObserved(PlanJournalOperation.INTENT_OUTCOME, subject.stage, subject.attempt,
                PlanIntentOutcome(intent.seq, status).encode(), stamp = stamp()), PlanJournalOperation.INTENT_OUTCOME.wire, outcome, checkpoint = _failure.value == null)
        }
        val pending = unsettledPlanIntents(entry.snapshot.records).map { it.seq }.toSet()
        if(entry.state.pendingOperations.isNotEmpty() && pending != entry.state.pendingOperations)
            commit(entry, PlanningMachine.Fact.EvidenceObserved(pending, stamp()), checkpoint = _failure.value == null)
        if(latched) {
            val before = entry.state
            val input = PlanningMachine.Fact.PersistenceUnknown(stamp())
            val next = PlanningMachine.reduce(before, input)
            MachineTransitionLog.append(PlanningMachine.id, PlanningMachine.space, before, input, next.state, next.effects)
            entry.state = next.state; publish()
        }
        Unit
    }
    override suspend fun markIntentUnknown(intent: JournalRecord) = lock.withLock {
        initialize()
        val entry = entries[intent.stream] ?: error("Missing plan intent")
        require(entry.snapshot.records.any { it.planEvidence() == intent })
        admitted.value = admitted.value - intent.stream
        withContext(NonCancellable) { commit(entry, PlanningMachine.Fact.OperationUnknown(intent.seq, stamp())) }
        Unit
    }
    /** Reads may discover another writer's accepted facts; they never preserve this process's grant. */
    private suspend fun refreshEntry(id: String): Entry? {
        val entry = entries[id] ?: return null
        val observed = events.snapshot(id)
        if(observed == entry.snapshot) return entry
        if(observed.revision.resetEpoch != entry.snapshot.revision.resetEpoch || observed.records.isEmpty()) {
            admitted.value = admitted.value - id
            throw PlanningRevisionConflictException()
        }
        if(observed.revision.seq < entry.snapshot.revision.seq || observed.records.size < entry.snapshot.records.size ||
            observed.records.take(entry.snapshot.records.size) != entry.snapshot.records)
            failed(IllegalStateException("Planning journal history changed"), entry)
        val state = try { validate(observed, id) } catch(failure: Throwable) { failed(failure, entry) }
        admitted.value = admitted.value - id
        entry.state = state; entry.snapshot = observed; publish()
        val pending = unsettledPlanIntents(observed.records).map { it.seq }.toSet()
        if(pending != state.pendingOperations) commit(entry, PlanningMachine.Fact.EvidenceObserved(pending, stamp()))
        if(entry.state.run?.phase in setOf(PlanningMachine.RunPhase.RUNNING, PlanningMachine.RunPhase.STOPPING))
            commit(entry, PlanningMachine.Fact.Restored(stamp()))
        return entry
    }
    override suspend fun unsettled(stream: String): List<JournalRecord> = lock.withLock {
        initialize(); val entry = refreshEntry(stream) ?: return@withLock emptyList()
        unsettledPlanIntents(entry.snapshot.records)
    }
    override suspend fun journalPlan(id: String): JournalPlanSnapshot? = lock.withLock {
        initialize(); val entry = refreshEntry(id) ?: return@withLock null
        entry.state.plan?.let { JournalPlanSnapshot(it, entry.snapshot) }
    }
    override suspend fun recordStrategy(expected: JournalPlanSnapshot, selection: PlanStrategySelection, retryLimit: Int?): Plan? = lock.withLock {
        writable(); initialize()
        val entry = entries[expected.plan.id] ?: return@withLock null
        if(entry.state.plan != expected.plan || entry.snapshot != expected.journal) return@withLock null
        val facts = planStrategyFacts(entry.snapshot.records)
        val trigger = detectPlanStrategy(expected.plan, facts.samples) ?: return@withLock null
        require(selection.sourceSeq == trigger.sourceSeq && selection.metrics == trigger.metrics && selection.runId == trigger.runId && selection.stageId == trigger.stageId)
        if(facts.selections.any { it.second.sourceSeq == trigger.sourceSeq }) return@withLock null
        val transition = commit(entry, PlanningMachine.Fact.StrategySelected(selection, retryLimit, stamp()),
            PlanJournalOperation.STRATEGY_SELECTED.wire, selection.encode()).first
        transition.rejection?.let { error(it.reason) }
        transition.state.plan
    }
    override suspend fun reconcileIntents(expectedPlan: Plan, expected: List<JournalRecord>, resolving: Set<Long>, authority: PlanRecoveryAuthority,
        expectedJournal: JournalSnapshot?) = lock.withLock {
        writable(); initialize()
        val entry = refreshEntry(expectedPlan.id) ?: error("План не найден")
        require(expectedJournal == null || entry.snapshot == expectedJournal) { "Журнал изменился; повторите сверку" }
        require(entry.state.plan == expectedPlan)
        val pending = unsettledPlanIntents(entry.snapshot.records)
        require(pending == expected && resolving.isNotEmpty() && pending.map { it.seq }.containsAll(resolving))
        withContext(NonCancellable) {
            pending.filter { it.seq in resolving }.forEach { intent ->
                val subject = PlanJournalSubject.decode(intent.detail)
                val detail = PlanIntentReconciliation(intent.seq, authority).encode()
                commit(entry, PlanningMachine.Fact.JournalObserved(PlanJournalOperation.INTENT_RECONCILED, subject.stage, subject.attempt, detail, stamp = stamp()),
                    PlanJournalOperation.INTENT_RECONCILED.wire, detail)
            }
            commit(entry, PlanningMachine.Fact.EvidenceObserved(unsettledPlanIntents(entry.snapshot.records).map { it.seq }.toSet(), stamp()))
        }
        Unit
    }
    override suspend fun deletePlan(projectId: String) {
        val plan = planFor(projectId) ?: return
        command(plan.id, PlanningMachine.Intent.Delete(stamp()))
        lock.withLock {
            val entry = entries.getValue(plan.id)
            try {
                withContext(NonCancellable) {
                    if(!events.drop(entry.snapshot.revision)) throw PlanningRevisionConflictException()
                    entry.snapshot = events.snapshot(plan.id)
                    repo.deletePlan(plan.id)
                }
            } catch(failure: Throwable) { failed(failure, entry) }
        }
    }
    override suspend fun wipe() = lock.withLock {
        initialize()
        check(entries.values.none { unsettledPlanIntents(it.snapshot.records).isNotEmpty() || it.state.pendingOperations.isNotEmpty() || it.state.run?.phase in setOf(PlanningMachine.RunPhase.RUNNING, PlanningMachine.RunPhase.STOPPING, PlanningMachine.RunPhase.UNKNOWN) }) { "Исход планирования не подтверждён" }
        withContext(NonCancellable) {
            entries.values.forEach { if(!events.drop(it.snapshot.revision)) throw PlanningRevisionConflictException() }
            repo.wipe(); entries.clear(); admitted.value = emptyMap(); _failure.value = null; loaded = true; publish()
        }
        Unit
    }
}
