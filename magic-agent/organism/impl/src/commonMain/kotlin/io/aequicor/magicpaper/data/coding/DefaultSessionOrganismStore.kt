package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Sole durable interpreter. Legacy snapshots are compatibility projections, never commit authority. */
class DefaultSessionOrganismStore(private val storage: KeyValueStore, private val journal: EventJournal,
    private val knownSecrets: () -> Set<String> = { emptySet() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default, private val clock: () -> Long = Id::now) : SessionOrganismStore {
    @Serializable private data class Envelope(val ref: OrganismInputRef, val resetEpoch: Long)
    private data class Entry(var state: SessionOrganismMachine.State, var revision: JournalRevision,
        val records: MutableList<JournalRecord>, var inputId: String = "")
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val inputs = OrganismInputStore(storage, json)
    private val entries = mutableMapOf<String, Entry>()
    private val mutableOrganisms = MutableStateFlow<Map<String, SessionOrganism>>(emptyMap())
    override val organisms = mutableOrganisms.asStateFlow()
    private val mutableProjections = MutableStateFlow<Map<String, SessionOrganismProjection>>(emptyMap())
    override val projections = mutableProjections.asStateFlow()
    private val mutableFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    override val failures = mutableFailures.asStateFlow()
    private fun stamp() = SessionOrganismMachine.Stamp(Id.new(), clock())
    private fun stream(id: String) = PREFIX + encodedOrganismId(id)
    private fun key(id: String) = "session-organism-$id"

    override suspend fun get(id: String): SessionOrganism = withContext(dispatcher) { lock.withLock {
        checkNotNull(load(id).state.organism) { "Организм не найден" }
    } }
    override suspend fun projection(id: String): SessionOrganismProjection = withContext(dispatcher) { lock.withLock {
        load(id).projection()
    } }
    private var initialized = false
    private var loadFailure: Exception? = null
    override suspend fun loadAll(): List<SessionOrganism> = withContext(dispatcher) { lock.withLock {
        initialize()
        entries.values.mapNotNull { it.state.organism }
    } }
    override suspend fun dispatch(id: String, input: SessionOrganismMachine.Input): SessionOrganismMachine.Transition =
        withContext(dispatcher) { lock.withLock { dispatchLocked(id, load(id), sanitize(input)) } }

    private suspend fun load(id: String): Entry {
        initialize()
        return entries.getOrPut(id) {
            Entry(SessionOrganismMachine.initial(id), JournalRevision(stream(id), 0), mutableListOf())
        }.also { entry ->
            if (entry.records.isEmpty() && entry.state.organism == null)
                entry.revision = validatedSnapshot(stream(id)).revision
        }
    }

    /** Discover all owners before any import or publication so a tombstone also reserves its identity. */
    private suspend fun initialize() {
        loadFailure?.let { throw it }
        if (initialized) return
        val imports = mutableMapOf<String, SessionOrganismMachine.Input>()
        try {
            for (source in journal.streams().filter { it.startsWith(PREFIX) }) {
                val snapshot = validatedSnapshot(source)
                val first = checkNotNull(snapshot.records.firstOrNull()) { "Пустой журнал организма" }
                val id = json.decodeFromString(Envelope.serializer(), first.detail).ref.organismId
                var state = SessionOrganismMachine.initial(id)
                var inputId = ""
                for (record in snapshot.records) {
                    val input = inputs.read(json.decodeFromString(Envelope.serializer(), record.detail).ref)
                    check(input.stamp.at == record.at) { "Время ввода организма изменилось" }
                    val next = SessionOrganismMachine.reduce(state, input)
                    check(next.reject == null) { "Повреждён переход организма" }
                    state = next.state
                    inputId = input.stamp.id
                }
                entries[id] = Entry(state, snapshot.revision, snapshot.records.toMutableList(), inputId)
            }
            for (legacyKey in storage.keys("session-organism-")) {
                val id = legacyKey.removePrefix("session-organism-")
                if (id in entries) continue
                val old = json.decodeFromString<SessionOrganism>(checkNotNull(storage.read(legacyKey)))
                check(old.id == id) { "Снимок принадлежит другому организму" }
                val input = sanitize(SessionOrganismMachine.Fact.LegacyImported(stamp(), old))
                val next = SessionOrganismMachine.reduce(SessionOrganismMachine.initial(id), input)
                check(next.reject == null) { "Повреждён снимок организма" }
                entries[id] = Entry(next.state, validatedSnapshot(stream(id)).revision, mutableListOf())
                imports[id] = input
            }
            entries.forEach { (id, entry) -> validateIdentities(id, entry.state) }
            for ((id, input) in imports) {
                val entry = entries.getValue(id)
                val imported = entry.state
                entry.state = SessionOrganismMachine.initial(id)
                commit(id, entry, input, imported)
            }
            for ((id, entry) in entries) {
                if (entry.state.organism != null) {
                    dispatchLocked(id, entry, SessionOrganismMachine.Fact.LimitPolicyMigrated(stamp()))
                    publish(id, entry)
                }
            }
            initialized = true
        } catch (failure: Exception) {
            loadFailure = failure
            mutableOrganisms.value = emptyMap(); mutableProjections.value = emptyMap()
            report("restore", "replay", failure)
            throw failure
        }
    }

    private fun validateIdentities(id: String, next: SessionOrganismMachine.State) {
        val candidate = next.organism ?: return
        check(candidate.id == id && candidate.sessions.all { (key, node) -> key == node.id }) { "Неверная принадлежность организма" }
        val ids = candidate.sessions.keys + candidate.historyDeletedIds + id
        check(entries.none { (otherId, other) -> otherId != id &&
            (other.state.organism?.let { it.sessions.keys + it.historyDeletedIds + otherId } ?: setOf(otherId)).any(ids::contains) }) {
            "Идентификатор сессии уже принадлежит другому организму"
        }
    }
    private suspend fun dispatchLocked(id: String, entry: Entry, input: SessionOrganismMachine.Input): SessionOrganismMachine.Transition {
        entry.records.firstOrNull { record ->
            json.decodeFromString(Envelope.serializer(), record.detail).ref.inputId == input.stamp.id
        }?.let { record ->
            check(inputs.read(json.decodeFromString(Envelope.serializer(), record.detail).ref) == input) { "Идентификатор ввода организма занят" }
            // Input replay acknowledges durable state; it never repeats the parent's outputs.
            return SessionOrganismMachine.Transition(entry.state)
        }
        val next = SessionOrganismMachine.reduce(entry.state, input)
        next.reject?.let { rejected -> when (rejected.kind) {
            SessionOrganismMachine.Rejection.VERSION -> throw StaleSessionVersion()
            SessionOrganismMachine.Rejection.QUARANTINE -> throw SessionQuarantineBlocked(checkNotNull(rejected.sessionId), rejected.reason)
            SessionOrganismMachine.Rejection.UNKNOWN -> error(rejected.reason)
            SessionOrganismMachine.Rejection.VALIDATION -> throw ToolArgumentRejection(rejected.reason)
        } }
        validateIdentities(id, next.state)
        if (next.state == entry.state && next.outputs.isEmpty()) return next
        try { commit(id, entry, input, next.state) }
        catch (failure: Exception) { unknown(id, entry, failure); throw failure }
        publish(id, entry)
        checkpoint(id, entry)
        return next
    }
    private suspend fun commit(id: String, entry: Entry, input: SessionOrganismMachine.Input, next: SessionOrganismMachine.State) = withContext(NonCancellable) {
        val expected = entry.revision
        val ref = inputs.save(id, input)
        val detail = json.encodeToString(Envelope.serializer(), Envelope(ref, expected.resetEpoch))
        val accepted = try {
            checkNotNull(journal.append(expected, OPERATION, input.stamp.at, detail)) { "Организм изменён другим владельцем" }.also {
                check(it.stream == expected.stream && it.seq > expected.seq && it.operation == OPERATION && it.detail == detail && it.at == input.stamp.at) { "Неверное подтверждение записи организма" }
            }
        } catch (failure: Exception) {
            val observed = try { validatedSnapshot(expected.stream) } catch (read: Exception) { failure.addSuppressed(read); throw failure }
            val last = observed.records.lastOrNull()?.takeIf { it.seq > expected.seq && it.detail == detail && it.at == input.stamp.at &&
                observed.revision.resetEpoch == expected.resetEpoch && observed.records.dropLast(1) == entry.records } ?: throw failure
            try { check(inputs.read(ref) == input) } catch (read: Exception) { failure.addSuppressed(read); throw failure }
            if (failure is CancellationException) throw failure
            last
        }
        entry.records += accepted
        entry.revision = expected.copy(seq = accepted.seq)
        entry.inputId = input.stamp.id
        entry.state = next
    }
    private suspend fun validatedSnapshot(source: String): JournalSnapshot {
        val snapshot = journal.snapshot(source)
        check(snapshot.revision.stream == source && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) { "Неверное поколение журнала организма" }
        var previous = 0L
        val ids = mutableSetOf<String>()
        for (record in snapshot.records) {
            val envelope = json.decodeFromString(Envelope.serializer(), record.detail)
            check(record.stream == source && record.operation == OPERATION && record.seq > previous &&
                envelope.ref.organismId.isNotBlank() && envelope.ref.inputId.isNotBlank() && stream(envelope.ref.organismId) == source && envelope.resetEpoch == snapshot.revision.resetEpoch && ids.add(envelope.ref.inputId)) { "Повреждён порядок журнала организма" }
            previous = record.seq
        }
        if (snapshot.records.isNotEmpty()) check(previous == snapshot.revision.seq) { "Журнал организма прочитан не полностью" }
        return snapshot
    }
    private fun Entry.projection() = SessionOrganismProjection(checkNotNull(state.organism), OrganismRevision(revision.stream, revision.seq, revision.resetEpoch, inputId))
    private fun publish(id: String, entry: Entry) {
        entry.state.organism?.let { mutableOrganisms.value += id to it; mutableProjections.value += id to entry.projection() }
    }
    private fun unknown(id: String, entry: Entry, failure: Throwable) {
        entry.state = SessionOrganismMachine.reduce(entry.state, SessionOrganismMachine.Fact.PersistenceUnknown(stamp())).state
        publish(id, entry)
        report(id, "commit", failure)
    }
    private fun report(id: String, operation: String, failure: Throwable) {
        AppLog.error("organism", "persistence_failed", mapOf("organismId" to id, "operation" to operation, "causeType" to failure::class.simpleName.orEmpty()))
        mutableFailures.value += id to "Не удалось подтвердить сохранение задачи. Новые действия требуют проверки хранилища."
    }
    private fun checkpoint(id: String, entry: Entry) {
        try { storage.write(key(id), json.encodeToString(SessionOrganism.serializer(), checkNotNull(entry.state.organism))); mutableFailures.value -= id }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { report(id, "snapshot", failure) }
    }
    /** Producers are stopped and joined by the application before this explicit reset boundary. */
    override suspend fun clearForReset(): Unit = withContext(NonCancellable + Dispatchers.Default) { lock.withLock {
        storage.keys("session-organism-").forEach(storage::delete)
        journal.streams().filter { it.startsWith(PREFIX) }.forEach { check(journal.drop(journal.snapshot(it).revision)) }
        inputs.clear()
        entries.clear(); initialized = false; loadFailure = null; mutableOrganisms.value = emptyMap(); mutableProjections.value = emptyMap(); mutableFailures.value = emptyMap()
    } }
    private fun sanitize(input: SessionOrganismMachine.Input): SessionOrganismMachine.Input {
        val secrets = knownSecrets()
        if (input is SessionOrganismMachine.Intent.AdmitIntegration)
            requireTool(input.request.checks.flatten().all { PlanningDiagnostics.redact(it, secrets) == it }) { "Укажите итоговые проверки без секретов в аргументах" }
        fun safe(element: JsonElement, redact: Boolean = false): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (key, value) -> safe(value, key in REDACTED_FIELDS) })
            is JsonArray -> JsonArray(element.map { safe(it, redact) })
            is JsonPrimitive -> if (redact && element.isString) JsonPrimitive(PlanningDiagnostics.redact(element.content, secrets)) else element
        }
        return json.decodeFromJsonElement(SessionOrganismMachine.Input.serializer(), safe(json.encodeToJsonElement(SessionOrganismMachine.Input.serializer(), input)))
    }
    private companion object {
        const val PREFIX = "organism-workflow:"
        const val OPERATION = "organism.input.v1"
        val REDACTED_FIELDS = setOf("name", "reason", "text", "summary", "error", "evidence", "artifacts", "checks", "output", "detail", "sourceVersion", "ruleVersion", "attachments", "omissions", "acceptance", "observed", "blockedReason", "prompt")
    }

    override suspend fun setArchiveVisibility(id: String, sessionId: String, generation: Long, archived: Boolean,
        stillReady: () -> Boolean): SessionOrganism {
        val result = withContext(dispatcher) { lock.withLock {
            val entry = load(id)
            dispatchLocked(id, entry, SessionOrganismMachine.Intent.SetArchiveVisibility(stamp(), id, sessionId, generation, archived, stillReady()))
        } }
        return checkNotNull(result.state.organism)
    }

    override suspend fun applyLimits(id: String, limits: OrganismLimits): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.ApplyLimits(stamp(), id, limits))
        return checkNotNull(result.state.organism)
    }

    override suspend fun admitIntegration(scope: SessionAuthority, request: SessionIntegrationRequest, fingerprint: String): Pair<SessionOrganism, Boolean> {
        val result = dispatch(scope.organismId, SessionOrganismMachine.Intent.AdmitIntegration(stamp(), scope, request, fingerprint))
        return checkNotNull(result.state.organism) to result.outputs.filterIsInstance<SessionOrganismMachine.Output.IntegrationAdmitted>().single().fresh
    }

    override suspend fun checkpointIntegration(id: String, record: SessionIntegration): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.CheckpointIntegration(stamp(), id, record))
        return checkNotNull(result.state.organism)
    }

    override suspend fun adopt(projectId: String, root: CodingSession, descendants: List<CodingSession>, limits: OrganismLimits): SessionOrganism {
        val result = dispatch(root.organismId ?: root.id, SessionOrganismMachine.Fact.Adopt(stamp(), projectId, root, descendants, limits))
        return checkNotNull(result.state.organism)
    }

    override suspend fun renameByUser(id: String, target: String, name: String, operationId: String): SessionOrganism {
        val fingerprint = "USER:" + toolArgumentsFingerprint(json.encodeToJsonElement(OrganismCommand.serializer(), OrganismCommand(OrganismAction.RENAME, target, name.trim())))
        val result = dispatch(id, SessionOrganismMachine.Intent.RenameByUser(stamp(), id, target, name, operationId, fingerprint))
        return checkNotNull(result.state.organism)
    }

    override suspend fun check(scope: SessionAuthority): Unit {
        val result = dispatch(scope.organismId, SessionOrganismMachine.Intent.Check(stamp(), scope))
    }

    override suspend fun requestFailureStop(id: String, rootId: String, generation: Long, reason: String): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.RequestFailureStop(stamp(), id, rootId, generation, reason))
        return checkNotNull(result.state.organism)
    }

    override suspend fun authorizePlanRetry(id: String, sessionId: String, binding: SessionLegacyAttempt,
        continuationConfirmed: Boolean): PlanAttemptRetryAuthorization? {
        val result = dispatch(id, SessionOrganismMachine.Intent.AuthorizePlanRetry(stamp(), id, sessionId, binding, continuationConfirmed))
        return result.outputs.filterIsInstance<SessionOrganismMachine.Output.RetryAuthorized>().single().authorization
    }

    override suspend fun reconcileAndAuthorizePlanRetry(id: String, request: OrganismRetryRequest, proof: PlanRetryRecoveryProof,
        requestedBinding: SessionLegacyAttempt, continuationConfirmed: Boolean): PlanAttemptRetryAuthorization {
        val result = dispatch(id, SessionOrganismMachine.Fact.ReconcileAndAuthorizePlanRetry(stamp(), id, request, proof, requestedBinding, continuationConfirmed))
        return checkNotNull(result.outputs.filterIsInstance<SessionOrganismMachine.Output.RetryAuthorized>().single().authorization)
    }

    override suspend fun admitPlanWorker(id: String, session: CodingSession, task: SessionTask,
        binding: SessionLegacyAttempt, rules: PlanningRulesSnapshot?, unfinishedStageIds: Set<String>,
        retryAuthorization: PlanAttemptRetryAuthorization?, continuationConfirmed: Boolean): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.AdmitPlanWorker(stamp(), id, session, task, binding, rules, unfinishedStageIds, retryAuthorization, continuationConfirmed))
        return checkNotNull(result.state.organism)
    }

    override suspend fun acceptPlanResult(id: String, binding: SessionLegacyAttempt, result: SessionResult): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.AcceptPlanResult(stamp(), id, binding, result))
        return checkNotNull(result.state.organism)
    }

    override suspend fun recordWorkspace(id: String, sessionId: String, generation: Long, workspace: SessionCodingWorkspace): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.RecordWorkspace(stamp(), id, sessionId, generation, workspace))
        return checkNotNull(result.state.organism)
    }

    override suspend fun changeRootMode(id: String, sessionId: String, mode: CodingInteractionMode): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.ChangeRootMode(stamp(), id, sessionId, mode))
        return checkNotNull(result.state.organism)
    }

    override suspend fun prepareUserTurn(id: String, sessionId: String, requestId: String): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.PrepareUserTurn(stamp(), id, sessionId, requestId))
        return checkNotNull(result.state.organism)
    }

    override suspend fun resolveSessionQuarantine(id: String, sessionId: String, resolution: SessionQuarantineResolution): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.ResolveSessionQuarantine(stamp(), id, sessionId, resolution))
        return checkNotNull(result.state.organism)
    }

    override suspend fun reconcileInterruptedRun(id: String, sessionId: String, generation: Long, version: Long): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.ReconcileInterruptedRun(stamp(), id, sessionId, generation, version))
        return checkNotNull(result.state.organism)
    }

    override suspend fun beginRun(id: String, sessionId: String): SessionNode {
        val result = dispatch(id, SessionOrganismMachine.Intent.BeginRun(stamp(), id, sessionId))
        return result.outputs.filterIsInstance<SessionOrganismMachine.Output.RunAdmitted>().single().node
    }

    override suspend fun finishStop(id: String, sessionIds: Set<String>): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.FinishStop(stamp(), id, sessionIds))
        return checkNotNull(result.state.organism)
    }

    override suspend fun requestUserStop(id: String, target: String, operationId: String, archive: Boolean): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.RequestUserStop(stamp(), id, target, operationId, archive))
        return checkNotNull(result.state.organism)
    }

    override suspend fun restoreByUser(id: String, target: String, operationId: String, rules: PlanningRulesSnapshot, sourceVersion: String?): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.RestoreByUser(stamp(), id, target, operationId, rules, sourceVersion))
        return checkNotNull(result.state.organism)
    }

    override suspend fun command(scope: SessionAuthority, operationId: String, request: OrganismCommand): SessionOrganism {
        return checkNotNull(commandTransition(scope, operationId, request).state.organism)
    }

    /** Typed outputs travel to the runtime parent only after the whole organism input commits. */
    override suspend fun commandTransition(scope: SessionAuthority, operationId: String, request: OrganismCommand): SessionOrganismMachine.Transition {
        val fingerprint = toolArgumentsFingerprint(json.encodeToJsonElement(OrganismCommand.serializer(), request))
        return dispatch(scope.organismId, SessionOrganismMachine.Intent.Command(stamp(), scope, operationId, request, fingerprint))
    }

    override suspend fun observe(id: String, sessionId: String, generation: Long, observed: SessionObservedState): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.Observe(stamp(), id, sessionId, generation, observed))
        return checkNotNull(result.state.organism)
    }

    override suspend fun acknowledge(id: String, deliveryId: String, recipient: String, generation: Long, processed: Boolean): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.Acknowledge(stamp(), id, deliveryId, recipient, generation, processed))
        return checkNotNull(result.state.organism)
    }

    override suspend fun charge(scope: SessionAuthority, tokens: Long): SessionOrganism {
        val result = dispatch(scope.organismId, SessionOrganismMachine.Fact.Charge(stamp(), scope, tokens))
        return checkNotNull(result.state.organism)
    }

    override suspend fun beginAuxiliary(context: OrganismAuxiliaryAdmission): SessionAuxiliaryRun {
        val result = dispatch(context.organismId, SessionOrganismMachine.Intent.BeginAuxiliary(stamp(), context))
        return result.outputs.filterIsInstance<SessionOrganismMachine.Output.AuxiliaryAdmitted>().single().run
    }

    override suspend fun chargeAuxiliary(organismId: String, auxiliaryId: String, sourceId: String, totalTokens: Long): SessionOrganism {
        val result = dispatch(organismId, SessionOrganismMachine.Fact.ChargeAuxiliary(stamp(), organismId, auxiliaryId, sourceId, totalTokens))
        return checkNotNull(result.state.organism)
    }

    override suspend fun finishAuxiliary(organismId: String, auxiliaryId: String, observed: SessionObservedState): SessionOrganism {
        val result = dispatch(organismId, SessionOrganismMachine.Fact.FinishAuxiliary(stamp(), organismId, auxiliaryId, observed))
        return checkNotNull(result.state.organism)
    }

    override suspend fun recover(id: String): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.Restored(stamp(), id))
        return checkNotNull(result.state.organism)
    }

    override suspend fun deleteHistoryByUser(id: String, target: String?): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.DeleteHistoryByUser(stamp(), id, target))
        return checkNotNull(result.state.organism)
    }

    override suspend fun recordResult(id: String, result: SessionResult): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.RecordResult(stamp(), id, result))
        return checkNotNull(result.state.organism)
    }

    override suspend fun proposeImmunityInterventions(id: String): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.ProposeImmunityInterventions(stamp(), id))
        return checkNotNull(result.state.organism)
    }

    override suspend fun acceptImmunityIntervention(id: String, proposalId: String, action: ImmunityAction,
        rules: PlanningRulesSnapshot?, sourceVersion: String?, reconciled: Boolean,
    ): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.AcceptImmunityIntervention(stamp(), id, proposalId, action, rules, sourceVersion, reconciled))
        return checkNotNull(result.state.organism)
    }

    override suspend fun finishImmunityIntervention(id: String, proposalId: String, error: String?): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.FinishImmunityIntervention(stamp(), id, proposalId, error))
        return checkNotNull(result.state.organism)
    }

    override suspend fun dismissImmunityIntervention(id: String, proposalId: String): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.DismissImmunityIntervention(stamp(), id, proposalId))
        return checkNotNull(result.state.organism)
    }

    override suspend fun inspectSignals(id: String): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Intent.InspectSignals(stamp(), id))
        return checkNotNull(result.state.organism)
    }

    override suspend fun quarantine(id: String, sessionId: String, generation: Long, operationId: String, reason: String): SessionOrganism {
        val result = dispatch(id, SessionOrganismMachine.Fact.Quarantine(stamp(), id, sessionId, generation, operationId, reason))
        return checkNotNull(result.state.organism)
    }
}
